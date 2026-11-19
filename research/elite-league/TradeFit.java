import java.nio.file.*;
import java.util.*;
import lab.TradeResponse;
import lab.DuelNetwork;
import lab.Json;

/** Fit asymmetric response values by clustering actual observable situations and averaging paired
 * differential outcomes within each prototype. Tune the amount of switching on separate fight clocks. */
final class TradeFit {
    record Example(String group,double[] x,double[] y,double base,double wt){}
    record Metric(double merit,int switches,double delta,double[] totals){}
    static List<Example> rows(Path path)throws Exception{
        var list=new ArrayList<Example>();
        for(String line:Files.readAllLines(path)){
            var parts=line.split(" ");double[] x=null,y=null;
            if(parts.length>3){
                x=new double[DuelNetwork.INPUTS];y=new double[TradeResponse.WORDS];int index=3;
                for(int i=0;i<x.length;i++)x[i]=Double.parseDouble(parts[index++]);
                for(int i=0;i<y.length;i++)y[i]=Double.parseDouble(parts[index++]);
            }
            list.add(new Example(parts[0],x,y,Double.parseDouble(parts[1]),Double.parseDouble(parts[2])));
        }
        return list;
    }
    static int slot(int i){return TradeResponse.TABLE_START+i*TradeResponse.NODE;}
    static int closest(float[] w,int k,double[] x){
        int best=0;double smallest=Double.POSITIVE_INFINITY;
        for(int i=0;i<k;i++){
            int j=slot(i);double dist=0;for(int f:TradeResponse.INPUTS){double v=x[f]-w[j++];dist+=v*v;}
            if(dist<smallest){best=i;smallest=dist;}
        }
        return best;
    }
    static void initialize(float[] w,List<Example> fit,Random rng,int k){
        for(int i=0;i<k;i++){
            var r=fit.get(rng.nextInt(fit.size()));int j=slot(i);
            for(int f:TradeResponse.INPUTS)w[j++]=(float)r.x[f];
        }
    }
    static void cluster(float[] w,List<Example> fit,int k){
        int dim=TradeResponse.INPUTS.length;
        for(int iteration=0;iteration<18;iteration++){
            double[][] sums=new double[k][TradeResponse.NODE];int[] n=new int[k];
            for(var r:fit){
                int i=closest(w,k,r.x);n[i]++;int j=0;
                for(int f:TradeResponse.INPUTS)sums[i][j++]+=r.x[f];
                for(double y:r.y)sums[i][j++]+=y-r.base;
            }
            for(int i=0;i<k;i++)if(n[i]!=0){
                for(int j=0;j<dim;j++)w[slot(i)+j]=(float)(sums[i][j]/n[i]);
                for(int j=dim;j<TradeResponse.NODE;j++)w[slot(i)+j]=(float)(sums[i][j]/(n[i]+5));
            }
        }
    }
    static Metric measure(float[] w,List<Example> rows){
        Map<String,double[]> group=new TreeMap<>();int switches=0;double total=0,base=0,wt=0;
        for(var r:rows){
            int choice=r.x==null?0:TradeResponse.decide(w,r.x,new double[TradeResponse.WORDS]);
            double value=r.x==null?r.base:r.y[choice];total+=value;base+=r.base;wt+=r.wt;if(choice!=0)switches++;
            var n=group.computeIfAbsent(r.group,a->new double[2]);n[0]+=value;n[1]++;
        }
        var mean=group.values().stream().mapToDouble(a->a[0]/a[1]).summaryStatistics();
        return new Metric(.6*mean.getAverage()+.4*mean.getMin(),switches,total-base,new double[]{total,base,wt});
    }
    public static void main(String[] args)throws Exception{
        var fit=rows(Path.of(args[0])).stream().filter(r->r.x!=null).toList();var valid=rows(Path.of(args[1]));
        var base=RobustField.load("base",Path.of(args[2])).model();Path out=Path.of(args[3]);
        if(base.family()!=DuelNetwork.Family.TACTIC && base.family()!=DuelNetwork.Family.MLP)throw new IllegalArgumentException("Unsupported base");
        double best=-1;int count=Integer.MAX_VALUE;
        for(int k:new int[]{6,12,24,32})for(int seed:new int[]{881,22277,91313}){
            float[] w=new float[TradeResponse.SIZE];w[0]=base.family()==DuelNetwork.Family.TACTIC?0:1;w[1]=k;w[4]=args.length>4?Integer.parseInt(args[4])-1:0;
            System.arraycopy(base.weights(),0,w,TradeResponse.HEADER,base.weights().length);
            initialize(w,fit,new Random(seed),k);cluster(w,fit,k);
            for(float bw:new float[]{.10f,.4f,1.2f})for(float margin:new float[]{0,.01f,.04f,.09f,.16f,1.01f}){
                w[2]=bw;w[3]=margin;var m=measure(w,valid);
                if(m.merit>best || m.merit==best && m.switches<count){
                    best=m.merit;count=m.switches;
                    Files.writeString(out.resolveSibling(out.getFileName()+".net"),DuelNetwork.encode(DuelNetwork.Family.RESPONSE,w)+"\n");
                    Files.writeString(out.resolveSibling(out.getFileName()+".json"),Json.encode(Map.of("k",k,"seed",seed,"bandwidth",bw,"min_gain",margin,
                       "matches",valid.size(),"training_states",fit.size(),"switches",count,"total_base_wtap_continuation",m.totals,"merit",best))+"\n");
                }
            }
        }
    }
}
