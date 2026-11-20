import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Retime one ordinary mouse press, with complete native cap and travel state. */
final class BoxingReentryClickPhase {
    private static final double END = 3100;
    private static final double LAST_OPENING = 950;
    private static final int[] DELAYS = {0,4,8,12,16,20,24,32,40,48,60};
    private static final boolean FIT_FROM_SPAWN=Boolean.getBoolean("lab.reentryClickPhase.fitFromSpawn");

    private static final class Scout extends BoxingLandingReentry.Scout {
        final double firstAdmission;
        final int delay;
        double deferred = -1, sent = -1;

        Scout(int rtt,double firstAdmission,int delay) {
            super(rtt);
            this.firstAdmission=firstAdmission;
            this.delay=delay;
        }

        @Override public boolean allowInjection(PeriodicComboClock.Observation view,String source,
                                                 java.util.function.BooleanSupplier contact) {
            if (sent<0 && view.time()>=firstAdmission-1e-7) {
                if (deferred<0) deferred=view.time();
                if (view.time()<deferred+delay) return false;
            }
            return super.allowInjection(view,source,contact);
        }

        @Override public boolean injected(PeriodicComboClock.Observation view,double at) {
            if (sent<0 && at>=firstAdmission-1e-7) sent=at;
            return super.injected(view,at);
        }
    }

    private static final class Defender extends DuelEnv.Player {
        DuelEnv.Game game;
        PeriodicComboClock.PacketObservation velocity;
        final List<List<Number>> steps=new ArrayList<>();
        Defender(int rtt) {super("wtap",0,rtt,0);}

        @Override public void received(PeriodicComboClock.PacketObservation packet) {
            if (packet.kind()==PeriodicComboClock.ReceivedKind.OWN_VELOCITY) velocity=packet;
            super.received(packet);
        }

        @Override public PeriodicComboClock.Control movement(PeriodicComboClock.Control issued) {
            if (velocity==null) return issued;
            var s=game.clock.state;
            var impact=game.first?s.lastFreshB:s.lastFreshA;
            var body=(game.first?s.b:s.a).q;
            if (impact!=null && velocity.handledAt()>=impact.time() && velocity.vx()==impact.x()
                    && velocity.vy()==impact.y() && velocity.vz()==impact.z() && body.vy()==impact.y()) {
                double impulse=Math.hypot(impact.x(),impact.z());
                if (impulse>0) steps.add(List.of(impact.time(),velocity.handledAt(),game.clock.time(),impulse,
                        Math.hypot(body.vx(),body.vz())/impulse));
            }
            velocity=null;
            return issued;
        }
    }

    private static void phase(DuelEnv.Game game,int shift) {
        if (shift!=0) game.clock.shiftFutureClientOpportunities(!game.first,shift);
    }

    private record Opening(double admission, List<List<Object>> polls) {}

    private static Opening firstContact(DuelEnv env,boolean first,int shift) {
        var polls=new ArrayList<List<Object>>();
        var scout=new BoxingLandingReentry.Scout(env.rtt) {
            @Override public boolean allowInjection(PeriodicComboClock.Observation own,String source,
                                                     java.util.function.BooleanSupplier contact) {
                polls.add(List.of(own.time(),source,contact.getAsBoolean(),own.nearest(),own.own().onGround(),
                        own.sprint(),Double.isFinite(own.lastOwnHurtTime()),Double.isFinite(own.lastTargetHurtTime())));
                return super.allowInjection(own,source,contact);
            }
        };
        var g=env.new Game(first,scout,Evolve.WT.create(env.otherRtt,0));
        phase(g,shift);
        long[] press={-1};
        g.clock.setClickAudit(e->{
            if (press[0]<0 && e.fromA()==first && e.action().equals("CONSUME") && e.contact()) press[0]=e.admittedNanos();
        });
        g.clock.runUntil(LAST_OPENING);
        return new Opening(press[0]/1e6,polls);
    }

    private static Map<String,Object> run(DuelEnv env,Path fit,boolean first,int shift,double press,int delay) throws Exception {
        var own=new Scout(env.rtt,press,delay);
        var other=new Defender(env.otherRtt);
        var g=env.new Game(first,own,other);
        other.game=g;
        phase(g,shift);
        g.trace();
        var counts=new int[2];
        var last=new long[]{Long.MIN_VALUE/2,Long.MIN_VALUE/2};
        g.clock.setClickAudit(e->{
            if (!e.action().equals("ADMIT") || e.time()>=END) return;
            int side=e.fromA()==first?0:1;
            if (e.admittedNanos()-last[side]<50_000_000) throw new AssertionError("Changed physical CPS cap");
            last[side]=e.admittedNanos();
            counts[side]++;
        });
        var row=new LinkedHashMap<String,Object>();
        row.put("delay",delay);
        var cut=earliestPair(g,own);
        if (cut!=null) {
            if (own.sent<0) throw new AssertionError("Re-entry encountered before the first altered admission resolved");
            row.put("at",cut.time());
            row.put("view",BoxingNaturalReentry.legal(own,cut));
            var pre=g.hits.list.size();
            if (!FIT_FROM_SPAWN) BoxingMirrorForensics.loadPacketModel(g.clock.state,fit,"raw-queue");
            var timing=new BoxingLandingReentry.Timing(0,0,0,0);
            var adjustment=BoxingLandingReentry.Adjustment.fromProperty();
            var stock=BoxingLandingReentry.fork(env,g,own,cut,BoxingLandingReentry.Start.WTAP,timing,false,null);
            var changed=BoxingLandingReentry.fork(env,g,own,cut,BoxingLandingReentry.Start.LANDING_PLUS_ONE,timing,false,adjustment);
            if (!stock.presses().equals(changed.presses())) throw new AssertionError("Matched suffix clicks changed");
            row.put("prefix",g.hits.list.subList(0,pre).stream().map(h->List.of(h.at(),h.sideA()==first,h.upgrade())).toList());
            for (var entry:Map.of("wtap",stock,"word",changed).entrySet()) {
                var tail=entry.getValue();
                var hits=tail.hits().stream().filter(h->h.at()<END).toList();
                var score=BoxingReentryProtection.score(hits,first,cut.time());
                int total=margin(g.hits.list,first)+score.margin();
                var actual=new ArrayList<Integer>();
                for (int i=0;i<2;i++) actual.add(counts[i]+(int)tail.presses().get(i).stream().filter(t->t<END*1e6).count());
                row.put(entry.getKey(),Map.of("score",score.values(),"to3100",total,"presses",actual,
                        "activation",tail.activation()));
            }
            // Late S12 delivery may place the first recoil after both hurt cues.
            // Continue the matched W-tap prefix only to measure that first step.
            g.clock.runUntil(cut.time()+200);
        } else {
            g.clock.runUntil(END);
            var scored=Map.of("to3100",margin(g.hits.list,first),"presses",List.of(counts[0],counts[1]));
            row.put("wtap",scored);
            row.put("word",scored);
        }
        row.put("sent",own.sent);
        row.put("firstDefenderStep",other.steps.isEmpty()?List.of():other.steps.get(0));
        return row;
    }

    private static PeriodicComboClock.Observation earliestPair(DuelEnv.Game game,Scout own) {
        if (game.clock.advanceToBeforeNextHead(game.first,LAST_OPENING)) do {
            var view=game.clock.observation(game.first);
            own.observe(view);
            if (own.episode==1) return view;
        } while (game.clock.runHeadInterval(game.first,LAST_OPENING));
        return null;
    }

    private static int margin(List<DuelEnv.Hit> hits,boolean first) {
        return hits.stream().filter(h->h.at()<END).mapToInt(h->h.sideA()==first?1:-1).sum();
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]);
        var env=BoxingEdgeHitSelect.environment(Path.of(args[1]),rtt,180_000,
                Path.of(args[2]).resolve("wire"),Path.of(args[2]).resolve("seeds"));
        var fit=Path.of(args[3]);
        if (FIT_FROM_SPAWN) BoxingMirrorForensics.loadPacketModel(env.initial.state,fit,"raw-queue");
        for (int i=4;i<args.length;i++) {
            var test=args[i].split(",");
            boolean first=test[0].equals("a");
            int shift=Integer.parseInt(test[1]);
            var opening=firstContact(env,first,shift);
            double press=opening.admission();
            var rows=new ArrayList<Map<String,Object>>();
            if (press>=0) for (int delay:DELAYS) rows.add(run(env,fit,first,shift,press,delay));
            System.out.println(Json.encode(Map.of("clock",env.clockId,"rtt",rtt,"seat",test[0],"phase",shift,
                    "contactPress",press,"rows",rows,"openingPolls",opening.polls())));
        }
    }
}
