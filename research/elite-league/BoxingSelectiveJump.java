import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.BoxingSecondHurtJump;
import lab.Json;

/** A frozen own-client defensive jump versus plain W-tap on the same clocks. */
final class BoxingSelectiveJump {
    private static final double ACTIVE_GAP = 1100;
    private enum Arm {
        WTAP(BoxingSecondHurtJump.Mode.OBSERVE),
        VISIBLE(BoxingSecondHurtJump.Mode.VISIBLE),
        GUARDED(BoxingSecondHurtJump.Mode.GUARDED);
        final BoxingSecondHurtJump.Mode mode;
        Arm(BoxingSecondHurtJump.Mode mode) { this.mode = mode; }
    }

    private record Guard(double replyBuffer, double serviceDelay) {}

    private static final class Fighter extends DuelEnv.Player {
        final BoxingSecondHurtJump jump;
        final List<Double> jumpTimes = new ArrayList<>();
        final List<Double> skippedTimes = new ArrayList<>();
        long contactsSeen;
        int opportunities, suppressed;
        boolean impact;

        Fighter(int rtt, Arm arm, Guard guard) {
            super("wtap", 0, rtt, 0);
            jump = new BoxingSecondHurtJump(arm.mode,guard.replyBuffer,guard.serviceDelay);
        }

        Fighter(Fighter previous, Arm arm) {
            super("wtap",0,previous.rtt,0);
            jump = new BoxingSecondHurtJump(previous.jump,arm.mode);
            handled = previous.handled;
            releaseUntil = previous.releaseUntil;
            firstClick = previous.firstClick;
            impact = previous.impact;
            contactsSeen = previous.contactsSeen;
        }

        @Override public void received(PeriodicComboClock.PacketObservation p) {
            if (p.kind() == PeriodicComboClock.ReceivedKind.OWN_HURT) {
                jump.ownHurt(p.handledAt());
            } else if (p.kind() == PeriodicComboClock.ReceivedKind.TARGET_HURT) {
                jump.targetHurt();
            } else if (p.kind() == PeriodicComboClock.ReceivedKind.OWN_VELOCITY) {
                impact = p.vx() != 0 || p.vz() != 0;
            }
        }

        @Override public void consumed(PeriodicComboClock.Observation o, long admissionId) {
            if (o.localContacts() > contactsSeen) jump.contact(o.lastContactTime());
            contactsSeen = o.localContacts();
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var usual = super.input(o);
            var decision = jump.decide(o.time(),impact,o.own().onGround(),rtt);
            impact = false;
            if (decision == BoxingSecondHurtJump.Decision.NONE) return usual;
            opportunities++;
            if (decision == BoxingSecondHurtJump.Decision.SUPPRESSED) {
                suppressed++;
                skippedTimes.add(o.time());
            }
            if (decision != BoxingSecondHurtJump.Decision.JUMP) return usual;
            jumps++;
            jumpTimes.add(o.time());
            return new PeriodicComboClock.Control(usual.key(),usual.strafe(),usual.click(),true);
        }
    }

    private record Regulation(Map<String,Object> score, List<List<Long>> presses, double end,
            List<Double> jumpTimes, List<Double> skippedTimes, List<DuelEnv.Hit> hits) {
        List<List<Long>> before(double cut) {
            return presses.stream().map(p -> p.stream().takeWhile(t -> t/1e6 <= cut).toList()).toList();
        }
    }

    private static void phase(DuelEnv.Game game, int own, int opponent) {
        if (own != 0) game.clock.shiftFutureClientOpportunities(game.first,own);
        if (opponent != 0) game.clock.shiftFutureClientOpportunities(!game.first,opponent);
    }

    private static Regulation regulation(DuelEnv env, boolean first, Arm arm, Guard guard,
                                         int ownPhase, int opponentPhase) {
        var player = new Fighter(env.rtt,arm,guard);
        var game = env.new Game(first,player,Evolve.WT.create(env.rtt,0));
        phase(game,ownPhase,opponentPhase);
        game.trace();
        var presses = List.of(new ArrayList<Long>(),new ArrayList<Long>());
        game.clock.setClickAudit(e -> {
            if (!e.action().equals("ADMIT")) return;
            var p = presses.get(e.fromA() == first ? 0 : 1);
            if (!p.isEmpty() && e.admittedNanos()-p.get(p.size()-1) < 50_000_000L)
                throw new IllegalStateException("The physical cap changed");
            p.add(e.admittedNanos());
        });
        var score = BoxingDoubleSearch.play(game,100);
        double end = BoxingEdgeHitSelect.decidingHit(game);
        double start = presses.stream().mapToDouble(p -> p.isEmpty() ? end : p.get(0)/1e6).max().orElseThrow();
        var cps = presses.stream().map(p -> 1000.0*p.stream()
                .filter(t -> t/1e6 >= start && t/1e6 <= end).count()/(end-start)).toList();
        return new Regulation(Map.of("score",score.values(),"cps",cps,"jumps",player.jumps,
                "opportunities",player.opportunities,"suppressed",player.suppressed),
                presses.stream().map(List::copyOf).toList(),end,
                List.copyOf(player.jumpTimes),List.copyOf(player.skippedTimes),List.copyOf(game.hits.list));
    }

    private static int margin(List<DuelEnv.Hit> hits, boolean first, double from, double until) {
        return hits.stream().filter(h -> h.at() >= from && h.at() <= until)
                .mapToInt(h -> h.sideA() == first ? 1 : -1).sum();
    }

    private static Map<String,Object> firstSkip(Regulation visible, Regulation guarded, boolean first, double common) {
        if (guarded.skippedTimes.isEmpty() || guarded.skippedTimes.get(0) >= common) return Map.of();
        double at = guarded.skippedTimes.get(0);
        if (!visible.jumpTimes.contains(at))
            throw new IllegalStateException("First guard skip was not the matching jump in the visible arm");
        var prefix = guarded.hits.stream().takeWhile(h -> h.at() < at).toList();
        if (!prefix.equals(visible.hits.stream().takeWhile(h -> h.at() < at).toList()))
            throw new IllegalStateException("Hit records diverged before the guard's first decision change");
        boolean replyAfterIncoming = false;
        for (var h : prefix) {
            if (h.sideA() == first) replyAfterIncoming = true;
            else if (!h.upgrade()) replyAfterIncoming = false;
        }
        double end = Math.min(common,at+1700);
        return Map.of("at",at,"replyAlreadyScored",replyAfterIncoming,"fullWindow",end == at+1700,
                "visibleMargin",margin(visible.hits,first,at,end),
                "guardedMargin",margin(guarded.hits,first,at,end));
    }

    private static Map<String,Object> suffix(DuelEnv env, DuelEnv.Game prefix, Fighter history,
            Arm arm, double anchor, List<DuelEnv.Hit> queued) {
        var player = new Fighter(history,arm);
        var game = env.new Game(prefix,prefix.first ? player : prefix.a.copy(),prefix.first ? prefix.b.copy() : player);
        game.trace();
        game.hits.list.addAll(queued);
        while (game.hits.list.stream().noneMatch(h -> h.sideA() != game.first) && game.advance()) {
            // A previous return ends the maintenance episode even if a jump is now available.
        }
        return Map.of("maintenance",BoxingLateReturns.score(game,anchor,ACTIVE_GAP).values(),"jumps",player.jumps);
    }

    private static List<Map<String,Object>> maintenance(DuelEnv env, boolean first, Guard guard,
            int ownPhase, int opponentPhase) {
        var player = new Fighter(env.rtt,Arm.WTAP,guard);
        var game = env.new Game(first,player,Evolve.WT.create(env.rtt,2000));
        game.trace();
        phase(game,ownPhase,opponentPhase);
        int seen = 0, streak = 0;
        var enrolled = new HashSet<Integer>();
        var out = new ArrayList<Map<String,Object>>();
        while (game.advance()) {
            double wake = Math.max(game.a.firstClick,game.b.firstClick);
            var hits = game.hits.list;
            for (; seen < hits.size(); seen++) {
                var h = hits.get(seen);
                if (!Double.isFinite(wake) || h.at() < wake) continue;
                if (h.sideA() != first) {
                    streak = 0;
                    continue;
                }
                if (h.upgrade()) continue;
                streak++;
                if ((streak != 3 && streak != 5 && streak != 10) || !enrolled.add(streak)) continue;
                var pending = List.copyOf(hits.subList(seen+1,hits.size()));
                var arms = new EnumMap<Arm,Map<String,Object>>(Arm.class);
                for (var a : Arm.values()) arms.put(a,suffix(env,game,player,a,h.at(),pending));
                out.add(Map.of("k",streak,"anchor",h.at(),"arms",arms));
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        int rtt = Integer.parseInt(args[0]);
        int ownPhase = Integer.parseInt(args[1]), opponentPhase = Integer.parseInt(args[2]);
        var guard = new Guard(Double.parseDouble(args[3]),Double.parseDouble(args[4]));
        var wire = Path.of(args[5]);
        var drillWire = Path.of(args[6]);
        var seeds = Path.of(args[7]);
        for (int i=8;i<args.length;i++) {
            var tape = Path.of(args[i]);
            var env = BoxingEdgeHitSelect.environment(tape,rtt,180_000,wire,seeds);
            var drill = BoxingEdgeHitSelect.environment(tape,rtt,20_000,drillWire,seeds);
            for (boolean first : new boolean[]{true,false}) {
                var arms = new EnumMap<Arm,Regulation>(Arm.class);
                for (var arm : Arm.values()) arms.put(arm,regulation(env,first,arm,guard,ownPhase,opponentPhase));
                double common = arms.values().stream().mapToDouble(Regulation::end).min().orElseThrow();
                var before = arms.get(Arm.WTAP).before(common);
                boolean samePresses = arms.values().stream().allMatch(a -> a.before(common).equals(before));
                var scores = new LinkedHashMap<String,Object>();
                arms.forEach((a,r) -> scores.put(a.name(),r.score));
                var firstSkip = firstSkip(arms.get(Arm.VISIBLE),arms.get(Arm.GUARDED),first,common);
                System.out.println(Json.encode(Map.of("clock",env.clockId,"rtt",rtt,"seat",first ? "a" : "b",
                        "phase",List.of(ownPhase,opponentPhase),"regulation",scores,"samePresses",samePresses,
                        "firstSkip",firstSkip,
                        "maintenance",maintenance(drill,first,guard,ownPhase,opponentPhase))));
            }
        }
    }
}
