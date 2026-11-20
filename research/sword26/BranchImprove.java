package sword;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static sword.Fighter.*;

/** Learn one key's decision tree from paired full-episode returns at ordinary self-play states. */
public final class BranchImprove {
    static double[] choices(int head) {
        if (head < 2) return new double[]{-1,0,1};
        if (head < 5) return new double[]{-1,1};
        return new double[]{-1,-.78,0,.78,1};
    }
    static Keys replace(Keys k, int head, double v) {
        return new Keys(head==0?TreePolicy.key(v):k.forward(),head==1?TreePolicy.key(v):k.side(),
            head==2?v>0:k.sprint(),head==3?v>0:k.jump(),head==4?v>0:k.click(),head==5?Fighter.heading(v*1.5):k.offset());
    }
    static final class Intervention implements Model.Controller {
        final Fighter base; final int head, duration; final double value;
        int elapsed; double[] observation;
        Intervention(double[] w, int head, double value, int duration) {
            this.base=new Fighter(w);this.head=head;this.value=value;this.duration=duration;
        }
        public void prime(View v,Keys k) {base.prime(v,k);}
        public Keys act(View v) {
            var k=base.act(v);
            if(elapsed==0) observation=base.input.clone();
            if(elapsed++<duration) base.previous=replace(k,head,value);
            return base.previous;
        }
    }
    record Example(double[] x,double[] payoff){}
    static Example sample(double[] parent,List<double[]> field,int head,long seed) {
        var rng=new Random(seed); var rival=field.get(rng.nextInt(field.size())); boolean left=rng.nextBoolean();
        var scene=new Model.Scene(rng.nextLong(),rng.nextInt(2),rng.nextInt(2));
        var start=Starts.sample(scene,left?parent:rival,left?rival:parent,rng);
        int duration=1<<rng.nextInt(4); var values=choices(head);
        double[] observation=null,returns=new double[values.length];
        for(int i=0;i<values.length;i++){
            var policy=new Intervention(parent,head,values[i],duration); var other=new Fighter(rival);
            var game=left?Model.duel(policy,other,start,null):Model.duel(other,policy,start,null);
            returns[i]=(left?1:-1)*Train.fitness(game);observation=policy.observation;
        }
        double mean=Arrays.stream(returns).average().orElseThrow();
        for(int i=0;i<returns.length;i++) returns[i]-=mean;
        return new Example(observation,returns);
    }
    static int best(double[] sum) {
        int best=0;for(int i=1;i<sum.length;i++) if(sum[i]>sum[best])best=i;return best;
    }
    static void fit(double[] genes,int head,int node,List<Example> data,double[] values) {
        int offset=head*TreePolicy.SIZE, n=values.length;
        double[] total=new double[n];
        for(var e:data) for(int i=0;i<n;i++) total[i]+=e.payoff[i];
        int leaf=best(total);
        if(node>=TreePolicy.NODES){
            genes[offset+2*TreePolicy.NODES+node-TreePolicy.NODES]=values[leaf];return;
        }
        double gain=0,threshold=0;int feature=-1;
        for(int f=0;f<TreePolicy.SENSORS;f++){
            int axis=f;
            var sorted=new ArrayList<>(data);
            sorted.sort(Comparator.comparingDouble(e->e.x[axis]));
            double[] less=new double[n],more=total.clone();
            for(int at=0;at+1<sorted.size();at++){
                var e=sorted.get(at);for(int i=0;i<n;i++){less[i]+=e.payoff[i];more[i]-=e.payoff[i];}
                if(at+1<16||sorted.size()-at-1<16||e.x[f]==sorted.get(at+1).x[f])continue;
                double improvement=less[best(less)]+more[best(more)]-total[leaf];
                if(improvement>gain){gain=improvement;feature=f;threshold=(e.x[f]+sorted.get(at+1).x[f])*.5;}
            }
        }
        if(feature<0){
            // Every descendant returns the same action when no split improves the return.
            constant(genes,offset,node,values[leaf]);return;
        }
        genes[offset+node*2]=feature;genes[offset+node*2+1]=threshold;
        var lower=new ArrayList<Example>();var upper=new ArrayList<Example>();
        for(var e:data)(e.x[feature]<threshold?lower:upper).add(e);
        fit(genes,head,2*node+1,lower,values);fit(genes,head,2*node+2,upper,values);
    }
    static void constant(double[] genes,int offset,int node,double value){
        if(node>=TreePolicy.NODES)genes[offset+2*TreePolicy.NODES+node-TreePolicy.NODES]=value;
        else {constant(genes,offset,node*2+1,value);constant(genes,offset,node*2+2,value);}
    }
    public static void main(String[] args)throws Exception{
        var dir=Path.of(args[0]);Files.createDirectories(dir);
        var parent=read(Path.of(args[1]));if(parent.length!=TreePolicy.COUNT)throw new IllegalArgumentException("Tree parent required");
        var field=new ArrayList<double[]>();for(String p:Files.readAllLines(Path.of(args[2])))field.add(read(Path.of(p)));
        var rng=new Random(Long.parseLong(args[3]));int samples=Integer.parseInt(args[4]);
        try(var pool=Executors.newFixedThreadPool(Integer.parseInt(args[5]))){
            for(int h=0;h<TreePolicy.ACTIVE_HEADS;h++){
                int head=h;var jobs=new ArrayList<Callable<Example>>();
                for(int i=0;i<samples;i++){long seed=rng.nextLong();jobs.add(()->sample(parent,field,head,seed));}
                var data=new ArrayList<Example>();for(var result:pool.invokeAll(jobs))data.add(result.get());
                var candidate=parent.clone();fit(candidate,head,0,data,choices(head));
                write(dir.resolve("head-"+head+".net"),candidate);
            }
        }
    }
}
