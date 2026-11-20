import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Offline first-travel velocity intervention on a fixed, already measured click schedule. */
final class BoxingOpeningRecoilSwap {
    private static final double END = 3100;

    private static final class Victim extends DuelEnv.Player {
        final double factor;
        DuelEnv.Game game;
        PeriodicComboClock.PacketObservation velocity;
        List<Number> step = List.of();

        Victim(int rtt,double factor) {
            super("wtap",0,rtt,0);
            this.factor = factor;
        }

        @Override public void received(PeriodicComboClock.PacketObservation p) {
            if (p.kind() == PeriodicComboClock.ReceivedKind.OWN_VELOCITY) velocity = p;
            super.received(p);
        }

        @Override public PeriodicComboClock.Control movement(PeriodicComboClock.Control issued) {
            if (velocity == null || !step.isEmpty()) return issued;
            var s = game.clock.state;
            var body = game.first ? s.b : s.a;
            var hit = game.first ? s.lastFreshB : s.lastFreshA;
            var q = body.q;
            if (hit != null && velocity.handledAt() >= hit.time() && velocity.vx() == hit.x() &&
                    velocity.vy() == hit.y() && velocity.vz() == hit.z() && q.vy() == hit.y()) {
                double observed = Math.hypot(q.vx(),q.vz());
                if (Double.isFinite(factor)) body.q = PeriodicComboFightModel.motion(q,factor*hit.x(),q.vy(),factor*hit.z());
                step = List.of(hit.time(),velocity.handledAt(),game.clock.time(),Math.hypot(hit.x(),hit.z()),
                        observed,Math.hypot(body.q.vx(),body.q.vz()));
            }
            velocity = null;
            return issued;
        }
    }

    private static Map<String,Object> run(DuelEnv env,boolean first,double admission,int phase,int hold,String version) {
        var mine = new DuelEnv.Player("wtap",0,env.rtt,0) {
            @Override public boolean allowInjection(PeriodicComboClock.Observation o,String source) {
                return o.time() < admission || o.time() >= admission + hold;
            }
        };
        var target = new Victim(env.otherRtt,Double.parseDouble(version));
        var g = env.new Game(first,mine,target);
        target.game = g;
        if (phase != 0) g.clock.shiftFutureClientOpportunities(!first,phase);
        g.trace();
        var presses=List.of(new ArrayList<Long>(),new ArrayList<Long>());
        var contacts=new ArrayList<List<Object>>();
        g.clock.setClickAudit(e->{
            if(e.action().equals("ADMIT")) presses.get(e.fromA()==first?0:1).add(e.admittedNanos());
            if(!e.action().equals("CONSUME") || e.time()>1200) return;
            var s=g.clock.state;
            var local=e.fromA()?s.a:s.b;
            var view=e.fromA()?s.ab:s.ba;
            double ray=PeriodicComboFightModel.ray(local,view);
            double ready=e.contact()?s.packets.get(s.packets.size()-1).ready:-1;
            contacts.add(List.of(e.time(),e.fromA()==first,e.eligible(),e.contact(),
                    PeriodicComboFightModel.nearest(local.q,view),Double.isFinite(ray)?ray:-1,ready));
        });
        g.clock.runUntil(Math.nextDown(END));
        if (target.step.isEmpty()) throw new IllegalStateException("Missing first recoil step");
        var s=g.clock.state;
        return Map.of("hold",hold,"factor",version,"step",target.step,"presses",presses,"contacts",contacts,
                "score",first?List.of(s.acceptedA,s.acceptedB):List.of(s.acceptedB,s.acceptedA),
                "hits",g.hits.list.stream().map(h->List.of(h.at(),h.sideA()==first,h.upgrade())).toList());
    }

    public static void main(String[] arguments) throws Exception {
        int rtt=Integer.parseInt(arguments[0]);
        var root=Path.of(arguments[1]);
        var env=BoxingEdgeHitSelect.environment(Path.of(arguments[2]),rtt,180_000,root.resolve("wire"),root.resolve("seeds"));
        BoxingMirrorForensics.loadPacketModel(env.initial.state,Path.of(arguments[3]),"raw-queue");
        boolean first=arguments[4].equals("a");
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=5;i<arguments.length;i++) {
            var input=arguments[i].split(",");
            int phase=Integer.parseInt(input[0]);
            double admission=Double.parseDouble(input[1]);
            for(int hold:new int[]{0,4}) for(String v:List.of("NaN",".6","1"))
                rows.add(Map.of("phase",phase,"arm",run(env,first,admission,phase,hold,v)));
        }
        System.out.println(Json.encode(rows));
    }
}
