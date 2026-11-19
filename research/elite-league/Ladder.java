import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.CombatTactics;
import lab.DuelNetwork;
import lab.Json;

/** One restartable season of simultaneous scripted and neural responses to a frozen, diverse field. */
final class Ladder {
    static final double[] BASE={1,1,2.85,450,55,.05,2,.56,125,.42,17,.9,1,.52,.7,2};
    static final double[] LOW={0,-1,2,325,5,.005,0,.18,15,0,5,.2,.15,.32,.12,0};
    static final double[] HIGH={11,1,3.9,590,175,.29,4,.95,265,1.4,26,2.2,2.0,.88,2.4,4};
    static final int[] FEATURES={0,2,1,10,11,14};
    static final String[] TRACKS={"rule","table","search","neural"};
    static class HeadBank {
        final List<double[]> examples=new ArrayList<>();
        final List<double[]> difficult=new ArrayList<>();
        HeadBank(Path frames,Path feedback)throws Exception{
            for(var e:HumanFit.examples(HumanFit.load(frames),false))add(examples,e.input());
            if(Files.exists(feedback)){
                for(String l:Files.readAllLines(feedback)){
                    double[] x=Arrays.stream(l.split(" ")).mapToDouble(Double::parseDouble).toArray();
                    if(x.length!=CombatTactics.DIMENSIONS)throw new IOException("Invalid pressure example");
                    difficult.add(x);
                }
            }
        }
        static void add(List<double[]> dest,double[] full){
            double[] state=new double[FEATURES.length];for(int j=0;j<state.length;j++)state[j]=full[FEATURES[j]];
            dest.add(state);
        }
        double[] draw(Random r){var pool=!difficult.isEmpty()&&r.nextDouble()<.7?difficult:examples;return pool.get(r.nextInt(pool.size()));}
    }
    static Arena.Model explicit(int kind,Random rng,HeadBank bank){
        float[] w=new float[CombatTactics.SIZE];for(int i=0;i<BASE.length;i++)w[i]=(float)BASE[i];w[0]=kind;
        int n=CombatTactics.PARAMETERS;
        for(int i=0;i<CombatTactics.PROTOTYPES;i++){
            for(double x:bank.draw(rng))w[n++]=(float)x;
            w[n++]=rng.nextInt(CombatTactics.WORDS);
        }
        return new Arena.Model(DuelNetwork.Family.TACTIC,w);
    }
    static Arena.Model change(Arena.Model parent,String track,Random rng,HeadBank bank) {
        if(track.equals("neural"))
            return Evolve.mutate(parent,rng,new double[]{.07,.18,.35,.65,1.0}[rng.nextInt(5)]);
        float[] w=parent.weights().clone();
        double scale=new double[]{.04,.11,.22}[rng.nextInt(3)];
        for(int i=1;i<BASE.length;i++)if(rng.nextDouble()<.32)
            w[i]=(float)Math.max(LOW[i],Math.min(HIGH[i],w[i]+rng.nextGaussian()*scale*(HIGH[i]-LOW[i])));
        if(track.equals("rule")&&rng.nextDouble()<.18)w[0]=rng.nextInt(CombatTactics.WORDS);
        if(track.equals("table")){
            double[] focus=bank.draw(rng);int nearest=0;
            double best=Double.POSITIVE_INFINITY;
            for(int i=0;i<CombatTactics.PROTOTYPES;i++){
                double dist=0;for(int j=0;j<focus.length;j++)dist+=Math.pow(focus[j]-w[BASE.length+i*7+j],2);
                if(dist<best){best=dist;nearest=i;}
            }
            int node=rng.nextDouble()<.65?nearest:rng.nextInt(CombatTactics.PROTOTYPES);
            int start=BASE.length+node*7;
            if(rng.nextDouble()<.60)for(int j=0;j<focus.length;j++)w[start+j]=(float)focus[j];
            w[start+6]=rng.nextInt(CombatTactics.WORDS);
        }
        return new Arena.Model(parent.family(),w);
    }
    record Opp(Arena.Fighter f,String group,double priority){}
    static List<Opp> readField(Path path)throws Exception{
        var out=new ArrayList<Opp>();
        for(String l:Files.readAllLines(path)){
            var v=l.split(" ");var f=v.length==4?RobustField.load(v[0],Path.of(v[3])):Arena.Fighter.named(v[0]);
            out.add(new Opp(f,v[1],Double.parseDouble(v[2])));
        }
        return out;
    }
    static List<Population.Task> cases(RobustField domains,List<Opp> field,Random rng,int perGroup){
        var groups=new TreeMap<String,List<Opp>>();for(var entry:field)groups.computeIfAbsent(entry.group,k->new ArrayList<>()).add(entry);
        var cases=new ArrayList<Population.Task>();int g=0;
        for(var members:groups.values()){
            double total=members.stream().mapToDouble(o->o.priority).sum();
            for(int rep=0;rep<perGroup;rep++){
                double key=rng.nextDouble()*total;Opp chosen=members.get(members.size()-1);
                for(var o:members){key-=o.priority;if(key<=0){chosen=o;break;}}
                var variants=rng.nextDouble()<.24?domains.shifted:domains.standard;
                var e=variants.get(rng.nextInt(variants.size())).env();
                for(boolean seat:new boolean[]{true,false})cases.add(new Population.Task(new Arena.Scenario(e,chosen.f,seat),g));
            }
            g++;
        }
        return cases;
    }
    static Arena.Fighter fighter(String name,Arena.Model m){return new Arena.Fighter(name,m);}
    static void train(Path out,int season,int generations,int population,Path fieldPath,Path warmPath,
                      Path frames,Path feedback,List<Path> tapes)throws Exception{
        var rng=new Random(719033L+season*101113);var pool=Executors.newFixedThreadPool(3);
        var base=Path.of("research/elite-league");
        var domains=new RobustField(tapes,base.resolve("frozen"),base.resolve("challenge-frozen"));
        var field=readField(fieldPath);int ng=(int)field.stream().map(Opp::group).distinct().count();
        var bank=new HeadBank(frames,feedback);var warm=Matches.roster(warmPath);var elites=new LinkedHashMap<String,List<Arena.Fighter>>();
        Files.createDirectories(out);
        for(String track:TRACKS){
            var seeds=new ArrayList<Arena.Fighter>();for(var f:warm)if(f.name().startsWith(track+"-"))seeds.add(f);
            if(!track.equals("neural")){
                int count=track.equals("rule")?CombatTactics.WORDS:1;
                for(int i=0;i<count;i++)seeds.add(fighter(track+"-origin"+i,explicit(track.equals("rule")?i:track.equals("table")?CombatTactics.TABLE:CombatTactics.SEARCH,rng,bank)));
            }
            if(seeds.isEmpty())throw new IllegalArgumentException("No initial "+track);
            elites.put(track,seeds);
        }
        long bouts=0;
        try(var log=Files.newBufferedWriter(out.resolve("training.jsonl"),StandardOpenOption.CREATE_NEW)){
            for(int gen=0;gen<generations;gen++){
                var fixtures=cases(domains,field,rng,2);
                var group=new ArrayList<Arena.Fighter>();var names=new HashMap<Arena.Fighter,String>();
                for(String track:TRACKS){
                    var parents=elites.get(track);var proposed=new ArrayList<>(parents);
                    while(proposed.size()<population){
                        var parent=parents.get(rng.nextInt(parents.size())).model();
                        if(!track.equals("neural")&&rng.nextDouble()<.04)
                            parent=explicit(track.equals("rule")?rng.nextInt(CombatTactics.WORDS):track.equals("table")?CombatTactics.TABLE:CombatTactics.SEARCH,rng,bank);
                        proposed.add(fighter(track+"-g"+gen+"-"+proposed.size(),change(parent,track,rng,bank)));
                    }
                    for(var f:proposed){names.put(f,track);group.add(f);}
                }
                group.addAll(List.of(Evolve.WT,Evolve.HOLD));
                var ranks=Population.rank(group,fixtures,ng,pool);
                for(String track:TRACKS){
                    var winners=new ArrayList<Arena.Fighter>();
                    for(var row:ranks)if(track.equals(names.get(row.fighter()))&&winners.size()<population/6)winners.add(row.fighter());
                    elites.put(track,winners);
                    if(gen%8==0 || gen==generations-1)
                        Files.writeString(out.resolve(track+String.format("-g%03d.net",gen)),winners.get(0).model().encode()+"\n",StandardOpenOption.CREATE_NEW);
                }
                bouts+=(long)fixtures.size()*group.size();
                log.write(Json.encode(Map.of("generation",gen,"bouts",bouts,"settings_per_fighter",fixtures.size(),
                         "field_scores",ranks.stream().map(z->List.of(z.fighter().name(),z.average(),z.worst())).toList())));
                log.newLine();log.flush();
                if(gen%16==0)System.out.printf("season %d generation %d %,d games%n",season,gen,bouts);
            }
        }finally{pool.shutdown();}
    }
    /** Offline sampling of actual first-person states in bouts lost to a specific opponent.
     * Terminal outcome is used to prioritize training examples, never fed to the playing policy. */
    static class Trace extends DuelEnv.Player{
        Arena.Neural policy;final List<double[]> heads=new ArrayList<>();
        Trace(Arena.Model m,int ping){super("trace",0,ping,0);policy=new Arena.Neural(m,ping);}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var c=policy.input(o);HeadBank.add(heads,policy.net.features());return c;
        }
    }
    static void misses(Path arms,Path field,Path out,List<Path> tapes)throws Exception{
        var rng=new Random(10277);var fighters=Matches.roster(arms);var opponents=readField(field);
        try(var text=Files.newBufferedWriter(out,StandardOpenOption.CREATE_NEW)){
            for(var e:Arena.envs(tapes,"mmc",true))for(var f:fighters)for(var opp:opponents)for(boolean seat:new boolean[]{true,false}){
                var p=new Trace(f.model(),e.rtt);var g=e.new Game(seat,p,opp.f.create(e.rtt,0));g.finish();
                if(g.reward()>=1 || p.heads.isEmpty())continue;
                // Sample the opening, the body of the exchange, and the final six heads.
                int n=p.heads.size();for(int i:new int[]{Math.min(8,n-1),rng.nextInt(n),Math.max(0,n-1-rng.nextInt(Math.min(n,6)))}){
                    var x=p.heads.get(i);for(int j=0;j<x.length;j++){if(j>0)text.write(' ');text.write(Double.toString(x[j]));}text.newLine();
                }
            }
        }
    }
    public static void main(String[] a)throws Exception{
        if(a[0].equals("misses")){misses(Path.of(a[1]),Path.of(a[2]),Path.of(a[3]),Arrays.stream(a,4,a.length).map(Path::of).toList());return;}
        train(Path.of(a[1]),Integer.parseInt(a[2]),Integer.parseInt(a[3]),Integer.parseInt(a[4]),Path.of(a[5]),Path.of(a[6]),
              Path.of(a[7]),Path.of(a[8]),Arrays.stream(a,9,a.length).map(Path::of).toList());
    }
}
