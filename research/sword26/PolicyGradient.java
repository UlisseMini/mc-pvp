package sword;

import java.nio.file.*;
import java.util.*;
import static sword.Fighter.*;

/** Recurrent PPO with one score for damage differential and the terminal outcome.
 * The archive and training starts are generated exclusively from training games. */
public final class PolicyGradient {
    static final int STRIDE=INPUTS+2, HEAD=HIDDEN*STRIDE, CHUNK=16;
    static final double TEMP=.65, SIGMA=.24;
    record Experience(double[] x,double[] before,Keys keys,double turn,double log,double value){}
    static class Step {
        Experience e;double reward,advantage,target;
        Step(Experience e){this.e=e;}
    }
    static final class Policy implements Model.Controller{
        final Fighter net;
        final double[] critic;
        final Random rng;
        final List<Step> trajectory=new ArrayList<>();
        Policy(double[] w,double[] c,long seed){net=new Fighter(w);critic=c;rng=new Random(seed);}
        public Keys act(View v){
            net.observe(v);
            double[] before=net.hidden.clone();
            forward(net.weights,net.input,before,net.hidden,net.output);
            var o=net.output;
            int f=category(o[0],o[1],rng),s=category(o[2],o[3],rng);
            double turn=o[7]+SIGMA*rng.nextGaussian();
            Keys k=new Keys(f,s,rng.nextDouble()<sig(o[4]),rng.nextDouble()<sig(o[5]),rng.nextDouble()<sig(o[6]),heading(turn));
            double log=prob(o,k,turn,new double[OUTPUTS]);
            trajectory.add(new Step(new Experience(net.input.clone(),before,k,turn,log,value(critic,net.input))));
            net.previous=k;return k;
        }
        public void reward(double r){trajectory.getLast().reward=r;}
        void finish(){
            double a=0,next=0;
            for(int i=trajectory.size()-1;i>=0;i--){
                Step s=trajectory.get(i);a=s.reward+.995*next-s.e.value+.995*.96*a;
                s.advantage=a;s.target=a+s.e.value;next=s.e.value;
            }
        }
    }
    static double value(double[] c,double[] x){
        double v=c[INPUTS];for(int j=0;j<INPUTS;j++)v+=x[j]*c[j];return v;
    }
    static double sig(double x){return 1/(1+Math.exp(-clip(x/TEMP,-40,40)));}
    static int category(double a,double b,Random rng){
        double x=Math.exp(clip(a/TEMP,-30,30)),y=Math.exp(clip(b/TEMP,-30,30));
        double u=rng.nextDouble()*(1+x+y);return u<1?0:u<1+x?1:-1;
    }
    static double prob(double[] o,Keys k,double turn,double[] d){
        double log=0;
        for(int i=0;i<=2;i+=2){
            int key=i==0?k.forward():k.side();
            double a=Math.exp(clip(o[i]/TEMP,-30,30)),b=Math.exp(clip(o[i+1]/TEMP,-30,30)),z=1+a+b;
            log+=Math.log((key==0?1:key==1?a:b)/z);
            d[i]=((key==1?1:0)-a/z)/TEMP;d[i+1]=((key==-1?1:0)-b/z)/TEMP;
        }
        for(int i=4;i<=6;i++){
            boolean yes=i==4?k.sprint():i==5?k.jump():k.click();
            double p=sig(o[i]);log+=Math.log(Math.max(1e-15,yes?p:1-p));
            d[i]=((yes?1:0)-p)/TEMP;
        }
        double diff=turn-o[7];d[7]=diff/(SIGMA*SIGMA);
        return log-.5*diff*d[7];
    }
    static void forward(double[] w,double[] x,double[] before,double[] hidden,double[] scores){
        int off=0;
        for(int h=0;h<HIDDEN;h++){
            double v=w[off++]+w[off++]*before[h];
            for(double f:x)v+=f*w[off++];hidden[h]=Math.tanh(v);
        }
        for(int j=0;j<OUTPUTS;j++){
            double v=w[off++];for(double h:hidden)v+=h*w[off++];scores[j]=v;
        }
    }
    record Segment(List<Step> steps,int start,int end){}
    static final class Adam {
        final double[] first=new double[COUNT],second=new double[COUNT];int iter;
        void step(double[] w,double[] grad,int n,double lr){
            iter++;double norm=0;for(double g:grad)norm+=g*g;
            double denom=Math.max(n,Math.sqrt(norm)/.7);
            for(int i=0;i<COUNT;i++){
                double g=grad[i]/denom;
                first[i]=.9*first[i]+.1*g;second[i]=.999*second[i]+.001*g*g;
                w[i]+=lr*(first[i]/(1-Math.pow(.9,iter)))/(Math.sqrt(second[i]/(1-Math.pow(.999,iter)))+1e-8);
            }
        }
    }
    static void update(double[] w,double[] critic,List<Segment> chunks,Random rng,Adam opt,double avg,double sd){
        double[][] hidden=new double[CHUNK+1][HIDDEN],head=new double[CHUNK][OUTPUTS];
        for(int epoch=0;epoch<3;epoch++){
            Collections.shuffle(chunks,rng);
            for(int batch=0;batch<chunks.size();batch+=32){
                double[] grad=new double[COUNT],cg=new double[INPUTS+1];int samples=0;
                for(int b=batch;b<Math.min(chunks.size(),batch+32);b++){
                    Segment seg=chunks.get(b);int len=seg.end-seg.start;
                    System.arraycopy(seg.steps.get(seg.start).e.before,0,hidden[0],0,HIDDEN);
                    for(int t=0;t<len;t++)forward(w,seg.steps.get(seg.start+t).e.x,hidden[t],hidden[t+1],head[t]);
                    double[] recurrent=new double[HIDDEN],d=new double[OUTPUTS];
                    for(int t=len-1;t>=0;t--){
                        Step s=seg.steps.get(seg.start+t);Experience e=s.e;
                        double ratio=Math.exp(clip(prob(head[t],e.keys,e.turn,d)-e.log,-12,12));
                        double adv=(s.advantage-avg)/sd;
                        double gain=(adv>0&&ratio>1.2||adv<0&&ratio<.8)?0:adv*ratio;
                        for(int j=0;j<OUTPUTS;j++){
                            int off=HEAD+j*(HIDDEN+1);double g=gain*d[j];
                            grad[off++]+=g;
                            for(int h=0;h<HIDDEN;h++){grad[off+h]+=g*hidden[t+1][h];recurrent[h]+=g*w[off+h];}
                        }
                        for(int h=0;h<HIDDEN;h++){
                            int off=h*STRIDE;double hval=hidden[t+1][h],g=recurrent[h]*(1-hval*hval);
                            grad[off]+=g;grad[off+1]+=g*hidden[t][h];recurrent[h]=g*w[off+1];
                            for(int j=0;j<INPUTS;j++)grad[off+2+j]+=g*e.x[j];
                        }
                        double err=s.target-value(critic,e.x);cg[INPUTS]+=err;
                        for(int j=0;j<INPUTS;j++)cg[j]+=err*e.x[j];samples++;
                    }
                }
                opt.step(w,grad,samples,.001);
                for(int j=0;j<cg.length;j++)critic[j]+=.008*cg[j]/samples;
            }
        }
    }
    public static void main(String[] args)throws Exception{
        Path dir=Path.of(args[0]);Files.createDirectory(dir);
        int iters=Integer.parseInt(args[1]);var rng=new Random(Long.parseLong(args[2]));
        var archive=new ArrayList<double[]>();
        double[] neuralParent=null;
        for(int i=3;i<args.length;i++){
            double[] opponent=read(Path.of(args[i]));
            archive.add(opponent);
            if(opponent.length!=TreePolicy.COUNT)neuralParent=opponent;
        }
        double[] w=neuralParent==null?Train.noise(rng,new double[COUNT],.18):Arrays.copyOf(neuralParent,COUNT);
        archive.add(w.clone());double[] critic=new double[INPUTS+1];var opt=new Adam();
        try(var log=Files.newBufferedWriter(dir.resolve("training.tsv"))){
            log.write("iteration\tdecisions\tmean_return\n");
            for(int iter=0;iter<iters;iter++){
                var chunks=new ArrayList<Segment>();double sum=0,sq=0,returns=0;int total=0;
                for(int bout=0;bout<40;bout++){
                    var actor=new Policy(w,critic,rng.nextLong());
                    var rival=new Fighter(archive.get(rng.nextInt(archive.size())));
                    var scene=new Model.Scene(rng.nextLong(),rng.nextInt(2),rng.nextInt(2));
                    if(bout%2==0)Model.duel(actor,rival::act,scene);else Model.duel(rival::act,actor,scene);
                    actor.finish();var steps=actor.trajectory;
                    for(var s:steps){sum+=s.advantage;sq+=s.advantage*s.advantage;total++;returns+=s.reward;}
                    for(int i=0;i<steps.size();i+=CHUNK)chunks.add(new Segment(steps,i,Math.min(steps.size(),i+CHUNK)));
                }
                double mean=sum/total;
                update(w,critic,chunks,rng,opt,mean,Math.sqrt(Math.max(1e-6,sq/total-mean*mean)));
                if(iter%8==0||iter==iters-1){archive.add(w.clone());write(dir.resolve("gen-%04d.net".formatted(iter)),w);}
                log.write("%d\t%d\t%.5f%n".formatted(iter,total,returns/40));log.flush();
            }
        }
    }
}
