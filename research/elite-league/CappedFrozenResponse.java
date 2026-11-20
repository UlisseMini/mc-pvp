import java.nio.file.*;
import java.io.*;
import java.util.*;

/** The same frozen opponent across two physical click intervals on both clients. */
final class CappedFrozenResponse {
    static final Path D=FrozenSumoResponse.D.resolve("seven");
    static long interval(int cps){if(cps!=7 && cps!=20)throw new IllegalArgumentException("Unexpected cap");var props=new Properties();props.setProperty("click_cps",Integer.toString(cps));return lab.ClickRate.intervalNanos(props);}
    static void cap(DuelEnv e,int n){e.initial.timing.clickIntervalNanos=interval(n);}
    static List<DuelEnv> clocks(Path list,int n)throws IOException{
        var result=new ArrayList<DuelEnv>();for(var p:Files.readAllLines(list)){var c=InputStudy.clock(Path.of(p));cap(c,n);result.add(c);}return result;
    }
    static void verify(DuelEnv e,Arena.Fighter f,int cps,boolean seat)throws Exception{
        var sc=PingLeague.scenario(e,PingLeague.WT,seat,31,109);var env=sc.env();
        var game=env.new Game(seat,f.create(31,109,0,true,DuelEnv.regulationDiameter(),10),PingLeague.WT.create(109,31,0,true,DuelEnv.regulationDiameter(),10));
        if(game.clock.timing.clickIntervalNanos!=interval(cps))throw new AssertionError("Cap lost in fork");
        long[] last={-1,-1},small={Long.MAX_VALUE,Long.MAX_VALUE};int[] count={0,0};
        game.clock.setClickAudit(ev->{
            if(!ev.action().equals("ADMIT"))return;int idx=ev.fromA()?0:1;long now=ev.admittedNanos();
            if(last[idx]>=0)small[idx]=Math.min(small[idx],now-last[idx]);last[idx]=now;count[idx]++;
        });
        game.finish();
        for(int i=0;i<2;i++)if(count[i]<2||small[i]<interval(cps))throw new AssertionError("Physical cap proof failed "+Arrays.toString(small));
        if(f.equals(PingLeague.WT))System.out.println(cps+" CPS "+e.clockId+" seat="+seat+" presses="+Arrays.toString(count)+" minimum_ns="+Arrays.toString(small));
    }
    static void select(String[] args)throws Exception{
        var ds=clocks(FrozenSumoResponse.F.resolve("selection-clocks.txt"),7);var panel=FrozenSumoResponse.panel(ds);var wt=FrozenSumoResponse.evaluate(PingLeague.WT,panel);
        var temp=D.resolve("selection.tmp");
        try(var out=new PrintWriter(Files.newBufferedWriter(temp))){
            out.println("id,merit,mean,seat_a,seat_b,wtap");
            for(int i=1;i<args.length;i++){String id=args[i];var f=InputStudy.fighter(id,FrozenSumoResponse.F.resolve("fits/"+id+".net"));
                var s=FrozenSumoResponse.evaluate(f,panel);out.printf(Locale.ROOT,"%s,%.9f,%.9f,%.9f,%.9f,%.9f%n",id,s.merit(),s.mean(),s.seatA(),s.seatB(),wt.mean());}
            if(out.checkError())throw new IOException("Incomplete selection table");
        }
        Files.move(temp,D.resolve("selection.csv"),StandardCopyOption.ATOMIC_MOVE);
    }
    static void eval(Path dir,int n)throws Exception{
        InputStudy.field();var ds=clocks(dir.resolve("clocks.txt"),n);
        var panel=InputStudy.panel(ds,true);var tmp=dir.resolve("transfer-"+n+".tmp");
        try(var out=new PrintWriter(Files.newBufferedWriter(tmp))){
            out.println("arm,foe,calendar,seat,own_rtt,opponent_rtt,reward");
            for(var line:Files.readAllLines(dir.resolve("entrants.tsv"))){var v=line.split("\t");
                var f=v.length==1?Arena.Fighter.named(v[0]):InputStudy.fighter(v[0],Path.of(v[1]));
                PingLeague.evaluate(f,panel,out);
            }
            if(out.checkError())throw new IOException("Incomplete capped simulation table");
        }
        Files.move(tmp,dir.resolve("transfer-"+n+".csv"),StandardCopyOption.ATOMIC_MOVE);
    }
    public static void main(String[] args)throws Exception{
        try{
            FrozenSumoResponse.load();
            for(var c:FrozenSumoResponse.train)cap(c,7);for(var c:FrozenSumoResponse.dev)cap(c,7);
            switch(args[0]){
                case "check" -> {for(int cps:new int[]{7,20})for(boolean seat:new boolean[]{true,false}){
                    var e=FrozenSumoResponse.train.get(0);cap(e,cps);verify(e,PingLeague.WT,cps,seat);verify(e,FrozenSumoResponse.target,cps,seat);}}
                case "select" -> select(args);
                case "eval" -> eval(Path.of(args[1]),Integer.parseInt(args[2]));
                case "ppo","evolve" -> {var start=InputStudy.fighter("start",Path.of(args[2]));int seed=Integer.parseInt(args[3]);
                    if(args[0].equals("ppo"))FrozenSumoResponse.ppo(args[1],start,seed);else FrozenSumoResponse.evolve(args[1],start,seed);}
                default -> throw new IllegalArgumentException(args[0]);
            }
        }finally{PingLeague.pool.shutdown();}
    }
}
