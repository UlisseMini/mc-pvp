import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.Json;

/** Monte Carlo sumo with a clipped policy-gradient actor and a learned value baseline.
 * Sampled keys enter the same physical input queue as deterministic evaluation. */
final class PPO {
    static final int IN=DuelNetwork.INPUTS,H=DuelNetwork.HIDDEN,OUT=DuelNetwork.OUTPUTS;
    static final double INF=Double.POSITIVE_INFINITY;
    static double sigmoid(double v){return v>=0?1/(1+Math.exp(-v)):Math.exp(v)/(1+Math.exp(v));}
    static double logp(double p){return Math.log(Math.max(1e-15,p));}
    static final class Dist {
        final double temperature;
        Dist(double t){temperature=t;}
        int ordinal(double x,double low,double high,Random r){
            double u=r.nextDouble();
            if(u<sigmoid((low-x)/temperature))return -1;
            return u>1-sigmoid((x-high)/temperature)?1:0;
        }
        int sample(double[] s,boolean ground,Random r){
            int walk=ordinal(s[0],-.65,0,r);
            int k=walk<0?3:walk==0?0:r.nextDouble()<sigmoid(s[1]/temperature)?2:1;
            return DuelNetwork.pack(k,ordinal(s[4],-.45,.45,r),
                r.nextDouble()<sigmoid(s[2]/temperature),ground&&r.nextDouble()<sigmoid(s[3]/temperature));
        }
        double ordinalLog(double x,double low,double high,int actual,double[] gradient,int i){
            double a=sigmoid((low-x)/temperature),b=sigmoid((x-high)/temperature);
            double p=actual<0?a:actual>0?b:1-a-b;
            gradient[i]=(actual<0?a-1:actual>0?1-b:a-b)/temperature;
            return logp(p);
        }
        double binary(double x,boolean y,double[] gradient,int i){
            double p=sigmoid(x/temperature);gradient[i]=((y?1:0)-p)/temperature;
            return logp(y?p:1-p);
        }
        double probability(double[] s,boolean ground,int action,double[] grad){
            int k=DuelNetwork.key(action);
            double log=ordinalLog(s[0],-.65,0,k==3?-1:k==0?0:1,grad,0);
            grad[1]=grad[3]=0;
            if(k==1||k==2)log+=binary(s[1],k==2,grad,1);
            log+=binary(s[2],DuelNetwork.click(action),grad,2);
            if(ground)log+=binary(s[3],DuelNetwork.jump(action),grad,3);
            return log+ordinalLog(s[4],-.45,.45,DuelNetwork.side(action),grad,4);
        }
    }
    static final class Step {
        final double[] x;final int action;final double oldLog,value;
        double advantage,target;
        Step(double[] x,int a,double log,double v){this.x=x;action=a;oldLog=log;value=v;}
    }
    static double value(double[] v,double[] x){
        double y=v[0];for(int i=0;i<IN;i++)y+=v[i+1]*x[i];return Math.tanh(y);
    }
    static final class Collector extends DuelEnv.Player{
        final DuelNetwork actor;final Dist dist;final double[] critic,gradient=new double[OUT];final Random rng;
        final List<Step> steps=new ArrayList<>();
        Collector(float[] w,double[] v,double t,long seed,int ping){
            super("ppo",0,ping,0);actor=new DuelNetwork(DuelNetwork.Family.MLP,w);critic=v;dist=new Dist(t);rng=new Random(seed);
        }
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var q=o.own();actor.step(q.x(),q.y(),q.z(),o.targetX(),o.targetY(),o.targetZ(),q.vx(),q.vy(),q.vz(),q.onGround(),
                o.sprint(),o.time()-o.lastOwnHurtTime(),o.time()-o.lastTargetHurtTime(),rtt);
            double[] x=actor.features(),s=actor.scores();int action=dist.sample(s,q.onGround(),rng);
            steps.add(new Step(x,action,dist.probability(s,q.onGround(),action,gradient),value(critic,x)));
            actor.applied(action);
            return new PeriodicComboClock.Control(DuelNetwork.key(action),DuelNetwork.side(action)*.98f,DuelNetwork.click(action),DuelNetwork.jump(action));
        }
    }
    record Batch(List<Step> steps,double reward){}
    static Batch trajectory(Arena.Scenario sc,float[] weights,double[] critic,double temp,long seed){
        DuelEnv e=sc.env();boolean first=sc.first();
        var p=new Collector(weights,critic,temp,seed,first?e.rtt:e.otherRtt);
        var g=e.new Game(first,p,sc.opponent().create(first?e.otherRtt:e.rtt,0));g.finish();
        double reward=g.reward()*2-1,nextAdv=0,nextValue=0;
        for(int i=p.steps.size()-1;i>=0;i--){
            var step=p.steps.get(i);
            double immediate=i==p.steps.size()-1?reward:0;
            // Gamma=1 for the complete undiscounted terminal ring-out. GAE lambda .97.
            nextAdv=immediate+nextValue-step.value+.97*nextAdv;nextValue=step.value;
            step.advantage=nextAdv;step.target=nextAdv+step.value;
        }
        return new Batch(p.steps,(reward+1)/2);
    }
    static void forward(float[] w,double[] x,double[] hidden,double[] output){
        int idx=0;
        for(int i=0;i<H;i++){double v=w[idx++];for(double input:x)v+=w[idx++]*input;hidden[i]=v/(1+Math.abs(v));}
        for(int k=0;k<OUT;k++){double v=w[idx++];for(double h:hidden)v+=w[idx++]*h;output[k]=v;}
    }
    static void backward(float[] w,double[] x,double[] h,double[] dOut,double[] grad,double factor){
        int pos=H*(IN+1);double[] dh=new double[H];
        for(int k=0;k<OUT;k++){
            double d=factor*dOut[k];grad[pos++]+=d;
            for(int i=0;i<H;i++){dh[i]+=d*w[pos];grad[pos++]+=d*h[i];}
        }
        pos=0;
        for(int i=0;i<H;i++){
            double a=1-Math.abs(h[i]),d=dh[i]*a*a;grad[pos++]+=d;
            for(double input:x)grad[pos++]+=d*input;
        }
    }
    static final class Adam{
        final double[] m,v;int t;
        Adam(int n){m=new double[n];v=new double[n];}
        void update(float[] w,double[] g,int batch,double lr){
            double norm=0;for(double x:g)norm+=x*x;
            double denom=Math.max(batch,Math.sqrt(norm)/.6);t++;
            double lrT=lr*Math.sqrt(1-Math.pow(.999,t))/(1-Math.pow(.9,t));
            for(int i=0;i<w.length;i++){
                double z=g[i]/denom;m[i]=.9*m[i]+.1*z;v[i]=.999*v[i]+.001*z*z;
                w[i]+=(float)(lrT*m[i]/(Math.sqrt(v[i])+1e-8));
            }
        }
    }
    static void update(List<Step> data,float[] w,double[] critic,Adam opt,Random rng,Dist dist,double lr){
        int count=data.size();double mean=0,sq=0;
        for(var s:data){mean+=s.advantage;sq+=s.advantage*s.advantage;}
        mean/=count;double sd=Math.sqrt(Math.max(1e-8,sq/count-mean*mean));
        for(var s:data)s.advantage=(s.advantage-mean)/sd;
        double[] h=new double[H],out=new double[OUT],d=new double[OUT];
        for(int epoch=0;epoch<3;epoch++){
            Collections.shuffle(data,rng);
            for(int start=0;start<count;start+=256){
                int end=Math.min(start+256,count),n=end-start;
                double[] g=new double[w.length],vg=new double[critic.length];
                for(int i=start;i<end;i++){
                    var s=data.get(i);forward(w,s.x,h,out);
                    double ratio=Math.exp(Math.min(10,dist.probability(out,s.x[8]>0,s.action,d)-s.oldLog));
                    if(s.advantage>=0?ratio<1.18:ratio>.82)backward(w,s.x,h,d,g,s.advantage*ratio);
                    double v=value(critic,s.x),diff=(s.target-v)*(1-v*v);vg[0]+=diff;
                    for(int j=0;j<IN;j++)vg[1+j]+=s.x[j]*diff;
                }
                opt.update(w,g,n,lr);
                for(int j=0;j<critic.length;j++)critic[j]+=.02*vg[j]/n;
            }
        }
    }
    public static void main(String[] args)throws Exception{
        var dest=Path.of(args[0]);int steps=Integer.parseInt(args[1]),pairs=Integer.parseInt(args[2]),seed=Integer.parseInt(args[3]);
        var initial=Arena.Model.decode(Files.readString(Path.of(args[4])).trim());if(initial.family()!=DuelNetwork.Family.MLP)throw new IllegalArgumentException("MLP actor required");
        var field=new RobustField(Arrays.stream(args,7,args.length).map(Path::of).toList(),Path.of(args[5]),Path.of(args[6]));
        float[] w=initial.weights().clone();double[] critic=new double[IN+1];var opt=new Adam(w.length);
        var rng=new Random(seed);var pool=Executors.newFixedThreadPool(2);var past=new ArrayList<Arena.Fighter>();long bouts=0,heads=0;
        Files.createDirectories(dest);
        try(var log=Files.newBufferedWriter(dest.resolve("training.jsonl"),StandardOpenOption.CREATE_NEW)){
            for(int gen=0;gen<steps;gen++){
                double temp=.38-.16*gen/Math.max(1.,steps-1);
                var scenarios=field.draw(rng,pairs,past);var jobs=new ArrayList<Callable<Batch>>();
                for(var sc:scenarios){long episodeSeed=rng.nextLong();jobs.add(()->trajectory(sc,w,critic,temp,episodeSeed));}
                var data=new ArrayList<Step>();double total=0;
                for(var f:pool.invokeAll(jobs)){var b=f.get();data.addAll(b.steps);total+=b.reward;}
                heads+=data.size();bouts+=jobs.size();
                // Deterministic baselines on the precise same draw of opponents, seats, starts and timings.
                var controlWork=new ArrayList<Callable<Double>>();
                for(var ctrl:List.of(Evolve.WT,Evolve.HOLD))controlWork.add(()->scenarios.stream().mapToDouble(s->s.game(ctrl)).average().orElseThrow());
                var control=pool.invokeAll(controlWork);bouts+=scenarios.size()*2L;
                update(data,w,critic,opt,rng,new Dist(temp),.00035);
                if(gen%8==0||gen==steps-1){
                    var model=new Arena.Model(DuelNetwork.Family.MLP,w.clone());past.add(new Arena.Fighter("ppo-history",model));
                    Files.writeString(dest.resolve(String.format("g%04d.net",gen)),model.encode()+"\n",StandardOpenOption.CREATE_NEW);
                }
                log.write(Json.encode(Map.of("generation",gen,"stochastic_actor_before_update",total/jobs.size(),"actor_heads",heads,
                    "bouts_including_controls",bouts,"wtap",control.get(0).get(),"hold",control.get(1).get())));
                log.newLine();log.flush();
                if(gen%16==0)System.out.printf("PPO %d g%d %,d heads %.3f%n",seed,gen,heads,total/jobs.size());
            }
        }finally{pool.shutdown();}
    }
}
