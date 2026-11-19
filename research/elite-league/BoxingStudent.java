import java.nio.file.*;
import java.io.*;
import java.util.*;
import lab.DuelNetwork;

/** Fit the ordinary policy scores to executed teacher keys, with equal opening sampling. */
final class BoxingStudent {
    record Example(int word,int action,double[] x){}
    record Group(List<Example> all,List<Example> interventions){}
    static double[] target(int a){
        int key=DuelNetwork.key(a);
        return new double[]{key==3?-1.1:key==0?-.3:.85,key==2?.85:-.85,
                       DuelNetwork.click(a)?.85:-.85,DuelNetwork.jump(a)?.85:-.85,.85*DuelNetwork.side(a)};
    }
    static List<Group> load(Path p)throws Exception{
        Map<String,Group> by=new LinkedHashMap<>();
        try(var src=Files.newBufferedReader(p)){
            String line;
            while((line=src.readLine())!=null){
                var fields=line.split(" ");double[] x=new double[DuelNetwork.INPUTS];
                for(int i=0;i<x.length;i++)x[i]=Double.parseDouble(fields[i+3]);
                var row=new Example(Integer.parseInt(fields[1]),Integer.parseInt(fields[2]),x);
                var group=by.computeIfAbsent(fields[0],s->new Group(new ArrayList<>(),new ArrayList<>()));
                group.all.add(row);if(row.word!=0)group.interventions.add(row);
            }
        }
        return new ArrayList<>(by.values());
    }
    public static void main(String[] args)throws Exception{
        var groups=load(Path.of(args[0]));Path out=Path.of(args[1]);Files.createDirectories(out);
        var warm=Arena.Model.decode(Files.readString(Path.of(args[2])).trim());
        if(warm.family()!=DuelNetwork.Family.MLP)throw new IllegalArgumentException("Expected MLP");
        for(int seed:new int[]{33299,64019})for(double rare:new double[]{.2,.5,.8}){
            var rng=new Random(seed);float[] w=warm.weights().clone();
            var adam=new PPO.Adam(w.length);String tag=seed+"-"+Math.round(rare*10);
            for(int epoch=0;epoch<100;epoch++){
                for(int batch=0;batch<12;batch++){
                    double[] gradient=new double[w.length];
                    for(int i=0;i<64;i++){
                        var g=groups.get(rng.nextInt(groups.size()));
                        var samples=!g.interventions.isEmpty()&&rng.nextDouble()<rare?g.interventions:g.all;
                        var row=samples.get(rng.nextInt(samples.size()));double[] h=new double[PPO.H],o=new double[PPO.OUT];
                        PPO.forward(w,row.x,h,o);double[] delta=target(row.action);
                        for(int j=0;j<delta.length;j++)delta[j]=Math.max(-1,Math.min(1,delta[j]-o[j]));
                        if(DuelNetwork.key(row.action)==0||DuelNetwork.key(row.action)==3)delta[1]=0;
                        PPO.backward(w,row.x,h,delta,gradient,1);
                    }
                    adam.update(w,gradient,64,.004);
                }
                if((epoch+1)%5==0)Files.writeString(out.resolve(String.format("bc-%s-%03d.net",tag,epoch+1)),DuelNetwork.encode(DuelNetwork.Family.MLP,w)+"\n",StandardOpenOption.CREATE_NEW);
            }
        }
    }
}
