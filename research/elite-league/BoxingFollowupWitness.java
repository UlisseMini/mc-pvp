import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.BoxingApproachPoll;
import lab.Json;

/** Swap the first incoming horizontal impulse in the two m7407 timing branches. */
final class BoxingFollowupWitness {
    private static final double ANCHOR = 295.581916;
    private static final double END = ANCHOR + 1800;

    private record Arm(int upload, double replaceWith) {}
    private record Prefix(DuelEnv.Game game, List<Object> impulse) {}

    private static Prefix starting(DuelEnv env, Arm arm) {
        var subject=new BoxingApproachPollCompare.Fighter(env.rtt,BoxingApproachPoll.Mode.OBSERVE,null);
        var initial=env.new Game(true,subject,Evolve.WT.create(env.otherRtt,0));
        if (!initial.clock.advanceToBeforeNextHead(true,ANCHOR)) throw new IllegalStateException("Missing approach");
        while (initial.clock.time()<ANCHOR && initial.clock.runHeadInterval(true,ANCHOR)) { }
        if (initial.clock.time()!=ANCHOR || Math.abs(initial.clock.observation(true).nearest()-3.8351178062321196)>1e-8)
            throw new IllegalStateException("The earlier approach state changed");
        var movement=new BoxingApproachFootwork.Fighter(subject,0,"bbbs",true);
        var game=env.new Game(initial,movement,initial.b.copy());
        BoxingJumpTimingAudit.shiftAttackTiming(game.clock.state,0,arm.upload,ANCHOR,END);
        game.trace();
        var input=new ArrayList<Object>();
        game.clock.setAudit((at,event,state) -> {
            game.hits.event(at,event,state);
            if (!event.equals("SERVER_DRAIN") || !input.isEmpty() || state.lastFreshA==null) return;
            var incoming=state.lastFreshA;
            if (incoming.time()!=at || incoming.time()!=449.886417)
                throw new IllegalStateException("Original attack drain was not reproduced");
            double strength=Math.hypot(incoming.x(),incoming.z());
            var packet=state.packets.stream().filter(p->p.kind==8 && p.vx==incoming.x() && p.vz==incoming.z() && p.vy==incoming.y())
                    .max(java.util.Comparator.comparingLong(p->p.sequence)).orElseThrow();
            if (arm.replaceWith>0) {
                double factor=arm.replaceWith/strength;
                packet.vx=Math.rint(packet.vx*factor*8000)/8000;
                packet.vz=Math.rint(packet.vz*factor*8000)/8000;
            }
            input.addAll(List.of(at,strength,Math.hypot(packet.vx,packet.vz),incoming.y(),incoming.serverSeparation()));
        });
        return new Prefix(game,input);
    }

    private static List<Integer> points(List<DuelEnv.Hit> hits) {
        int a=0,b=0;
        for (var hit:hits) if (hit.sideA()) a++; else b++;
        return List.of(a,b);
    }

    private static Map<String,Object> inspect(DuelEnv env, Arm arm) {
        var started=starting(env,arm);
        var game=started.game;
        var stages=new LinkedHashMap<String,Object>();
        var pending=new ArrayList<>(BoxingTradeFollowup.STAGES);
        if (game.clock.advanceToBeforeNextHead(true,1320)) do {
            var own=game.clock.observation(true);
            if (own.lastOwnHurtTime()<ANCHOR || own.lastTargetHurtTime()<ANCHOR) continue;
            int stage=pending.get(0);
            if (own.time()-Math.max(own.lastOwnHurtTime(),own.lastTargetHurtTime())<stage) continue;
            if (((BoxingApproachFootwork.Fighter)game.our()).movement.size()!=4)
                throw new IllegalStateException("Follow-up starts before preparatory footwork ended");
            var prefix=points(game.hits.list);
            var choices=new LinkedHashMap<String,Object>();
            var src=(BoxingApproachFootwork.Fighter)game.our();
            double remain=END-own.time();
            for (String word:List.of("", "s", "ss", "r", "rr", "b", "bb", "ll", "dd")) {
                var choice=new BoxingApproachFootwork.Fighter(src,0,word,true);
                var battle=BoxingApproachFootwork.replay(env,game,choice,own.time(),remain);
                var end=points(battle.hits());
                choices.put(word.isEmpty()?"wtap":word,Map.of("points",List.of(prefix.get(0)+end.get(0),prefix.get(1)+end.get(1)),
                        "hits",battle.hits().stream().map(h->List.of(h.at(),h.sideA(),h.upgrade())).toList()));
            }
            stages.put(Integer.toString(stage),Map.of("at",own.time(),"gap",own.nearest(),"past",prefix,
                    "velocity",List.of(own.own().vx(),own.own().vy(),own.own().vz()),"choices",choices));
            pending.remove(0);
            if (pending.isEmpty()) break;
        } while (game.clock.runHeadInterval(true,1320));
        game.clock.runUntil(Math.nextDown(END));
        return Map.of("upload",arm.upload,"replacement",arm.replaceWith,"points",points(game.hits.list),
                "first_impulse",started.impulse,"hits",game.hits.list.stream().map(h->List.of(h.at(),h.sideA(),h.upgrade())).toList(),"stages",stages);
    }

    public static void main(String[] args) throws Exception {
        var env=BoxingEdgeHitSelect.environment(Path.of(args[0]),20,180_000,Path.of(args[1]),Path.of(args[2]));
        for (var a:List.of(new Arm(0,0),new Arm(5,0),new Arm(0,.8935),new Arm(5,.93875)))
            System.out.println(Json.encode(inspect(env,a)));
    }
}
