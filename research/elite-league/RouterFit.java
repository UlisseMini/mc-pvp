import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import lab.ExpertRouter;
import lab.Json;

/** Train a legal value estimator with full-information outcomes from shared counterfactual branches. */
final class RouterFit {
    record Row(String group,double base,double wt,double[] x,double[] outcomes){}
    static List<Row> read(Path file)throws Exception{
        List<Row> rows=new ArrayList<>();
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file))))){
            String line;
            while((line=in.readLine())!=null){
                String[] v=line.split(" ");
                double[] x=null,rewards=null;
                if(v.length!=3){
                    if(v.length!=3+ExpertRouter.INPUTS+6)throw new IOException("Wrong router dataset width");
                    x=new double[ExpertRouter.INPUTS];rewards=new double[6];int index=3;
                    for(int j=0;j<x.length;j++)x[j]=Double.parseDouble(v[index++]);
                    for(int j=0;j<rewards.length;j++)rewards[j]=Double.parseDouble(v[index++]);
                    if(rewards[0]!=Double.parseDouble(v[1]))throw new AssertionError("Reference branch changed");
                }
                rows.add(new Row(v[0],Double.parseDouble(v[1]),Double.parseDouble(v[2]),x,rewards));
            }
        }
        return rows;
    }
    static void gradient(float[] w,Row r,double[] hidden,double[] scores,double[] g){
        int offset=ExpertRouter.HIDDEN*(ExpertRouter.INPUTS+1);
        double[] dh=new double[ExpertRouter.HIDDEN];
        for(int k=0;k<ExpertRouter.EXPERTS;k++){
            double delta=r.outcomes[k]-PPO.sigmoid(scores[k]);
            g[offset++]+=delta;
            for(int j=0;j<hidden.length;j++){dh[j]+=delta*w[offset];g[offset++]+=delta*hidden[j];}
        }
        offset=0;
        for(int j=0;j<hidden.length;j++){
            double d=dh[j]*Math.pow(1-Math.abs(hidden[j]),2);g[offset++]+=d;
            for(double v:r.x)g[offset++]+=d*v;
        }
    }
    record Score(double merit,double mean,double worst,int switches,double improvement,double losses,double fixes,
                 double wtap,double reference,double oracle,double lottery,int n,Map<String,double[]> groupSums){
        Object report(){return Map.ofEntries(Map.entry("merit",merit),Map.entry("mean",mean),Map.entry("worst",worst),
            Map.entry("switches",switches),Map.entry("point_gain",improvement),Map.entry("points_lost",losses),
            Map.entry("points_gained",fixes),Map.entry("wtap",wtap),Map.entry("reference",reference),
            Map.entry("privileged_oracle",oracle),Map.entry("random_continuation_expectation",lottery),
            Map.entry("matches",n),Map.entry("groups_points_n",groupSums));}
    }
    static Score score(List<Row> rows,float[] weights,double margin){
        var groups=new TreeMap<String,double[]>();double[] h=new double[ExpertRouter.HIDDEN],s=new double[ExpertRouter.EXPERTS];
        int switches=0;double gain=0,lost=0,fixed=0,wt=0,base=0,upper=0,lottery=0;
        for(var r:rows){
            double result=r.base,max=r.base,mean=r.base;
            if(r.x!=null){
                ExpertRouter.predict(weights,r.x,h,s);int i=ExpertRouter.choose(s,margin);
                result=r.outcomes[i];switches+=i==0?0:1;
                var vals=Arrays.stream(r.outcomes,0,ExpertRouter.EXPERTS).summaryStatistics();max=vals.getMax();mean=vals.getAverage();
            }
            gain+=result-r.base;lost+=Math.max(0,r.base-result);fixed+=Math.max(0,result-r.base);
            base+=r.base;wt+=r.wt;upper+=max;lottery+=mean;
            var group=groups.computeIfAbsent(r.group,k->new double[2]);group[0]+=result;group[1]++;
        }
        var rate=groups.values().stream().mapToDouble(v->v[0]/v[1]).summaryStatistics();
        return new Score(.6*rate.getAverage()+.4*rate.getMin(),rate.getAverage(),rate.getMin(),switches,gain,lost,fixed,
                         wt,base,upper,lottery,rows.size(),groups);
    }
    public static void main(String[] args)throws Exception{
        var fit=new ArrayList<>(read(Path.of(args[0])).stream().filter(r->r.x!=null).toList());
        var validation=read(Path.of(args[1]));Path out=Path.of(args[2]);
        double best=-1;int fewest=Integer.MAX_VALUE;
        try(var log=Files.newBufferedWriter(out.resolve("router-fit.jsonl"),StandardOpenOption.CREATE_NEW)){
            for(double decay:new double[]{0,.001,.01})for(int seed:new int[]{71153,93103}){
                var rng=new Random(seed);float[] w=new float[ExpertRouter.PARAMETERS];for(int i=0;i<w.length;i++)w[i]=(float)(.18*rng.nextGaussian());
                var opt=new PPO.Adam(w.length);
                for(int epoch=1;epoch<=64;epoch++){
                    Collections.shuffle(fit,rng);
                    for(int start=0;start<fit.size();start+=96){
                        int end=Math.min(fit.size(),start+96);
                        double[] h=new double[ExpertRouter.HIDDEN],s=new double[ExpertRouter.EXPERTS],g=new double[w.length];
                        for(var r:fit.subList(start,end)){ExpertRouter.predict(w,r.x,h,s);gradient(w,r,h,s,g);}
                        for(int i=0;i<w.length;i++)g[i]-=(end-start)*decay*w[i];
                        opt.update(w,g,end-start,.002/(1+epoch/60.0));
                    }
                    if(epoch%4!=0)continue;
                    for(double margin:new double[]{0,.15,.35,.65,1,2,100}){
                        Score sc=score(validation,w,margin);
                        log.write(Json.encode(List.of(seed,decay,epoch,margin,sc.merit,sc.mean,sc.switches,sc.improvement)));log.newLine();
                        if(sc.merit>best || sc.merit==best && sc.switches<fewest){
                            best=sc.merit;fewest=sc.switches;var skeleton=RouterData.skeleton();
                            System.arraycopy(w,0,skeleton.weights(),0,w.length);skeleton.weights()[w.length]=(float)margin;
                            Files.writeString(out.resolve("router.net"),skeleton.encode()+"\n");
                            Files.writeString(out.resolve("selection.json"),Json.encode(Map.of("model",sc.report(),
                                "seed",seed,"decay",decay,"epoch",epoch,"margin",margin,"fit_states",fit.size()))+"\n");
                        }
                    }
                    log.flush();
                }
            }
        }
    }
}
