import lab.Json;
import java.nio.file.*;
import java.util.*;

/** Training-wheels feasibility search. Branch ranking and pruning deliberately
 * see full simulator state. NOT a legal live policy and NOT an absence proof.
 * Each branch itself uses ordinary W/Ctrl/click input at <=20CPS.
 */
public final class ComboOracleSearch {
    record Link(Link previous,int key,int strafe,boolean click) {}
    record Node(PeriodicComboClock clock,Link path,double score,double minDenial,int fresh) {}
    static final PeriodicComboClock.Policy OPPONENT=o->new PeriodicComboClock.Control(2,0,o.time()>=2000);
    static final class Guard implements PeriodicComboClock.Audit {
        final int baselineReturns;final double reach;double minimum=1e9;boolean valid=true;String failure="";
        Guard(int returns,double reach){baselineReturns=returns;this.reach=reach;}
        public void event(double t,String event,PeriodicComboFightModel.State s){
            minimum=Math.min(minimum,PeriodicComboFightModel.their(s)-reach);
            if(minimum<=0){valid=false;failure="defender_geometry";}
            if(s.acceptedB>baselineReturns){valid=false;failure="accepted_return";}
            // A has an input opportunity before every subsequent server drain in
            // this fixed phase setup. A late fresh hit fails this strict stage.
            if(event.equals("SERVER_DRAIN")&&s.resistant<=s.freshThreshold){valid=false;failure="fresh_window_missed";}
        }
    }
    static double score(PeriodicComboFightModel.State s){
        double own=PeriodicComboFightModel.own(s),other=PeriodicComboFightModel.their(s);
        double remaining=Math.max(0,s.resistant-s.freshThreshold)/(double)s.freshThreshold;
        return Math.min(other-PeriodicComboFightModel.defenderReach,3+1.5*remaining-own)-.02*Math.abs(own-2.8);
    }
    static String cell(PeriodicComboFightModel.State s){
        double dx=s.b.q.x()-s.a.q.x(),dz=s.b.q.z()-s.a.q.z(),r=Math.hypot(dx,dz),ux=r==0?1:dx/r,uz=r==0?0:dz/r;
        StringBuilder q=new StringBuilder();
        double[] values={PeriodicComboFightModel.own(s),PeriodicComboFightModel.their(s),r,s.b.q.y(),s.b.q.vy(),s.serverB.vy(),
            s.a.q.vx()*ux+s.a.q.vz()*uz,-s.a.q.vx()*uz+s.a.q.vz()*ux,s.b.q.vx()*ux+s.b.q.vz()*uz,-s.b.q.vx()*uz+s.b.q.vz()*ux,
            s.ab.x-s.a.q.x(),s.ab.z-s.a.q.z(),s.ba.x-s.a.q.x(),s.ba.z-s.a.q.z(),s.ab.gx-s.a.q.x(),s.ab.gz-s.a.q.z(),s.ba.gx-s.a.q.x(),s.ba.gz-s.a.q.z(),
            s.serverA.vx(),s.serverA.vz(),s.serverB.vx(),s.serverB.vz()};
        for(double v:values)q.append(Math.round(v*40)).append(':');
        q.append(s.a.sprint).append(':').append(s.a.lastSent).append(':').append(s.serverSprint).append(':').append(s.resistant).append(':').append(s.ab.left).append(':').append(s.ba.left);
        for(var p:s.packets)q.append('/').append(p.kind).append(':').append(Math.round(p.due/5)).append(':').append(Math.round((p.x-s.a.q.x())*32)).append(':').append(Math.round(p.y*32)).append(':').append(Math.round((p.z-s.a.q.z())*32)).append(':').append(Math.round(p.vx*40)).append(':').append(Math.round(p.vy*40)).append(':').append(Math.round(p.vz*40)).append(':').append(p.flag);
        return q.toString();
    }
    public static void main(String[] args)throws Exception{
        if(args.length<6)throw new IllegalArgumentException("output.json defenderReach horizontal vertical hitTicks durationSeconds [beam=96] [opponentRTT=120] [strafe=1] [seedMs=auto] [openingBurst=2]");
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("Refuse overwrite");
        double reach=Double.parseDouble(args[1]),horizontal=Double.parseDouble(args[2]),vertical=Double.parseDouble(args[3]),seconds=Double.parseDouble(args[5]);int hitTicks=Integer.parseInt(args[4]),width=args.length>6?Integer.parseInt(args[6]):96;
        var config=new PeriodicComboFightModel.Config();config.ownRtt=0;config.otherRtt=args.length>7?Double.parseDouble(args[7]):120;config.defenderReach=reach;config.kbScale=horizontal;config.verticalScale=vertical;config.hitTicks=hitTicks;config.forceContacts=false;
        var timing=new PeriodicComboClock.Timing();timing.bTickPhase=Double.parseDouble(System.getProperty("combo.b_phase","10"));timing.serverPhase=Double.parseDouble(System.getProperty("combo.server_phase","25"));timing.uplinkA=timing.uplinkB=timing.downlinkA=timing.downlinkB=.4;
        PeriodicComboFightModel.defenderReach=reach;
        boolean strafeEnabled=args.length<=8||!args[8].equals("0");double requestedSeed=args.length>9&&!args[9].equals("auto")?Double.parseDouble(args[9]):-1;int openingBurst=args.length>10?Integer.parseInt(args[10]):2;
        var plan=new ComboCycleSearch.Plan(2*hitTicks,hitTicks,Math.min(4,2*hitTicks-1),0,openingBurst,0);
        var initial=new PeriodicComboClock(new PeriodicComboFightModel.State(4093,0,4099,0),config,timing,new ComboCycleSearch.Controller(plan,hitTicks),OPPONENT);
        double seed=0;
        for(double t=requestedSeed>=0?requestedSeed:2000;t<=(requestedSeed>=0?requestedSeed:15000);t+=50){
            try{initial.runUntil(t-.001);}catch(IllegalStateException e){break;}
            var s=initial.state;
            if(s.hits>=3&&s.resistant>=hitTicks+1&&PeriodicComboFightModel.their(s)>reach+.1&&PeriodicComboFightModel.own(s)<3&&s.packets.stream().noneMatch(p->p.kind==7)){seed=t;break;}
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("scope","Oracle beam search for a separated maintenance trajectory; hidden state used only offline. Finite beam pruning is not exhaustive and cannot prove impossibility. Acquisition is a separately declared legal word with2s target no-click handicap.");
        report.put("combat",List.of(horizontal,vertical,hitTicks));report.put("rtt",List.of(0,config.otherRtt));report.put("phases",List.of(timing.aTickPhase,timing.bTickPhase,timing.serverPhase));report.put("reach",reach);report.put("beam",width);report.put("seed_ms",seed);report.put("strafe_enabled",strafeEnabled);report.put("opening_burst",openingBurst);
        if(seed==0){report.put("result","seed_notfound");Files.writeString(out,Json.encode(report)+"\n");return;}
        int initialHits=initial.state.hits,returns=initial.state.acceptedB;
        List<Node> beam=List.of(new Node(initial,null,score(initial.state),PeriodicComboFightModel.their(initial.state)-reach,0));Node best=beam.get(0);List<Map<String,Object>> progress=new ArrayList<>();Map<String,Integer> rejections=new TreeMap<>();int step=0;
        for(;step<(int)(seconds*20);step++){
            Map<String,Node> distinct=new HashMap<>();
            for(Node parent:beam)for(int key=0;key<=2;key++)for(int strafe:strafeEnabled?new int[]{-1,0,1}:new int[]{0})for(boolean click:new boolean[]{false,true}){
                final int chosenKey=key;final boolean chosenClick=click;
                PeriodicComboClock next=parent.clock.fork(o->new PeriodicComboClock.Control(chosenKey,strafe*.98f,chosenClick),OPPONENT);
                Guard guard=new Guard(returns,reach);next.setAudit(guard);
                try{next.runUntil(seed+(step+1)*50-.001);}catch(IllegalStateException e){rejections.merge("unsupported:"+e.getMessage(),1,Integer::sum);continue;}
                if(!guard.valid){rejections.merge(guard.failure,1,Integer::sum);continue;}
                var s=next.state;Node n=new Node(next,new Link(parent.path,key,strafe,click),score(s),Math.min(parent.minDenial,guard.minimum),s.hits-initialHits);
                String cell=cell(s);Node old=distinct.get(cell);if(old==null||n.score>old.score)distinct.put(cell,n);
            }
            if(distinct.isEmpty())break;
            var selected=new ArrayList<>(distinct.values());selected.sort(Comparator.comparingDouble(Node::score).reversed());beam=selected.subList(0,Math.min(width,selected.size()));best=beam.get(0);
            if(step%20==19){Map<String,Object> row=Map.of("seconds",(step+1)/20.0,"survivors_before_beam",distinct.size(),"fresh_hits",best.fresh,"own_range",PeriodicComboFightModel.own(best.clock.state),"defender_range",PeriodicComboFightModel.their(best.clock.state),"min_margin",best.minDenial);progress.add(row);if(step%200==199)System.err.println(Json.encode(row));}
        }
        List<Map<String,Object>> controls=new ArrayList<>();for(Link link=best.path;link!=null;link=link.previous)controls.add(Map.of("key",link.key,"strafe",link.strafe,"click",link.click));Collections.reverse(controls);
        report.put("result",step==(int)(seconds*20)?"finite_witness":"beam_extinguished");report.put("maintained_seconds",controls.size()/20.0);report.put("fresh_hits",best.fresh);report.put("minimum_defender_margin",best.minDenial);report.put("rejections",rejections);report.put("progress",progress);report.put("controls",controls);
        Files.writeString(out,Json.encode(report)+"\n");System.out.println(Json.encode(Map.of("file",out.toString(),"result",report.get("result"),"seconds",report.get("maintained_seconds"),"fresh",best.fresh,"margin",best.minDenial,"seed",seed)));
    }
}
