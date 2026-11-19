import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.HumanTiming;
import lab.Json;

/** Behavioral cloning on whole-round demonstrations, with scoring on different rounds. */
final class HumanFit {
    static final int N=HumanTiming.INPUTS, H=HumanTiming.HIDDEN, O=HumanTiming.OUTPUTS;
    record Frame(String match, int tick, double[] observation, int label) {}
    record Example(String match, double[] input, int action) {}

    static ArrayList<Frame> load(Path path) throws Exception {
        var frames = new ArrayList<Frame>();
        for (String line : Files.readAllLines(path)) {
            String[] fields=line.split(" ");
            if (fields.length != 17) throw new IllegalArgumentException("Wrong frame width: "+line);
            double[] obs=new double[14];
            for (int i=0;i<obs.length;i++) obs[i]=Double.parseDouble(fields[i+2]);
            frames.add(new Frame(fields[0],Integer.parseInt(fields[1]),obs,Integer.parseInt(fields[16])));
        }
        return frames;
    }

    static int step(DuelNetwork network, double[] o) {
        return network.step(o[0],o[1],o[2],o[3],o[4],o[5],o[6],o[7],o[8],o[9]!=0,o[10]!=0,
                o[11]<0?Double.POSITIVE_INFINITY:o[11],o[12]<0?Double.POSITIVE_INFINITY:o[12],o[13]);
    }

    static ArrayList<Example> examples(List<Frame> frames, boolean previousOnly) {
        var examples=new ArrayList<Example>();
        String last=""; int prevTick=-2;
        DuelNetwork network=null;
        for (Frame f:frames) {
            if (!last.equals(f.match) || f.tick!=prevTick+1)
                network=new DuelNetwork(DuelNetwork.Family.IMITATION,new float[HumanTiming.SIZE]);
            step(network,f.observation);
            double[] input=network.imitationInputs();
            if (previousOnly) {
                for(int i=0;i<22;i++) if(i<16 || i>19) input[i]=0;
                // Ground is an admissibility mask for the jump likelihood for every baseline.
                input[8]=f.observation[9]!=0?1:-1;
            }
            examples.add(new Example(f.match,input,f.label));
            network.applied(f.label);
            last=f.match;prevTick=f.tick;
        }
        return examples;
    }

    static int[] labels(int a) {
        int key=DuelNetwork.key(a);
        return new int[]{key==3?0:key==0?1:2,DuelNetwork.side(a)+1,
                         DuelNetwork.click(a)?1:0,DuelNetwork.jump(a)?1:0};
    }

    static double[] probabilities(double[] scores) {
        double[] p=new double[O];
        for(int start:new int[]{0,3}) {
            double m=Math.max(scores[start],Math.max(scores[start+1],scores[start+2])), sum=0;
            for(int k=start;k<start+3;k++){p[k]=Math.exp(scores[k]-m);sum+=p[k];}
            for(int k=start;k<start+3;k++)p[k]/=sum;
        }
        p[6]=HumanTiming.sigmoid(scores[6]); p[7]=HumanTiming.sigmoid(scores[7]);
        return p;
    }

    static double[] gradient(Example e,double[] p) {
        int[] y=labels(e.action);double[] g=new double[O];
        for(int k=0;k<6;k++)g[k]=(y[k/3]==k%3?1:0)-p[k];
        g[6]=y[2]-p[6]; g[7]=e.input[8]>0?y[3]-p[7]:0;
        return g;
    }

    static void backward(float[] w,double[] x,double[] hidden,double[] g,double[] result) {
        double[] d=new double[H];
        int index=H*(N+1);
        for(int k=0;k<O;k++){
            result[index++]+=g[k];
            for(int j=0;j<H;j++){d[j]+=w[index]*g[k];result[index++]+=hidden[j]*g[k];}
        }
        index=0;
        for(int j=0;j<H;j++){
            double v=d[j]*Math.pow(1-Math.abs(hidden[j]),2);result[index++]+=v;
            for(double a:x)result[index++]+=a*v;
        }
    }

    static final class Metrics {
        long n;
        final long[] counts=new long[4];
        final double[] loss=new double[4], actual=new double[O],predicted=new double[O];
        final int[][][] confusion=new int[4][3][3];
        final Map<String,double[]> byMatch=new TreeMap<>();
        void add(Example e,double[] p) {
            int[] y=labels(e.action); n++;
            double[] item=byMatch.computeIfAbsent(e.match,k->new double[2]);item[1]++;
            for(int k=0;k<4;k++){
                if(k==3 && e.input[8]<=0)continue;
                double prob;int mode;
                if(k<2){
                    int idx=k*3;
                    prob=p[idx+y[k]]; mode=0;
                    for(int j=0;j<3;j++){
                        actual[idx+j]+=y[k]==j?1:0;predicted[idx+j]+=p[idx+j];
                        if(p[idx+j]>p[idx+mode])mode=j;
                    }
                } else {
                    int idx=k+4;mode=p[idx]>=.5?1:0;
                    prob=y[k]==1?p[idx]:1-p[idx];
                    actual[idx]+=y[k];predicted[idx]+=p[idx];
                }
                confusion[k][y[k]][mode]++;
                double l=-Math.log(Math.max(1e-15,prob));counts[k]++;loss[k]+=l;item[0]+=l;
            }
        }
        double merit(){return byMatch.values().stream().mapToDouble(a->a[0]/a[1]).average().orElseThrow();}
        Map<String,Object> data(){
            return Map.of("heads",n,"eligible_by_head",counts,"cross_entropy_sum",loss,
                "actual_action_counts",actual,"expected_action_counts",predicted,"argmax_confusion",confusion,
                "bout_equal_joint_nll",merit(),"per_bout_loss_sum_and_heads",byMatch);
        }
    }

    static Metrics metrics(List<Example> data,float[] w,double[] constant) {
        var m=new Metrics();double[] h=new double[H],out=new double[O];
        for(var e:data){
            if(constant!=null)m.add(e,constant);
            else{HumanTiming.forward(w,e.input,h,out);m.add(e,probabilities(out));}
        }
        return m;
    }

    static double[] frequencies(List<Example> fit) {
        var m=metrics(fit,null,new double[]{.333,.334,.333,.333,.334,.333,.5,.5});
        double[] p=m.actual.clone();
        for(int j=0;j<O;j++){
            int k=j<6?j/3:j-4;
            p[j]=(p[j]+1)/(m.counts[k]+(k<2?3.:2.));
        }
        return p;
    }

    static void fit(Path input,Path output) throws Exception {
        Files.createDirectories(output);
        var train=load(input.resolve("fit.txt"));var valid=load(input.resolve("select.txt"));
        var info=new LinkedHashMap<String,Object>();var snapshots=new ArrayList<Object>();
        double[] fixed=frequencies(examples(train,false));
        info.put("unconditional_fit_frequencies",fixed);
        info.put("select_unconditional",metrics(examples(valid,false),null,fixed).data());
        for(boolean previousOnly:new boolean[]{true,false}){
            var fit=examples(train,previousOnly);var select=examples(valid,previousOnly);
            String kind=previousOnly?"history":"clone";double best=Double.POSITIVE_INFINITY;
            for(int seed:new int[]{16013,76001,488491}){
                var rng=new Random(seed);float[] w=new float[HumanTiming.SIZE];
                for(int i=0;i<HumanTiming.PARAMETERS;i++)w[i]=(float)(rng.nextGaussian()*.18);
                var opt=new PPO.Adam(w.length);
                for(int epoch=1;epoch<=64;epoch++){
                    Collections.shuffle(fit,rng);
                    for(int start=0;start<fit.size();start+=96){
                        int end=Math.min(fit.size(),start+96);
                        double[] g=new double[w.length],h=new double[H],out=new double[O];
                        for(var e:fit.subList(start,end)){
                            HumanTiming.forward(w,e.input,h,out);
                            backward(w,e.input,h,gradient(e,probabilities(out)),g);
                        }
                        opt.update(w,g,end-start,.0022/(1+epoch/55.));
                    }
                    if(epoch%4!=0)continue;
                    Metrics test=metrics(select,w,null);
                    snapshots.add(List.of(kind,seed,epoch,test.merit()));
                    if(test.merit()<best){
                        best=test.merit();w[HumanTiming.PARAMETERS]=130019;
                        Files.writeString(output.resolve(kind+".net"),DuelNetwork.encode(DuelNetwork.Family.IMITATION,w)+"\n");
                        info.put("selected_"+kind,List.of(seed,epoch));info.put("select_"+kind,test.data());
                    }
                }
            }
        }
        info.put("selection_trace",snapshots);
        Files.writeString(output.resolve("selection.json"),Json.encode(info)+"\n",StandardOpenOption.CREATE_NEW);
    }

    static Map<String,Object> selfFed(List<Frame> frames,float[] weights) {
        Map<String,ArrayList<Integer>> truth=new TreeMap<>(),sample=new TreeMap<>();
        String last="";int prev=-2;DuelNetwork n=null;
        for(var f:frames){
            if(!f.match.equals(last)||f.tick!=prev+1)n=new DuelNetwork(DuelNetwork.Family.IMITATION,weights);
            int a=step(n,f.observation);n.applied(a);
            truth.computeIfAbsent(f.match,k->new ArrayList<>()).add(f.label);
            sample.computeIfAbsent(f.match,k->new ArrayList<>()).add(a);
            prev=f.tick;last=f.match;
        }
        return Map.of("observed_actions",truth,"sampled_actions",sample);
    }

    static void evaluate(Path input,Path models,Path out) throws Exception {
        var frames=load(input.resolve("later.txt"));Map<String,Object> evidence=new TreeMap<>();
        var fit=examples(load(input.resolve("fit.txt")),false);
        evidence.put("unconditional",metrics(examples(frames,false),null,frequencies(fit)).data());
        for(String kind:List.of("history","clone")){
            var model=Arena.Model.decode(Files.readString(models.resolve(kind+".net")).trim());
            evidence.put(kind,metrics(examples(frames,kind.equals("history")),model.weights(),null).data());
            if(kind.equals("clone"))evidence.put("recursively_sampled_on_recorded_observations",selfFed(frames,model.weights()));
        }
        Files.writeString(out,Json.encode(evidence)+"\n",StandardOpenOption.CREATE_NEW);
    }

    public static void main(String[] args)throws Exception {
        if(args[0].equals("fit"))fit(Path.of(args[1]),Path.of(args[2]));
        else if(args[0].equals("evaluate"))evaluate(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]));
        else throw new IllegalArgumentException("fit or evaluate required");
    }
}
