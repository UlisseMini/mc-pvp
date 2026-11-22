import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;

/** Execute full, independently seeded regulation duels. Only own-POV features leave the rollout. */
final class LeagueWorker {
    static final Map<String,DuelEnv> clocks=new HashMap<>();
    static final Map<String,Arena.Fighter> opponents=new HashMap<>();
    static final int STRIDE=32;
    static final int CPS=Integer.getInteger("league.cps",7);
    static final long INTERVAL=CappedFrozenResponse.interval(CPS);
    static Arena.Fighter fighter(String name)throws IOException{
        var known=opponents.get(name);
        if(known!=null)return known;
        if(name.endsWith(".net"))return InputStudy.fighter(name,Path.of(name));
        throw new IOException("Unknown fighter "+name);
    }
    static void load()throws IOException{
        Path d=Path.of("research/elite-league");
        for(String row:Files.readAllLines(d.resolve("sumo-inputs/archive-field.tsv"))){
            String[] f=row.split("\t");
            if(f.length>1){
                String actual;
                try{actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(f[1]))));}
                catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
                if(!actual.equals(f[2]))throw new IOException("Unfrozen weights "+f[0]);
            }
            opponents.put(f[0],f.length==1?Arena.Fighter.named(f[0]):InputStudy.fighter(f[0],Path.of(f[1])));
        }
    }
    static DuelEnv clock(String path)throws IOException{
        if(!clocks.containsKey(path)){
            String map=System.getProperty("league.map");
            DuelEnv e=map==null?InputStudy.clock(Path.of(path)):RecordedMapClock.open(Path.of(path),map);CappedFrozenResponse.cap(e,CPS);
            if((map==null&&(e.initial.config.sumoDiameter!=13||DuelEnv.regulationGap()!=8))||e.limit!=120000)throw new IOException("Unexpected regulation geometry or duration");
            clocks.put(path,e);
        }
        return clocks.get(path);
    }
    static DuelEnv.Player player(Arena.Fighter fighter, int own,int other,DuelEnv e,long seed){
        var cfg=e.initial.config;
        var disk=cfg.platform.disk();
        lab.RecordedSumoMap map=disk!=null && disk.terrain() instanceof lab.RecordedCollisions rc ? rc.map : null;
        double intervalMs=e.initial.timing.clickIntervalNanos/1e6;
        var p=fighter.create(own,other,0,cfg.mmc,cfg.sumoDiameter,cfg.hitTicks,map,intervalMs);
        if(p instanceof Arena.Neural n && n.net.league()!=null)n.net.league().seed(seed);
        return p;
    }
    static final class Collector extends Arena.Neural {
        float[] rows=new float[STRIDE*384];
        int length, headIndex;
        final int maskHeads;
        Collector(Arena.Model model,int own,int other,long seed,boolean maskRtt){
            super(model,own,other);
            net.league().seed(seed);
            if(maskRtt){
                long z=seed+0x9e3779b97f4a7c15L;
                z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;z=(z^(z>>>27))*0x94d049bb133111ebL;z^=z>>>31;
                double u=(z>>>11)*0x1.0p-53;
                long z2=z+0x9e3779b97f4a7c15L;
                z2=(z2^(z2>>>30))*0xbf58476d1ce4e5b9L;z2=(z2^(z2>>>27))*0x94d049bb133111ebL;z2^=z2>>>31;
                // Mirror live DuelHudPing startup delay (50% opening mask, 10% full-bout unknown, 40% immediate).
                maskHeads=u<0.10?Integer.MAX_VALUE:u<0.60?(1+Math.floorMod(z2,12)):0;
            }else{
                maskHeads=0;
            }
        }
        @Override protected double effectiveOpponentRtt(PeriodicComboClock.Observation o){
            return headIndex<maskHeads?Double.NaN:opponentRtt;
        }
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var control=super.input(o);headIndex++;if(length+STRIDE>rows.length)rows=Arrays.copyOf(rows,rows.length*2);
            for(double x:net.features())rows[length++]=(float)x;
            rows[length++]=DuelNetwork.pack(control.key(),(int)Math.signum(control.strafe()),control.click(),control.jump());
            for(double v:net.scores())rows[length++]=(float)v;
            rows[length++]=(float)net.league().value();
            return control;
        }
    }
    record Trial(Arena.Fighter actor,Arena.Fighter foe,DuelEnv base,boolean seat,int own,int other,long aSeed,long bSeed){}
    record Result(double outcome,double time,int heads,float[] rows){}
    static Result play(Trial t,boolean collect){return play(t,collect?"collect":"eval");}
    static Result play(Trial t,String mode){
        boolean collect=mode.equals("collect")||mode.equals("train");
        boolean maskRtt=mode.equals("train");
        var e=new DuelEnv(t.base,t.seat?t.own:t.other,t.seat?t.other:t.own);
        var a=collect?new Collector(t.actor.model(),t.own,t.other,t.aSeed,maskRtt):player(t.actor,t.own,t.other,e,t.aSeed);
        var b=player(t.foe,t.other,t.own,e,t.bSeed);
        var game=e.new Game(t.seat,a,b);
        long[] last={-INTERVAL,-INTERVAL};
        game.clock.setClickAudit(ev->{if(ev.action().equals("ADMIT")){
            int i=ev.fromA()?0:1;
            if(ev.admittedNanos()-last[i]<INTERVAL)throw new IllegalStateException("Physical cap violated: "+CPS);
            last[i]=ev.admittedNanos();
        }});
        game.finish();
        if(a instanceof Collector c)return new Result(game.reward(),game.clock.time(),c.length/STRIDE,c.rows);
        return new Result(game.reward(),game.clock.time(),0,null);
    }
    static void batch(Path schedule,Path out,String mode,ExecutorService pool)throws Exception{
        var jobs=new ArrayList<Callable<Result>>();
        // Per-batch cache: the current on-policy weights can change between requests.
        var field=new HashMap<String,Arena.Fighter>();
        for(String row:Files.readAllLines(schedule)){
            String[] s=row.split("\t");
            for(int i=0;i<2;i++)if(!field.containsKey(s[i]))field.put(s[i],fighter(s[i]));
            var t=new Trial(field.get(s[0]),field.get(s[1]),clock(s[2]),s[3].equals("a"),
                    Integer.parseInt(s[4]),Integer.parseInt(s[5]),Long.parseLong(s[6]),Long.parseLong(s[7]));
            jobs.add(()->play(t,mode));
        }
        var work=pool.invokeAll(jobs);
        try(var d=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))){
            d.writeInt(work.size());
            for(var f:work){
                var r=f.get();d.writeFloat((float)r.outcome);d.writeFloat((float)r.time);d.writeInt(r.heads);
                for(int i=0;i<r.heads*STRIDE;i++)d.writeFloat(r.rows[i]);
            }
        }
    }
    public static void main(String[] args)throws Exception{
        load();var pool=Executors.newFixedThreadPool(Integer.parseInt(args[0]));
        try(var reader=new BufferedReader(new InputStreamReader(System.in))){
            String line;while((line=reader.readLine())!=null){
                String[] s=line.split("\t");batch(Path.of(s[1]),Path.of(s[2]),s[0],pool);
                System.out.println("OK");System.out.flush();
            }
        }finally{pool.shutdown();}
    }
}
