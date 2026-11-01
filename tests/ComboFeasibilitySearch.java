import lab.Json;
import java.nio.file.*;
import java.util.*;

/** Offline oracle beam: prune causally committed denial failures before ranking.
 * This is a finite heuristic search, never an exhaustive impossibility proof. */
public final class ComboFeasibilitySearch {
    record Link(Link previous,int key,int strafe,boolean click){}
    record Node(PeriodicComboClock clock,Link path,double score,double minimum,int fresh){}
    record Forecast(double margin,double firstFailure,double cutoff,boolean separated){}
    static final PeriodicComboClock.Policy QUIET=o->new PeriodicComboClock.Control(0,0,false);
    static double next(double t,double phase,double period){return phase+Math.ceil((t-phase)/period)*period;}
    static Forecast committed(PeriodicComboClock clock,double reach){
        var t=clock.timing;var c=clock.config;
        double input=next(clock.time()+1e-8,t.aTickPhase,t.tickPeriod);
        double server=next(input+c.ownRtt/2+t.uplinkA,t.serverPhase,t.serverPeriod);
        double cutoff=next(server+c.otherRtt/2+t.downlinkB,t.bFramePhase,t.framePeriod);
        double[] margin={PeriodicComboFightModel.their(clock.state)-reach},first={Double.NaN};boolean[] separated={true};
        var copy=clock.fork(QUIET,ComboOracleSearch.OPPONENT);
        copy.setAudit((time,event,s)->{double d=PeriodicComboFightModel.their(s)-reach;margin[0]=Math.min(margin[0],d);if(d<=0&&Double.isNaN(first[0]))first[0]=time;
            // Exclude the server collision branch from the causal certificate.
            double dx=Math.abs(s.serverAx-s.serverBx),dz=Math.abs(s.serverAz-s.serverBz);if(dx<.81&&dz<.81)separated[0]=false;
        });
        copy.runUntil(cutoff-1e-5);return new Forecast(margin[0],first[0],cutoff,separated[0]);
    }
    static double rank(PeriodicComboFightModel.State s,double committed){return ComboOracleSearch.score(s);}
    public static void main(String[] args)throws Exception{
        if(args.length<6)throw new IllegalArgumentException("out.json reach horizontal vertical hitTicks seconds [beam=96] [opponentRTT=120] [strafe=1] [seedMs=auto] [openingBurst=2]");
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("Refuse overwrite");
        double reach=Double.parseDouble(args[1]),horizontal=Double.parseDouble(args[2]),vertical=Double.parseDouble(args[3]),seconds=Double.parseDouble(args[5]);int hitTicks=Integer.parseInt(args[4]),width=args.length>6?Integer.parseInt(args[6]):96;
        boolean strafeEnabled=args.length<=8||!args[8].equals("0");double requestedSeed=args.length>9&&!args[9].equals("auto")?Double.parseDouble(args[9]):-1;int openingBurst=args.length>10?Integer.parseInt(args[10]):2;
        var config=new PeriodicComboFightModel.Config();config.ownRtt=0;config.otherRtt=args.length>7?Double.parseDouble(args[7]):120;config.defenderReach=reach;config.forceContacts=false;config.kbScale=horizontal;config.verticalScale=vertical;config.hitTicks=hitTicks;
        var timing=new PeriodicComboClock.Timing();timing.bTickPhase=Double.parseDouble(System.getProperty("combo.b_phase","10"));timing.serverPhase=Double.parseDouble(System.getProperty("combo.server_phase","25"));timing.uplinkA=timing.uplinkB=timing.downlinkA=timing.downlinkB=.4;
        PeriodicComboFightModel.defenderReach=reach;
        var plan=new ComboCycleSearch.Plan(2*hitTicks,hitTicks,Math.min(4,2*hitTicks-1),0,openingBurst,0);
        var opening=new ComboCycleSearch.Controller(plan,hitTicks);
        var initial=new PeriodicComboClock(new PeriodicComboFightModel.State(4093,0,4099,0),config,timing,opening,ComboOracleSearch.OPPONENT);
        double seed=0;Forecast initialForecast=null;
        for(double at=requestedSeed>=0?requestedSeed:2000;at<=(requestedSeed>=0?requestedSeed:15000);at+=50){
            try{initial.runUntil(at-.001);var s=initial.state;if(s.hits<3||s.resistant<=hitTicks||PeriodicComboFightModel.their(s)<=reach+.1||PeriodicComboFightModel.own(s)>=3||s.packets.stream().anyMatch(p->p.kind==7))continue;Forecast f=committed(initial,reach);if(!f.separated||f.margin<=0)continue;seed=at;initialForecast=f;break;}catch(IllegalStateException e){break;}
        }
        if(seed==0){Files.writeString(out,Json.encode(Map.of("result","seed_notfound","reach",reach,"combat",List.of(horizontal,vertical,hitTicks),"rtt",List.of(0,config.otherRtt),"strafe_enabled",strafeEnabled,"requested_seed",requestedSeed,"phases",List.of(timing.aTickPhase,timing.bTickPhase,timing.serverPhase),"scope","The specified acquisition failed to produce a viable separated seed; no maintenance infeasibility conclusion."))+"\n");return;}
        int initialHits=initial.state.hits,returns=initial.state.acceptedB;
        List<Node> beam=List.of(new Node(initial,null,rank(initial.state,initialForecast.margin),PeriodicComboFightModel.their(initial.state)-reach,0));Node best=beam.get(0),elite=best;double eliteAnchor=opening.anchor;int eliteSteps=0;Map<String,Integer> rejected=new TreeMap<>();List<Map<String,Object>> progress=new ArrayList<>();int step=0;
        for(;step<(int)(seconds*20);step++){
            Map<String,Node> distinct=new HashMap<>();
            for(Node parent:beam)for(int key=0;key<3;key++)for(int strafe:strafeEnabled?new int[]{-1,0,1}:new int[]{0})for(boolean click:new boolean[]{false,true}){
                final int k=key;final boolean hit=click;var clock=parent.clock.fork(o->new PeriodicComboClock.Control(k,strafe*.98f,hit),ComboOracleSearch.OPPONENT);var guard=new ComboOracleSearch.Guard(returns,reach);clock.setAudit(guard);
                Forecast f;
                try{clock.runUntil(seed+(step+1)*50-.001);if(!guard.valid){rejected.merge(guard.failure,1,Integer::sum);continue;}f=committed(clock,reach);}catch(IllegalStateException e){rejected.merge("unsupported",1,Integer::sum);continue;}
                if(!f.separated){rejected.merge("outside_separated_domain",1,Integer::sum);continue;}
                if(f.margin<=0){rejected.merge("committed_future_denial",1,Integer::sum);continue;}
                var s=clock.state;Node n=new Node(clock,new Link(parent.path,key,strafe,click),rank(s,f.margin),Math.min(parent.minimum,guard.minimum),s.hits-initialHits);
                String cell=ComboOracleSearch.cell(s);
                Node old=distinct.get(cell);if(old==null||n.score>old.score)distinct.put(cell,n);
            }
            Node nextElite=null;
            if(elite!=null){int phase=(int)Math.round((seed+step*50-eliteAnchor)/50)%(2*hitTicks);final int key=plan.key(phase);final boolean click=phase%hitTicks<openingBurst;
                var clock=elite.clock.fork(o->new PeriodicComboClock.Control(key,0,click),ComboOracleSearch.OPPONENT);var guard=new ComboOracleSearch.Guard(returns,reach);clock.setAudit(guard);
                try{clock.runUntil(seed+(step+1)*50-.001);Forecast f=committed(clock,reach);if(guard.valid&&f.separated&&f.margin>0){var s=clock.state;nextElite=new Node(clock,new Link(elite.path,key,0,click),rank(s,f.margin),Math.min(elite.minimum,guard.minimum),s.hits-initialHits);distinct.put("known_word_elite",nextElite);eliteSteps++;}}catch(IllegalStateException ignored){}
            }
            elite=nextElite;
            if(distinct.isEmpty())break;
            var candidates=new ArrayList<>(distinct.values());candidates.sort(Comparator.comparingDouble(Node::score).reversed());beam=new ArrayList<>(candidates.subList(0,Math.min(width,candidates.size())));if(elite!=null&&!beam.contains(elite))beam.set(beam.size()-1,elite);best=beam.get(0);
            if(step%20==19){var row=Map.<String,Object>of("seconds",(step+1)/20.,"fresh",best.fresh,"margin",best.minimum,"survivors",distinct.size());progress.add(row);if(step%200==199)System.err.println(Json.encode(row));}
        }
        List<Map<String,Object>> controls=new ArrayList<>();for(Link p=best.path;p!=null;p=p.previous)controls.add(Map.of("key",p.key,"strafe",p.strafe,"click",p.click));Collections.reverse(controls);
        Map<String,Object> report=new LinkedHashMap<>();report.put("scope","Finite oracle beam using same canonical fight kernel. Causal forecast ends strictly before a new A input can reach B via earliest server/drain/downlink/frame route; separated server-body domain only. Initial inputs and2s target no-click opening declared; maintenance scored separately. Beam rank/dedup are heuristic, no absence proof.");report.put("search_ablation","original beam rank/cell plus committed-prefix pruning and explicit known-word elite");report.put("known_word_survived_seconds",eliteSteps/20.);report.put("opening_burst",openingBurst);report.put("phases",List.of(timing.aTickPhase,timing.bTickPhase,timing.serverPhase));report.put("reach",reach);report.put("rtt",List.of(0,config.otherRtt));report.put("combat",List.of(horizontal,vertical,hitTicks));report.put("strafe_enabled",strafeEnabled);report.put("beam",width);report.put("seed_ms",seed);report.put("initial_committed_margin",initialForecast.margin);report.put("result",step==(int)(seconds*20)?"finite_witness":"beam_extinguished");report.put("maintained_seconds",controls.size()/20.);report.put("fresh_hits",best.fresh);report.put("minimum_defender_margin",best.minimum);report.put("rejections",rejected);report.put("progress",progress);report.put("controls",controls);Files.writeString(out,Json.encode(report)+"\n");System.out.println(Json.encode(Map.of("file",out.toString(),"result",report.get("result"),"seconds",report.get("maintained_seconds"),"fresh",best.fresh,"margin",best.minimum)));
    }
}
