import java.io.*;
import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.Json;

/** Offline monitor for the pinned league runtime. Player edits use only its own input history. */
final class Gen4Review {
    static final double[] ZERO = new double[32];
    static final int SWAP_AT = Integer.getInteger("review.swapAt",25);

    static final class Watched extends DuelEnv.Player {
        final DuelEnv.Player base;
        final String arm;
        final Arena.Neural neural;
        final List<Object> samples = new ArrayList<>();
        final ArrayDeque<double[]> recent = new ArrayDeque<>();
        final double[] scratch = new double[5];
        final int[] counts = new int[11];
        final int[] opening = new int[11];
        int heads;
        double[] donor, historicalState;
        final boolean detail;
        Watched(DuelEnv.Player base, String arm, boolean detail) {
            super("review",0,base.rtt,0);
            this.base=base; this.arm=arm; this.detail=detail;
            neural=base instanceof Arena.Neural n ? n : null;
        }
        public void received(PeriodicComboClock.PacketObservation p){base.received(p);}
        public boolean injected(PeriodicComboClock.Observation o,double t){return base.injected(o,t);}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            heads++;
            var league=neural==null?null:neural.net.league();
            boolean since6=arm.equals("recent6");
            boolean swap=arm.startsWith("swap") && heads==SWAP_AT && donor!=null;
            if(arm.equals("no-state") || since6 || swap){
                double[] sham=arm.equals("swap-sham") && swap ? league.memory() : null;
                league.memory(swap?donor:ZERO);
                if(since6||swap)for(var f:recent)league.forward(f,scratch);
                if(sham!=null && !Arrays.equals(sham,league.memory()))throw new IllegalStateException("Sham changed hidden state");
            }
            var c=base.input(o);
            if(arm.equals("catalog") && heads==SWAP_AT-7)historicalState=league.memory();
            if(since6||arm.startsWith("swap")){
                if(recent.size()==6)recent.removeFirst();
                recent.addLast(neural.net.features());
            }
            boolean before=!Double.isFinite(o.lastOwnHurtTime())&&!Double.isFinite(o.lastTargetHurtTime());
            boolean nearHurt=o.time()-o.lastOwnHurtTime()<150;
            int key=(arm.equals("forward-sprint") || (arm.equals("forward-after") && !before))?2:c.key();
            if(arm.equals("auto-sprint"))key=DuelNetwork.key(DuelNetwork.withAutoSprint(key,o.sprint()));
            float side=arm.equals("no-strafe")?0:c.strafe();
            boolean jump=c.jump() && !arm.equals("no-jump") &&
                !(nearHurt && arm.equals("no-hurt-jump")) && !(!nearHurt && arm.equals("no-other-jump"));
            boolean click=c.click()||arm.equals("always-click")||(before && arm.equals("click-opening"))||
                (!before && arm.equals("click-after"));
            if(key!=c.key()||side!=c.strafe()||jump!=c.jump()||click!=c.click()){
                c=new PeriodicComboClock.Control(key,side,click,jump);
                neural.net.applied(DuelNetwork.pack(key,(int)Math.signum(side),click,jump));
            }
            for(var bin:before?List.of(counts,opening):List.of(counts)){
                bin[0]++;bin[1+key]++;if(side!=0)bin[5]++;if(click)bin[6]++;
                if(jump)bin[7]++;if(o.own().onGround())bin[8]++;
                if(o.own().onGround() && o.time()-o.lastOwnHurtTime()<150){bin[9]++;if(jump)bin[10]++;}
            }
            if(detail)samples.add(List.of(o.time(),o.own().x(),o.own().y(),o.own().z(),
                o.nearest(),key,(int)Math.signum(side),click,jump,o.own().onGround()));
            return c;
        }
    }

    static final Map<String,Arena.Fighter> field=new HashMap<>();
    static Arena.Fighter fighter(String key)throws Exception{
        if(!field.containsKey(key))field.put(key,LeagueWorker.fighter(key));
        return field.get(key);
    }
    static Map<String,Object> game(String[] row, String arm, double[] donor, boolean detail)throws Exception{
        boolean seat=row[3].equals("a");
        int a=Integer.parseInt(row[4]),b=Integer.parseInt(row[5]);
        var e=new DuelEnv(LeagueWorker.clock(row[2]),seat?a:b,seat?b:a);
        var own=new Watched(LeagueWorker.player(fighter(row[0]),a,b,e,Long.parseLong(row[6])),arm,detail);
        var foe=new Watched(LeagueWorker.player(fighter(row[1]),b,a,e,Long.parseLong(row[7])),"intact",detail);
        own.donor=donor;
        var g=e.new Game(seat,own,foe);
        var fresh=new ArrayList<Object>();
        g.trace();
        g.clock.setAudit((t,event,state)->{
            int old=g.hits.list.size();g.hits.event(t,event,state);
            for(int j=old;j<g.hits.list.size();j++){
                var hit=g.hits.list.get(j);if(hit.upgrade())continue;
                var kb=hit.sideA()?state.lastFreshB:state.lastFreshA;
                if(kb==null || kb.time()!=hit.at())throw new IllegalStateException("Fresh velocity association");
                fresh.add(List.of(t,hit.sideA()==seat,kb.attackerSprint(),kb.serverSeparation(),Math.hypot(kb.x(),kb.z()),kb.y()));
            }
        });
        int[] press=new int[2],contact=new int[2];long[] last={-1,-1},min={Long.MAX_VALUE,Long.MAX_VALUE};
        g.clock.setClickAudit(ev->{
            int ix=ev.fromA()==seat?0:1;
            if(ev.action().equals("ADMIT")){
                if(last[ix]>=0)min[ix]=Math.min(min[ix],ev.admittedNanos()-last[ix]);
                last[ix]=ev.admittedNanos();press[ix]++;
            }else if(ev.action().equals("CONSUME")&&ev.contact())contact[ix]++;
        });
        g.finish();
        if(Math.min(min[0],min[1])<LeagueWorker.INTERVAL)throw new IllegalStateException("CPS cap exceeded");
        Map<String,Object> out=new LinkedHashMap<>();out.put("outcome",g.reward());out.put("end",(double)(float)g.clock.time());
        out.put("cut",own.heads>=SWAP_AT);
        if(arm.equals("catalog")){out.put("donor",own.historicalState);return out;}
        if(arm.equals("intact")||arm.equals("auto-sprint")||detail){
            out.put("fresh",fresh);out.put("counts",List.of(own.counts,foe.counts));out.put("opening",List.of(own.opening,foe.opening));
            out.put("press",press);out.put("contacts",contact);out.put("min_ns",min);
        }
        if(detail)out.put("samples",List.of(own.samples,foe.samples));
        return out;
    }
    public static void main(String[] args)throws Exception{
        LeagueWorker.load();
        try(var input=Files.newBufferedReader(Path.of(args[0]));var output=Files.newBufferedWriter(Path.of(args[1]))){
            String s;while((s=input.readLine())!=null){
                String[] row=s.split("\t");
                double[] donor=row.length>10?Arrays.stream(row[10].split(",")).mapToDouble(Double::parseDouble).toArray():null;
                var result=game(row,row[8],donor,row[9].equals("detail"));
                output.write(Json.encode(result));output.newLine();
            }
        }
    }
}
