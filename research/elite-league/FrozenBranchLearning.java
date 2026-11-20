import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;

/** Fit an ordinary deterministic MLP to complete-bout comparisons from a shared simulator snapshot. */
final class FrozenBranchLearning {
    static final Path F=FrozenSumoResponse.F;
    static final class Pilot extends DuelEnv.Player{
        Arena.Neural policy;
        boolean ground;double gap,age=Double.POSITIVE_INFINITY;
        int heads,chosen,channel=-1,left,value;
        double[] firstView;
        Pilot(Arena.Model m,int rtt,int other){super("branch",0,rtt,0);policy=new Arena.Neural(m,rtt,other);}
        Pilot(Pilot p){super(p.style,0,p.rtt,0);policy=p.policy.copy();ground=p.ground;gap=p.gap;age=p.age;
            heads=p.heads;chosen=p.chosen;channel=p.channel;left=p.left;value=p.value;firstView=p.firstView;}
        Pilot copy(){return new Pilot(this);}
        public boolean injected(PeriodicComboClock.Observation o,double t){return policy.injected(o,t);}
        public void received(PeriodicComboClock.PacketObservation p){policy.received(p);}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var c=policy.input(o);heads++;ground=o.own().onGround();age=o.time()-o.lastOwnHurtTime();
            gap=Math.hypot(o.targetX()-o.own().x(),o.targetZ()-o.own().z());
            if(left==0)return c;
            int key=channel==0?value:c.key(),side=channel==4?value:(int)Math.signum(c.strafe());
            boolean click=channel==2?value==1:c.click(),jump=channel==3?value==1&&ground:c.jump();
            int a=DuelNetwork.pack(key,side,click,jump);
            if(firstView==null){firstView=policy.net.features();chosen=a;}
            policy.net.applied(a);left--;return new PeriodicComboClock.Control(key,side*.98f,click,jump);
        }
    }
    record Label(double[] x,int action,int channel,double advantage){}
    record Sample(List<Label> labels,int trials,int roots,int changed){}
    static Sample sample(Arena.Scenario sc,Arena.Model model,long seed,boolean audit)throws Exception{
        var r=new Random(seed);int phase=r.nextInt(3),channel=new int[]{0,0,2,3,3,4}[r.nextInt(6)];
        int min=phase==0?3+r.nextInt(18):phase==1?8+r.nextInt(25):20+r.nextInt(100);
        int horizon=new int[]{1,3,6}[r.nextInt(3)];var env=sc.env();boolean first=sc.first();
        int a=first?env.rtt:env.otherRtt,b=first?env.otherRtt:env.rtt;
        var p=new Pilot(model,a,b);var g=env.new Game(first,p,sc.opponent().create(b,a,0,true,DuelEnv.regulationDiameter(),10));
        while(g.step<env.serverEnds.length&&g.winner()<0){
            double end=Math.min(env.limit,env.serverEnds[g.step]);
            if(!g.clock.advanceToBeforeNextHead(first,end)){g.step++;continue;}
            if(p.heads>=min&&(phase!=1||p.age<180&&p.ground||p.heads>=min+50))break;
            if(!g.clock.runHeadInterval(first,end))g.step++;
        }
        if(g.step>=env.serverEnds.length||g.winner()>=0)return new Sample(List.of(),0,0,0);
        int[] values=switch(channel){case 0->new int[]{0,1,2,3};case 4->new int[]{-1,0,1};default->new int[]{0,1};};
        var actors=new ArrayList<Pilot>();var outcomes=new ArrayList<Double>();double[] x=null;
        String hash=audit?FrozenResponseMechanics.digest(g):null;
        // First continuation has an active recorder but no changed channel.
        for(int i=-1;i<values.length;i++){
            var child=env.new Game(g,g.a.copy(),g.b.copy());var pilot=(Pilot)child.our();
            if(audit&&!FrozenResponseMechanics.digest(child).equals(hash))throw new AssertionError("Fork did not preserve both players and clock");
            pilot.channel=i<0?-1:channel;pilot.value=i<0?0:values[i];pilot.left=horizon;child.finish();
            if(pilot.firstView==null)throw new AssertionError("Did not execute the selected head");
            if(x==null)x=pilot.firstView;else if(!Arrays.equals(x,pilot.firstView))throw new AssertionError("Different local input at branch");
            actors.add(pilot);outcomes.add(child.reward());
        }
        if(audit){g.finish();if(g.reward()!=outcomes.get(0))throw new AssertionError("No-op path diverged");}
        var distinct=new HashMap<Integer,List<Double>>();
        for(int i=0;i<actors.size();i++)distinct.computeIfAbsent(actors.get(i).chosen,k->new ArrayList<>()).add(outcomes.get(i));
        if(distinct.size()==1)return new Sample(List.of(),actors.size(),1,0);
        var means=new HashMap<Integer,Double>();
        for(var item:distinct.entrySet())means.put(item.getKey(),item.getValue().stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        double avg=means.values().stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        var labels=new ArrayList<Label>();
        for(var item:means.entrySet()){double d=item.getValue()-avg;if(Math.abs(d)>1e-10)labels.add(new Label(x,item.getKey(),channel,d));}
        return new Sample(labels,actors.size(),1,labels.isEmpty()?0:1);
    }
    static void learn(float[] w,List<Label> data,PPO.Adam adam,double rate,int width){
        if(data.isEmpty())return;
        var dist=new PPO.Dist(.36);double[] grad=new double[w.length],h=new double[width],out=new double[5],d=new double[5];
        for(var label:data){
            PPO.forward(w,label.x,h,out);dist.probability(out,label.x[8]>0,label.action,d);
            for(int k=0;k<5;k++)if(k!=label.channel && !(label.channel==0&&k==1))d[k]=0;
            PPO.backward(w,label.x,h,d,grad,label.advantage);
        }
        adam.update(w,grad,data.size(),rate);
    }
    public static void main(String[] args)throws Exception{
        try{
            FrozenSumoResponse.load();String id=args[0];int seed=Integer.parseInt(args[1]);var rng=new Random(seed);
            var parent=InputStudy.fighter(id,Path.of(args[2]));var w=parent.model().weights();int width=DuelNetwork.width(parent.model().family());
            var adam=new PPO.Adam(w.length);var root=FrozenSumoResponse.draw(rng);
            sample(root,parent.model(),seed,true);
            Path src=F.resolve("fits/"+id+"-branches.tmp");
            try(var log=new PrintWriter(Files.newBufferedWriter(src));var best=new FrozenSumoResponse.Best(id,FrozenSumoResponse.panel(FrozenSumoResponse.dev))){
                log.println("update,roots,rollouts,choices_with_outcome_difference,labels");int roots=0,games=0,changed=0,count=0;
                for(int step=0;step<=768;step++){
                    if(step%32==0){best.consider(step,parent);log.printf("%d,%d,%d,%d,%d%n",step,roots,games,changed,count);log.flush();}
                    if(step==768)break;
                    var jobs=new ArrayList<Callable<Sample>>();
                    for(int i=0;i<48;i++){var s=FrozenSumoResponse.draw(rng);long ep=rng.nextLong();jobs.add(()->sample(s,parent.model(),ep,false));}
                    var labels=new ArrayList<Label>();
                    for(var item:PingLeague.pool.invokeAll(jobs)){var v=item.get();roots+=v.roots;games+=v.trials;changed+=v.changed;labels.addAll(v.labels);}
                    count+=labels.size();learn(w,labels,adam,step<448?.0006:step<640?.00028:.00012,width);
                }
                best.finish();if(log.checkError())throw new IOException("Incomplete rollout log");
            }
            Files.move(src,F.resolve("fits/"+id+"-branches.csv"),StandardCopyOption.ATOMIC_MOVE);
        }finally{PingLeague.pool.shutdown();}
    }
}
