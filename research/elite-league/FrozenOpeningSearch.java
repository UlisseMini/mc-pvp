import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.PairedExperts;

/** Evolve an opening and continuation together. Both experts receive only their own observations. */
final class FrozenOpeningSearch {
    static final int START=PairedExperts.HEADER+PairedExperts.GATE, BLOCK=PairedExperts.BLOCK;
    static final int INPUTS=DuelNetwork.INPUTS, LINEAR=4, MLP=0;
    static final List<float[]> parents=new ArrayList<>();
    static Random rng;
    static int mode;
    static double noise(double scale){return rng.nextGaussian()*scale;}
    static int headStart(float[] w,int part,int head){
        boolean linear=(int)w[1+part]==LINEAR;
        return START+part*BLOCK+(linear?head*(INPUTS+1):8*(INPUTS+1)+head*9);
    }
    static Arena.Fighter initial(){
        float[] w=new float[PairedExperts.SIZE];w[0]=mode;w[1]=rng.nextBoolean()?MLP:LINEAR;w[2]=MLP;
        w[3]=rng.nextInt(3);w[4]=rng.nextInt(21);w[6]=1+rng.nextInt(6);w[7]=(float)rng.nextDouble();
        for(int i=PairedExperts.HEADER;i<START;i++)w[i]=(float)noise(.55);
        for(int part=0;part<2;part++){
            int base=START+part*BLOCK;
            if((int)w[1+part]==MLP){var v=parents.get(rng.nextInt(parents.size()));System.arraycopy(v,0,w,base,v.length);}
            else{
                for(int i=0;i<5*(INPUTS+1);i++)w[base+i]=(float)noise(.45);
                w[headStart(w,part,0)]+=.9f;w[headStart(w,part,1)]+=.9f;
            }
        }
        return actor(w);
    }
    static Arena.Fighter actor(float[] w){return new Arena.Fighter("response",new Arena.Model(DuelNetwork.Family.PAIR,w));}
    static Arena.Fighter child(List<Arena.Fighter> elite){
        float[] w=elite.get(rng.nextInt(elite.size())).model().weights().clone();
        int part=rng.nextInt(2),base=START+part*BLOCK;
        var donor=elite.get(rng.nextInt(elite.size())).model().weights();
        double sigma=new double[]{.08,.22,.60,1.25}[rng.nextInt(4)];
        switch(rng.nextInt(6)){
            case 0 -> {w[1+part]=donor[1+part];System.arraycopy(donor,base,w,base,BLOCK);}
            case 1 -> {
                if(mode==1){w[3]=rng.nextInt(3);w[4]=(float)Math.min(60,Math.max(0,w[4]+noise(6)));}
                else {for(int i=PairedExperts.HEADER;i<START;i++)if(rng.nextDouble()<.35)w[i]+=noise(sigma);
                    w[6]=(float)Math.max(1,Math.min(20,w[6]+noise(3)));w[7]=(float)Math.max(0,Math.min(2,w[7]+noise(.3)));}
            }
            case 2,3 -> {
                int head=rng.nextBoolean()?3:rng.nextInt(5),at=headStart(w,part,head);
                int size=(int)w[1+part]==LINEAR?INPUTS+1:9;
                for(int i=0;i<size;i++)if(i==0||rng.nextDouble()<.60)w[at+i]+=noise(sigma);
            }
            case 4 -> {
                int size=DuelNetwork.size(PairedExperts.EXPERTS[(int)w[1+part]]);
                for(int i=0;i<size;i++)if(rng.nextDouble()<.14)w[base+i]+=noise(sigma);
            }
            case 5 -> {
                // An exploration proposal, never a fixed jump schedule. Features come from own POV.
                int[] cues={0,1,2,4,5,7,8,10,11,12,14,15,20,21};
                int at=headStart(w,part,3);
                if((int)w[1+part]==LINEAR){
                    Arrays.fill(w,at,at+INPUTS+1,0);
                    w[at]=(float)noise(.85);
                    for(int i=0;i<3;i++)w[at+1+cues[rng.nextInt(cues.length)]]+=(float)noise(1.5);
                }else{
                    int unit=rng.nextInt(8),u=base+unit*(INPUTS+1);
                    w[at+1+unit]+=noise(2);w[u]+=noise(.5);
                    for(int i=0;i<3;i++)w[u+1+cues[rng.nextInt(cues.length)]]+=noise(1.3);
                }
            }
        }
        return actor(w);
    }
    static void fit(String id)throws Exception{
        var elite=new ArrayList<Arena.Fighter>();for(int i=0;i<8;i++)elite.add(initial());
        try(var best=new FrozenSumoResponse.Best(id,FrozenSumoResponse.panel(FrozenSumoResponse.dev))){
            for(int gen=1;gen<=160;gen++){
                var games=new ArrayList<Arena.Scenario>();
                for(int i=0;i<16;i++){var env=FrozenSumoResponse.train.get(rng.nextInt(FrozenSumoResponse.train.size()));
                    int a=rng.nextInt(151),b=rng.nextInt(151);
                    for(boolean s:new boolean[]{true,false})games.add(PingLeague.scenario(env,FrozenSumoResponse.target,s,a,b));}
                var group=new ArrayList<>(elite);
                while(group.size()<32)group.add(rng.nextDouble()<.10?initial():child(elite));
                var jobs=new ArrayList<Callable<Double>>();
                for(var f:group)jobs.add(()->{double total=0;for(var s:games)total+=s.game(f);return total/games.size();});
                var ranks=new ArrayList<Map.Entry<Arena.Fighter,Double>>();int i=0;
                for(var f:PingLeague.pool.invokeAll(jobs))ranks.add(Map.entry(group.get(i++),f.get()));
                ranks.sort(Map.Entry.<Arena.Fighter,Double>comparingByValue().reversed());
                elite.clear();var distinct=new HashSet<String>();
                for(var r:ranks)if(distinct.add(r.getKey().model().encode())){elite.add(r.getKey());if(elite.size()==6)break;}
                if(gen==1||gen%8==0)best.consider(gen,elite.get(0));
            }
            best.finish();System.out.println(id+" development "+best.high);
        }
    }
    public static void main(String[] args)throws Exception{
        try{
            FrozenSumoResponse.load();
            if(args[0].equals("select")||args[0].equals("select-dir")){
                int start=args[0].equals("select-dir")?2:1;
                var folder=start==2?Path.of(args[1]):FrozenSumoResponse.D.resolve("opening");var clocks=new ArrayList<DuelEnv>();
                for(var p:Files.readAllLines(FrozenSumoResponse.F.resolve("selection-clocks.txt")))clocks.add(InputStudy.clock(Path.of(p)));
                var panel=FrozenSumoResponse.panel(clocks);var wt=FrozenSumoResponse.evaluate(PingLeague.WT,panel);
                try(var out=new PrintWriter(Files.newBufferedWriter(folder.resolve("selection.tmp")))){
                    out.println("id,merit,mean,seat_a,seat_b,wtap");
                    for(int k=start;k<args.length;k++){String id=args[k];var f=InputStudy.fighter(id,FrozenSumoResponse.F.resolve("fits/"+id+".net"));var s=FrozenSumoResponse.evaluate(f,panel);
                        out.printf(Locale.ROOT,"%s,%.9f,%.9f,%.9f,%.9f,%.9f%n",id,s.merit(),s.mean(),s.seatA(),s.seatB(),wt.mean());}
                    if(out.checkError())throw new IOException("Selection log incomplete");
                }
                Files.move(folder.resolve("selection.tmp"),folder.resolve("selection.csv"),StandardCopyOption.ATOMIC_MOVE);
            }else{
                mode=Integer.parseInt(args[1]);rng=new Random(Integer.parseInt(args[2]));
                parents.add(FrozenSumoResponse.target.model().weights());
                for(int i=3;i<args.length;i++){var f=InputStudy.fighter("warm",Path.of(args[i]));
                    if(f.model().family()!=DuelNetwork.Family.MLP)throw new IllegalArgumentException("Expected an eight-unit base MLP");parents.add(f.model().weights());}
                fit(args[0]);
            }
        }finally{PingLeague.pool.shutdown();}
    }
}
