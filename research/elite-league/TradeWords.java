import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.Json;

/** Branch at the first locally observed trade. Cover three heads then return to the same
 * primed controller, or use W-tap/Hold-W for the rest of the bout. No future state enters the legal bot. */
final class TradeWords {
    static int exchange=1;
    static final String[] WORDS={"continue","release-ctrl","release-w","back","recoil-click","late-click","jump","left","right","wtap","hold"};
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
                int key=choice==1?1:choice==2?0:choice==3?3:2;
                float side=choice==7?.98f:choice==8?-.98f:0;
                boolean click=choice==4?away:choice!=5||remaining==0;
                use=new PeriodicComboClock.Control(key,side,click,choice==6&&remaining==2&&o.own().onGround());
            }
            base.net.applied(DuelNetwork.pack(use.key(),(int)Math.signum(use.strafe()),use.click(),use.jump()));
            return use;
        }
    }
    static void run(DuelEnv env,boolean first,Arena.Fighter f,Arena.Fighter opp){
        var p=new Probe(f.model(),env.rtt);var g=env.new Game(first,p,opp.create(env.rtt,0));
        while(g.step<env.serverEnds.length && g.winner()<0 && !p.marked)
            if(!g.clock.runHeadInterval(first,env.serverEnds[g.step]))g.step++;
        Map<String,Object> result=new LinkedHashMap<>();result.put("prefix",f.name());result.put("opponent",opp.name());
        result.put("clock",env.clockId);result.put("side",first?"a":"b");result.put("rtt",env.rtt);
        if(p.marked){
            double start=g.clock.time();result.put("at",start);result.put("x",p.x);
            var scores=new LinkedHashMap<String,Object>();result.put("continuations",scores);
            for(int i=0;i<WORDS.length;i++){
                var b=env.new Game(g,g.a.copy(),g.b.copy());var q=(Probe)b.our();q.choice=i;q.remaining=3;b.trace();
                while(b.clock.time()<start+900&&b.advance()){}
                var s=b.clock.state;var own=first?s.serverA:s.serverB;var other=first?s.serverB:s.serverA;
                double radial=Math.hypot(own.x()-env.initial.config.centerX,own.z());
                var early=b.hits.list.stream().map(v->List.of(v.at()-start,v.sideA()==first,v.upgrade())).toList();
                double gap=Math.hypot(own.x()-other.x(),own.z()-other.z());
                b.finish();scores.put(WORDS[i],List.of(b.reward(),gap,radial,early));
            }
        }
        g.finish();result.put("base",g.reward());
        if(p.marked){
            List<?> retained=(List<?>)((Map<?,?>)result.get("continuations")).get("continue");
            if((double)retained.get(0)!=g.reward())throw new AssertionError("No-op trade continuation diverged");
        }
        System.out.println(Json.encode(result));
    }
    public static void main(String[] args)throws Exception{
        var arms=Matches.roster(Path.of(args[0]));var opponents=Matches.roster(Path.of(args[1]));
        int start=2;if(args[start].equals("--second-trade")){exchange=2;start++;}
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
        for(var e:envs)
            for(var f:arms)for(var opp:opponents)for(boolean seat:new boolean[]{true,false})run(e,seat,f,opp);
    }
}
