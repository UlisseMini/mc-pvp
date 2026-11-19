import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.DuelNetwork;
import lab.Json;

/** Fork before the first actual disagreement between a click selector and its own PPO expert. */
final class ContactTimingStudy {
    static boolean observeOnly;
    record Word(String name, int delay, int burst, boolean wtap) {}
    static final List<Word> WORDS = List.of(new Word("unchanged",0,0,false), new Word("wtap",0,0,true),
            new Word("defer-1",1,0,false),new Word("defer-3",3,0,false),new Word("defer-6",6,0,false),
            new Word("click-3",0,3,false),new Word("click-6",0,6,false),
            new Word("defer-1-click-6",1,6,false),new Word("defer-3-click-6",3,6,false));

    static final class Probe extends DuelEnv.Player {
        final Arena.Neural candidate, raw;
        final DuelEnv.Player tap;
        int heads, firstExtra, choice, start;
        double at;
        List<Double> view;

        Probe(Arena.Model fit,Arena.Model actor,int rtt) {
            super("contact-timing",0,rtt,0);
            candidate=new Arena.Neural(fit,rtt);
            raw=new Arena.Neural(actor,rtt);
            tap=new DuelEnv.Player("wtap",0,rtt,0);
        }
        Probe(Probe p) {
            super(p.style,0,p.rtt,0); candidate=p.candidate.copy();raw=p.raw.copy();tap=p.tap.copy();
            heads=p.heads;firstExtra=p.firstExtra;choice=p.choice;start=p.start;
            at=p.at;view=p.view;firstClick=p.firstClick;
        }
        @Override Probe copy(){return new Probe(this);}

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            heads++;
            var c=candidate.input(o);var a=raw.input(o);var w=tap.input(o);
            if(c.key()!=a.key() || c.strafe()!=a.strafe() || c.jump()!=a.jump() || a.click()&&!c.click())
                throw new AssertionError("Paired experts changed a channel other than opening clicks");
            if(firstExtra==0 && c.click()!=a.click()){
                firstExtra=heads;at=o.time();
                var f=candidate.net.features();var own=o.own();
                view=List.of(o.nearest(),Math.hypot(own.x()-o.targetX(),own.z()-o.targetZ()),f[2],f[5],f[12],
                        f[10],f[11],(double)(o.sprint()?1:0),(double)c.key(),(double)c.strafe(),(double)(c.jump()?1:0));
            }
            if(choice>0 && heads>=start) {
                var word=WORDS.get(choice);int age=heads-start;
                if(word.wtap)c=w;
                else if(age<word.delay+word.burst)
                    c=new PeriodicComboClock.Control(c.key(),c.strafe(),age>=word.delay,c.jump());
            }
            int action=DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump());
            raw.net.applied(action);candidate.net.applied(action);
            return c;
        }
    }

    static DuelEnv.Game start(DuelEnv e,Arena.Fighter fit,Arena.Fighter raw,Arena.Fighter foe,boolean seat) {
        return e.new Game(seat,new Probe(fit.model(),raw.model(),e.rtt),foe.create(e.rtt,0));
    }

    static Map<String,Object> outcome(DuelEnv.Game g,double start) {
        var s=g.clock.state;
        var early=g.hits.list.stream().filter(h -> h.at()>=start && h.at()<=start+1500)
                .map(h->List.of(h.at()-start,h.sideA()==g.first,h.upgrade())).toList();
        return Map.of("score",g.reward(),"end",g.clock.time(),"fresh",g.first?s.hits:s.incomingHits,
                "incoming",g.first?s.incomingHits:s.hits,"next_1500ms",early);
    }

    static void run(DuelEnv e,Arena.Fighter fit,Arena.Fighter raw,Arena.Fighter foe,boolean seat)throws Exception{
        var scout=start(e,fit,raw,foe,seat);scout.trace();scout.finish();var player=(Probe)scout.our();
        var row=new LinkedHashMap<String,Object>();
        row.put("opponent",foe.name());row.put("clock",e.clockId);row.put("rtt",e.rtt);row.put("side",seat?"a":"b");
        row.put("original",outcome(scout,player.at));row.put("first_extra_head",player.firstExtra);
        var wt=new Arena.Scenario(e,foe,seat).round(Arena.Fighter.named("wtap"));
        row.put("plain_wtap",wt.reward());
        if(player.firstExtra>0){row.put("at",player.at);row.put("local_view",player.view);}
        if(!observeOnly && player.firstExtra>0){
            var g=start(e,fit,raw,foe,seat);var p=(Probe)g.our();
            while(g.step<e.serverEnds.length && g.winner()<0){
                double limit=e.serverEnds[g.step];
                if(!g.clock.advanceToBeforeNextHead(seat,limit)){g.step++;continue;}
                if(p.heads+1==player.firstExtra)break;
                if(!g.clock.runHeadInterval(seat,limit))g.step++;
            }
            if(Math.abs(g.clock.time()-player.at)>1e-8 || g.winner()>=0 || p.firstExtra!=0)
                throw new AssertionError("First extra click was not located before the original input");
            row.put("prior_accepted",List.of(g.clock.state.acceptedA,g.clock.state.acceptedB));
            String prefix=TradeWords.stateHash(g);
            row.put("fork_sha256",prefix);
            var branches=new LinkedHashMap<String,Object>();
            for(int i=0;i<WORDS.size();i++){
                var b=e.new Game(g,g.a.copy(),g.b.copy());
                if(i==0&&!prefix.equals(TradeWords.stateHash(b)))throw new AssertionError("Changed first input prefix");
                var q=(Probe)b.our();
                if(i>0){q.choice=i;q.start=player.firstExtra;}
                b.trace();b.finish();
                if(!player.view.equals(q.view))throw new AssertionError("Different visible decision");
                if(i==0){
                    String hash=TradeWords.stateHash(b);
                    if(!hash.equals(TradeWords.stateHash(scout)))throw new AssertionError("No-op changed terminal state");
                    row.put("unchanged_terminal_sha256",hash);
                }
                branches.put(WORDS.get(i).name,outcome(b,player.at));
            }
            if(!prefix.equals(TradeWords.stateHash(g)))throw new AssertionError("Shared prefix mutated");
            row.put("branches",branches);
        }
        System.out.println(Json.encode(row));
    }
    static void replayInputs(String[] args)throws Exception{
        var fit=DuelNetwork.decode(java.nio.file.Files.readString(Path.of(args[1])).trim());
        var raw=DuelNetwork.decode(java.nio.file.Files.readString(Path.of(args[2])).trim());
        var in=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        String line;
        while((line=in.readLine())!=null){
            var fields=line.split(" ");double[] v=new double[14];
            for(int i=0;i<v.length;i++)v[i]=Double.parseDouble(fields[i]);
            double own=v[11]<0?Double.POSITIVE_INFINITY:v[11],target=v[12]<0?Double.POSITIVE_INFINITY:v[12];
            int first=fit.step(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9]!=0,v[10]!=0,own,target,v[13]);
            int stock=raw.step(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9]!=0,v[10]!=0,own,target,v[13]);
            int applied=Integer.parseInt(fields[15]);
            if(first!=Integer.parseInt(fields[14]) || DuelNetwork.key(first)!=DuelNetwork.key(stock)
                    || DuelNetwork.jump(first)!=DuelNetwork.jump(stock) || DuelNetwork.side(first)!=DuelNetwork.side(stock)
                    || DuelNetwork.click(stock)&&!DuelNetwork.click(first))throw new AssertionError("Divergent native control");
            System.out.println(first==stock?0:1);
            fit.applied(applied);raw.applied(applied);
        }
    }

    public static void main(String[] args)throws Exception{
        if(args[0].equals("--native-heads")){replayInputs(args);return;}
        observeOnly=args[2].equals("--observe");
        var arms=Matches.roster(Path.of(args[0]));var foes=Matches.roster(Path.of(args[1]));
        for(var e:Arena.envs(Arrays.stream(args,observeOnly?3:2,args.length).map(Path::of).toList(),"mmc",true)){
            e.initial.timing.mouseInput=true;
            for(var foe:foes)for(boolean seat:new boolean[]{true,false})run(e,arms.get(0),arms.get(1),foe,seat);
        }
    }
}
