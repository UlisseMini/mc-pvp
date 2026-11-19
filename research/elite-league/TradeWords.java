import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.Json;

/** Branch at the first locally observed trade. Cover three heads then return to the same
 * primed controller, or use W-tap/Hold-W for the rest of the bout. No future state enters the legal bot. */
final class TradeWords {
    static int exchange=1;
    static boolean auditForks, isolateInputs;
    static final String[] WORDS={"continue","release-ctrl","release-w","back","recoil-click","late-click","jump","left","right","wtap","hold"};
    static final String[] CHANNELS={"key","strafe","click","jump"};
    static int forcedWord(int choice){
        if(choice<WORDS.length)return choice;
        return (choice-WORDS.length)/15==0?7:4;
    }
    static int forcedMask(int choice){
        return choice<WORDS.length?15:1+(choice-WORDS.length)%15;
    }
    static String branchName(int choice){
        if(choice<WORDS.length)return WORDS[choice];
        List<String> names=new ArrayList<>();
        for(int i=0;i<CHANNELS.length;i++)if((forcedMask(choice)&(1<<i))!=0)names.add(CHANNELS[i]);
        return WORDS[forcedWord(choice)]+"/"+String.join("+",names);
    }
    static final class Probe extends DuelEnv.Player{
        Arena.Neural base;
        DuelEnv.Player wt,hold;
        PeriodicComboClock.Observation last;
        double[] x;
        boolean marked,inExchange;int exchanges;
        int choice,remaining;
        Probe(Arena.Model m,int ping){super("trade-probe",0,ping,0);base=new Arena.Neural(m,ping);wt=new DuelEnv.Player("wtap",0,ping,0);hold=new DuelEnv.Player("hold",0,ping,0);}
        private Probe(Probe s){
            super(s.style,0,s.rtt,0);base=s.base.copy();wt=s.wt.copy();hold=s.hold.copy();last=s.last;x=s.x;marked=s.marked;inExchange=s.inExchange;exchanges=s.exchanges;
            choice=s.choice;remaining=s.remaining;firstClick=s.firstClick;
        }
        @Override Probe copy(){return new Probe(this);}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var b=base.input(o);var w=wt.input(o);var h=hold.input(o);last=o;
            boolean paired=o.time()-o.lastOwnHurtTime()<230 && o.time()-o.lastTargetHurtTime()<230;
            if(paired && !inExchange)exchanges++;
            inExchange=paired;
            if(!marked && paired && exchanges>=exchange){
                marked=true;x=base.net.features();
            }
            if(choice==0)return b;
            PeriodicComboClock.Control use=b;
            boolean away=base.net.features()[2]<0;
            if(choice==9)use=w;else if(choice==10)use=h;
            else if(remaining-->0){
                int word=forcedWord(choice),mask=forcedMask(choice);
                int key=word==1?1:word==2?0:word==3?3:2;
                float side=word==7?.98f:word==8?-.98f:0;
                boolean click=word==4?away:word!=5||remaining==0;
                boolean jump=word==6&&remaining==2&&o.own().onGround();
                // All unforced channels follow the actor on this branch's own observations.
                use=new PeriodicComboClock.Control((mask&1)!=0?key:b.key(),(mask&2)!=0?side:b.strafe(),
                    (mask&4)!=0?click:b.click(),(mask&8)!=0?jump:b.jump());
            }
            base.net.applied(DuelNetwork.pack(use.key(),(int)Math.signum(use.strafe()),use.click(),use.jump()));
            return use;
        }
    }
    static String stateHash(DuelEnv.Game game)throws Exception{
        return FullClockHash.digest(List.of(FullClockHash.digest(game.clock),game.a,game.b,game.step,game.winner()));
    }
    static void run(DuelEnv env,boolean first,Arena.Fighter f,Arena.Fighter opp)throws Exception{
        var p=new Probe(f.model(),env.rtt);var g=env.new Game(first,p,opp.create(env.rtt,0));
        while(g.step<env.serverEnds.length && g.winner()<0 && !p.marked)
            if(!g.clock.runHeadInterval(first,env.serverEnds[g.step]))g.step++;
        Map<String,Object> result=new LinkedHashMap<>();result.put("prefix",f.name());result.put("opponent",opp.name());
        result.put("clock",env.clockId);result.put("side",first?"a":"b");result.put("rtt",env.rtt);
        String noChange=null;
        if(p.marked){
            double start=g.clock.time();result.put("at",start);result.put("x",p.x);
            var scores=new LinkedHashMap<String,Object>();result.put("continuations",scores);
            if(auditForks)result.put("fork_sha256",stateHash(g));
            for(int i=0;i<WORDS.length+(isolateInputs?30:0);i++){
                var b=env.new Game(g,g.a.copy(),g.b.copy());
                if(i==0 && auditForks && !stateHash(b).equals(result.get("fork_sha256")))
                    throw new AssertionError("Trade fork changed the plant or primed policies");
                var q=(Probe)b.our();
                if(i!=0){q.choice=i;q.remaining=3;}
                b.trace();
                while(b.clock.time()<start+900&&b.advance()){}
                var s=b.clock.state;var own=first?s.serverA:s.serverB;var other=first?s.serverB:s.serverA;
                double offset=lab.SumoFootprint.centerOffset(env.initial.config.sumoDiameter);
                double radial=Math.hypot(own.x()-(env.initial.config.centerX+offset),own.z()-offset);
                var early=b.hits.list.stream().map(v->List.of(v.at()-start,v.sideA()==first,v.upgrade())).toList();
                double gap=Math.hypot(own.x()-other.x(),own.z()-other.z());
                b.finish();
                var outcome=List.of(b.reward(),gap,radial,early);
                if(i>=WORDS.length && forcedMask(i)==15 && !outcome.equals(scores.get(WORDS[forcedWord(i)])))
                    throw new AssertionError("All four forced channels must reproduce their original trace and outcome");
                scores.put(branchName(i),outcome);
                if(i==0 && auditForks)noChange=stateHash(b);
            }
        }
        g.finish();result.put("base",g.reward());
        if(p.marked){
            List<?> retained=(List<?>)((Map<?,?>)result.get("continuations")).get("continue");
            if((double)retained.get(0)!=g.reward())throw new AssertionError("No-op trade continuation diverged");
            if(auditForks){
                if(!stateHash(g).equals(noChange))throw new AssertionError("No-op continuation changed terminal state");
                result.put("unchanged_terminal_sha256",noChange);
            }
        }
        System.out.println(Json.encode(result));
    }
    public static void main(String[] args)throws Exception{
        var arms=Matches.roster(Path.of(args[0]));var opponents=Matches.roster(Path.of(args[1]));
        int start=2;if(args[start].equals("--second-trade")){exchange=2;start++;}
        boolean mouse=args[start].equals("--mouse");if(mouse)start++;
        auditForks=args[start].equals("--audit-forks");if(auditForks)start++;
        isolateInputs=args[start].equals("--isolate-inputs");if(isolateInputs)start++;
        boolean shifted=args[start].equals("--phase-fit");
        var tapes=Arrays.stream(args,shifted?start+1:start,args.length).map(Path::of).toList();
        var envs=Arena.envs(tapes,"mmc",true);
        if(shifted)for(Path file:tapes){
            var tape=DuelEnv.Tape.read(file,DuelEnv.LIMIT);
            for(int v=1;v<=3;v++)for(int ping:new int[]{0,80}){
                var changed=BoxingDrill.shift(tape,11*v,13*v);
                envs.add(new DuelEnv(changed,"mmc",ping,ping,true,24+v,true,8+2*v,5.5+v*.4,(v-2)*.6));
            }
        }
        if(mouse)for(var e:envs)e.initial.timing.mouseInput=true;
        for(var e:envs)
            for(var f:arms)for(var opp:opponents)for(boolean seat:new boolean[]{true,false})run(e,seat,f,opp);
    }
}
