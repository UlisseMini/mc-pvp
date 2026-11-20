import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.BoxingApproachPoll;
import lab.ExactTravel;
import lab.Json;

/** Fork the exact first hold and vary only ordinary movement during the reply. */
final class BoxingApproachFootwork {
    private static final double HORIZON = 1800;
    private static final String ONLY = System.getProperty("lab.footwork.arm", "");
    private static final boolean TRACE = Boolean.getBoolean("lab.footwork.trace");
    private static final int FUTURE_OTHER_PHASE = Integer.getInteger("lab.footwork.futureOtherPhase", 0);
    private static final int OWN_UPLOAD = Integer.getInteger("lab.footwork.ownUpload", 0);
    private static final int OTHER_UPLOAD = Integer.getInteger("lab.footwork.otherUpload", 0);
    private static final int[] DELAYS = {35,55,75};
    private static final String[] WORDS = {
            "", "r", "b", "w", "s", "l", "d", "rr", "rb", "rw", "rs", "br", "bb", "bw", "bs",
            "wr", "wb", "ww", "ws", "sl", "sd", "ls", "ds", "ll", "dd", "rrs", "bbs", "brr"
    };

    static class Fighter extends BoxingApproachPollCompare.Fighter {
        final String word;
        final boolean startImmediately;
        final ArrayList<Integer> movement = new ArrayList<>();
        int index;

        Fighter(BoxingApproachPollCompare.Fighter prior, int wait, String word, boolean startImmediately) {
            super(prior,wait==0?null:new BoxingApproachPollCompare.Wait(wait,true));
            this.word = word;
            this.startImmediately = startImmediately;
        }

        @Override public PeriodicComboClock.Control movement(PeriodicComboClock.Control stock) {
            if (index==word.length() || (!startImmediately && held.isEmpty())) return stock;
            char letter = word.charAt(index++);
            int key = switch (letter) {
                case 'r','a','e' -> PeriodicComboFightModel.R;
                case 'b' -> PeriodicComboFightModel.BACK;
                case 'w' -> PeriodicComboFightModel.W;
                case 's','l','d' -> PeriodicComboFightModel.S;
                default -> throw new IllegalArgumentException("Unknown movement key " + letter);
            };
            float strafe = switch (letter) {
                case 'l','a' -> .98f;
                case 'd','e' -> -.98f;
                default -> 0;
            };
            movement.add(key);
            return new PeriodicComboClock.Control(key,strafe,stock.click(),stock.jump());
        }
    }

    record Branch(List<DuelEnv.Hit> hits, List<Object> reply, List<Object> replyGeometry,
                          List<Integer> presses, List<Integer> movement, List<Double> holds,
                          List<List<Object>> timeline) {
        Map<String,Object> summary(boolean first) {
            int mine=0, other=0;
            int firstOwn=-1;
            double opponentFirst=-1;
            double returnTime=-1;
            double followTime=-1;
            int chain=0;
            double lastFresh=-1;
            boolean open=true;
            for (int i=0;i<hits.size();i++) {
                var h=hits.get(i);
                if (h.sideA()==first) {
                    mine++;
                    if (h.upgrade()) continue;
                    if (firstOwn<0) firstOwn=i;
                    else if (followTime<0) followTime=h.at();
                    if (open && (lastFresh<0 || h.at()-lastFresh<=1100)) {
                        chain++;
                        lastFresh=h.at();
                    }
                } else {
                    other++;
                    if (opponentFirst<0) opponentFirst=h.at();
                    if (firstOwn>=0 && open) { returnTime=h.at(); open=false; }
                }
            }
            double ownTime=firstOwn<0?-1:hits.get(firstOwn).at();
            boolean doubleHit=followTime>=0 && followTime-ownTime<=850 && (returnTime<0 || followTime<returnTime);
            boolean exclusiveDouble=doubleHit && hits.stream().noneMatch(h -> h.sideA()!=first && h.at()==ownTime);
            var summary = new LinkedHashMap<>(Map.of("points",List.of(mine,other),"first",ownTime,"follow",followTime,
                    "first_return",returnTime,"double",doubleHit,"reply",reply,"presses",presses,
                    "reply_geometry",replyGeometry,"movement",movement,"holds",holds));
            summary.put("exclusive_double",exclusiveDouble);
            summary.put("exclusive_chain",hits.stream().anyMatch(h -> h.sideA()!=first && h.at()==ownTime) ? 0 :
                    (returnTime>=0 && returnTime==lastFresh ? chain-1 : chain));
            summary.put("opponent_first",opponentFirst);
            if (TRACE) summary.put("timeline",timeline);
            return summary;
        }
    }

    private static Branch branch(DuelEnv env, DuelEnv.Game prefix, BoxingApproachPollCompare.Fighter subject,
                                 double anchor, int delay, String word) {
        var branch = replay(env,prefix,new Fighter(subject,delay,word,false),anchor,HORIZON);
        if (delay!=0 && !branch.holds.equals(List.of(anchor)))
            throw new IllegalStateException("First-hold arm and shared prefix disagree at " + anchor + " / " + branch.holds);
        return branch;
    }

    static Branch replay(DuelEnv env, DuelEnv.Game prefix, DuelEnv.Player own, double anchor, double horizon) {
        return replay(env,prefix,own,anchor,horizon,null);
    }

    /** Offline diagnostic on the first movement after the defender receives melee velocity. */
    static final class FirstRecoil extends DuelEnv.Player {
        final double scale;
        final double advance;
        final boolean traceReturns;
        final List<List<Object>> returns = new ArrayList<>();
        PeriodicComboClock clock;
        boolean first;
        PeriodicComboClock.PacketObservation impulse;
        boolean unresolved;
        double previousScale = Double.NaN;
        double beforeMove = Double.NaN;
        double x,z,step = Double.NaN;
        double nearest = Double.NaN;

        FirstRecoil(DuelEnv.Player stock, double scale) {
            this(stock,scale,0,false);
        }

        FirstRecoil(DuelEnv.Player stock, double scale, double advance, boolean traceReturns) {
            super(stock.style,stock.mask,stock.rtt,stock.wake);
            if (stock.getClass()!=DuelEnv.Player.class || !stock.style.equals("wtap") || stock.mask!=0)
                throw new IllegalArgumentException("The recoil comparison uses plain W-tap");
            handled=stock.handled;
            releaseUntil=stock.releaseUntil;
            firstClick=stock.firstClick;
            this.scale=scale;
            this.advance=advance;
            this.traceReturns=traceReturns;
        }

        @Override public void received(PeriodicComboClock.PacketObservation packet) {
            super.received(packet);
            if (packet.kind() != PeriodicComboClock.ReceivedKind.OWN_VELOCITY) return;
            if (impulse == null) impulse=packet;
            else if (!Double.isFinite(beforeMove)) unresolved=true;
        }

        @Override public PeriodicComboClock.Control movement(PeriodicComboClock.Control issued) {
            if (impulse == null || unresolved || Double.isFinite(beforeMove)) return issued;
            var s=clock.state;
            var victim=first?s.b:s.a;
            var fresh=first?s.lastFreshB:s.lastFreshA;
            var q=victim.q;
            if (fresh == null || impulse.vx()!=fresh.x() || impulse.vy()!=fresh.y() || impulse.vz()!=fresh.z()
                    || impulse.handledAt()<fresh.time() || q.vy()!=impulse.vy()) {
                unresolved=true;
                return issued;
            }
            for (double f : new double[]{1,.6,.36}) {
                if (q.vx()==scaled(impulse.vx(),f) && q.vz()==scaled(impulse.vz(),f)) {
                    previousScale=f;
                    break;
                }
            }
            if (!Double.isFinite(previousScale)) {
                unresolved=true;
                return issued;
            }
            beforeMove=clock.time();
            x=q.x();
            z=q.z();
            var view=first?s.ba:s.ab;
            nearest=PeriodicComboFightModel.nearest(q,view);
            if (scale>0 && previousScale!=scale)
                victim.q = new ExactTravel.State(q.x(),q.y(),q.z(),impulse.vx()*scale,q.vy(),impulse.vz()*scale,
                        q.box(),q.onGround(),q.isCollidedHorizontally(),q.isCollidedVertically(),
                        q.isCollided(),q.noClip(),q.isInWeb(),q.sneaking());
            if (advance>0) {
                int route=first?1:0;
                s.packetTiming=new EarlierAttack(s.packetTiming,route,advance,s.queueReadyWatermarks[route]);
            }
            return issued;
        }

        private static double scaled(double v, double f) { return f==.36 ? v*.6*.6 : v*f; }

        void event(double at, String event, PeriodicComboFightModel.State s) {
            if (traceReturns && event.equals("SERVER_DRAIN")) returns.add(List.of("drain",at,s.serverTicks));
            if (at==beforeMove && event.equals(first?"MOVEB":"MOVEA")) {
                var q=(first?s.b:s.a).q;
                step=Math.hypot(q.x()-x,q.z()-z);
            }
        }

        void click(PeriodicComboClock.ClickEvent e, PeriodicComboFightModel.State s) {
            if (!traceReturns || !e.action().equals("CONSUME") || e.fromA()==first) return;
            var body=first?s.b:s.a;
            var view=first?s.ba:s.ab;
            int kind=first?7:3;
            PeriodicComboFightModel.Packet packet;
            if (e.contact()) {
                packet=s.packets.get(s.packets.size()-1);
                if (packet.kind!=kind || packet.sequence!=s.seq-1)
                    throw new IllegalStateException("Missing contact packet");
            } else {
                var copy=s.deepCopy();
                int route=first?1:0;
                copy.add(kind,e.time()+s.nominalHalfRtt[route],0,0,0,0,false);
                packet=copy.packets.get(copy.packets.size()-1);
            }
            double ray=PeriodicComboFightModel.ray(body,view);
            returns.add(List.of("click",e.time(),e.eligible(),e.contact(),PeriodicComboFightModel.nearest(body.q,view),
                    Double.isFinite(ray)?ray:-1.,packet.ready,e.contact()?packet.sequence:-1L));
        }

        void hit(DuelEnv.Hit hit, PeriodicComboFightModel.State s) {
            if (!traceReturns) return;
            long packet = hit.upgrade() ? (hit.sideA()?s.lastAcceptedPacketA:s.lastAcceptedPacketB)
                    : (hit.sideA()?s.lastFreshPacketA:s.lastFreshPacketB);
            returns.add(List.of("hit",hit.at(),hit.sideA()==first,hit.upgrade(),s.serverTicks,packet));
        }

        Map<String,Object> result(double cut) {
            if (!Double.isFinite(step)) return Map.of("status",unresolved?"unresolved":"no first step");
            return Map.of("status","measured","natural_factor",previousScale,"applied_factor",scale>0?scale:previousScale,
                    "receipt",impulse.handledAt()-cut,"move",beforeMove-cut,"range",nearest,"horizontal_step",step,
                    "horizontal_impulse",Math.hypot(impulse.vx(),impulse.vz()));
        }
    }

    /** Move only new defender attacks earlier, respecting send time and route FIFO. */
    private static final class EarlierAttack implements PeriodicComboFightModel.PacketTiming {
        final PeriodicComboFightModel.PacketTiming wire;
        final int defender;
        final double advance;
        double previousReceipt;

        EarlierAttack(PeriodicComboFightModel.PacketTiming wire, int defender, double advance, double previousReceipt) {
            this.wire=wire;
            this.defender=defender;
            this.advance=advance;
            this.previousReceipt=previousReceipt;
        }

        @Override public double arrival(int route, double send, double half) {
            return wire.arrival(route,send,half);
        }

        @Override public PeriodicComboFightModel.PacketTimingPlan planForPacket(int route, double send, double half,
                PeriodicComboFightModel.TimingSemantic semantic, int packetId) {
            var plan=wire.planForPacket(route,send,half,semantic,packetId);
            if (route!=defender) return plan;
            double raw=plan.rawReceiptMs();
            if (raw<previousReceipt || plan.readyMs()!=null)
                throw new IllegalStateException("Attack advance requires a FIFO wire-only uplink");
            previousReceipt=packetId==2?Math.max(Math.max(send,previousReceipt),raw-advance):raw;
            return packetId==2?new PeriodicComboFightModel.PacketTimingPlan(previousReceipt,null,null):plan;
        }

        @Override public EarlierAttack deepCopy() {
            return new EarlierAttack(wire.deepCopy(),defender,advance,previousReceipt);
        }
    }

    static Branch replay(DuelEnv env, DuelEnv.Game prefix, DuelEnv.Player own, double anchor, double horizon,
                         FirstRecoil probe) {
        var stock = (prefix.first?prefix.b:prefix.a).copy();
        if (probe != null) stock=probe;
        var game = env.new Game(prefix,prefix.first?own:stock,prefix.first?stock:own);
        if (probe != null) {
            probe.clock=game.clock;
            probe.first=prefix.first;
        }
        if (FUTURE_OTHER_PHASE!=0) game.clock.shiftFutureClientOpportunities(!prefix.first,FUTURE_OTHER_PHASE);
        BoxingJumpTimingAudit.shiftAttackTiming(game.clock.state,
                prefix.first?OWN_UPLOAD:OTHER_UPLOAD,prefix.first?OTHER_UPLOAD:OWN_UPLOAD,anchor,anchor+horizon);
        game.trace();
        var reply = new ArrayList<Object>();
        var replyGeometry = new ArrayList<Object>();
        var timeline = new ArrayList<List<Object>>();
        int[] presses = {0,0};
        long[] previous = {-1,-1};
        game.clock.setClickAudit(e -> {
            if (probe != null) probe.click(e,game.clock.state);
            if (TRACE && e.action().equals("CONSUME")) {
                var sample = new ArrayList<>(BoxingApproachPollCompare.pose(e.time(),"PRESS",e.fromA(),prefix.first,game.clock.state));
                sample.addAll(List.of(e.tick(),e.eligible(),e.contact(),e.admittedNanos()/1e6));
                if (e.contact()) {
                    var state = game.clock.state;
                    var packet = state.packets.get(state.packets.size()-1);
                    if (packet.sequence!=state.seq-1 || packet.kind!=(e.fromA()?3:7))
                        throw new IllegalStateException("Local contact missing its attack packet");
                    sample.addAll(List.of(packet.sequence,packet.due,packet.ready));
                }
                timeline.add(sample);
            }
            if (!e.action().equals("ADMIT")) return;
            int who=e.fromA()==prefix.first?0:1;
            if (previous[who]>=0 && e.admittedNanos()-previous[who]<50_000_000L)
                throw new IllegalStateException("Physical cap mismatch");
            previous[who]=e.admittedNanos();
            presses[who]++;
        });
        game.clock.setAudit((at,event,state) -> {
            if (probe != null) probe.event(at,event,state);
            int n=game.hits.list.size();
            game.hits.event(at,event,state);
            if (TRACE && (event.startsWith("MOVE") || event.startsWith("CLIENT_DRAIN")))
                timeline.add(BoxingApproachPollCompare.pose(at,event,event.endsWith("A"),prefix.first,state));
            if (!TRACE && !reply.isEmpty() && (probe==null || !probe.traceReturns)) return;
            for (int i=n;i<game.hits.list.size();i++) {
                var hit=game.hits.list.get(i);
                if (probe != null) probe.hit(hit,state);
                var impulse=hit.sideA()?state.lastFreshB:state.lastFreshA;
                if (TRACE) {
                    var impact = new ArrayList<Object>(List.of(at,"HIT",hit.sideA()==prefix.first,hit.upgrade(),state.serverTicks));
                    if (hit.upgrade()) impact.add(hit.sideA()?state.lastAcceptedPacketA:state.lastAcceptedPacketB);
                    else impact.addAll(List.of(Math.hypot(impulse.x(),impulse.z()),impulse.y(),
                            impulse.serverSeparation(),impulse.attackerSprint(),hit.sideA()?state.lastFreshPacketA:state.lastFreshPacketB));
                    timeline.add(impact);
                }
                if (reply.isEmpty() && hit.sideA()==prefix.first && !hit.upgrade()) {
                    reply.addAll(List.of(at,state.serverTicks,Math.hypot(impulse.x(),impulse.z()),impulse.y(),
                            Math.hypot(state.serverBx-state.serverAx,state.serverBz-state.serverAz)));
                    replyGeometry.addAll(List.of(impulse.serverSeparation(),impulse.attackerSprint()));
                }
            }
        });
        game.clock.runUntil(anchor+horizon-0.00001);
        return new Branch(List.copyOf(game.hits.list),reply,replyGeometry,List.of(presses[0],presses[1]),
                own instanceof Fighter f ? f.movement : List.of(), own instanceof Fighter f ? f.held : List.of(), timeline);
    }

    private static Map<String,Object> one(DuelEnv env, boolean first, double anchor, int ownPhase, int oppositePhase) {
        var subject = new BoxingApproachPollCompare.Fighter(env.rtt,BoxingApproachPoll.Mode.INPUT_TICK,
                new BoxingApproachPollCompare.Wait(35,true));
        var prefix = env.new Game(first,subject,Evolve.WT.create(env.otherRtt,0));
        if (ownPhase!=0) prefix.clock.shiftFutureClientOpportunities(first,ownPhase);
        if (oppositePhase!=0) prefix.clock.shiftFutureClientOpportunities(!first,oppositePhase);
        prefix.trace();
        prefix.clock.runUntil(anchor-0.00001);
        if (!subject.held.isEmpty()) throw new IllegalStateException("Expected a checkpoint before the first hold");
        var arms = new LinkedHashMap<String,Object>();
        arms.put("0:k",branch(env,prefix,subject,anchor,0,"").summary(first));
        for (int delay:DELAYS) for (String word:WORDS) {
            String key=delay+":"+(word.isEmpty()?"k":word);
            if (ONLY.isEmpty() || key.equals(ONLY) || word.isEmpty() && ONLY.startsWith(delay+":"))
                arms.put(key,branch(env,prefix,subject,anchor,delay,word).summary(first));
        }
        return Map.of("clock",env.clockId,"rtt",env.rtt,"seat",first?"a":"b","anchor",anchor,
                "phase",List.of(ownPhase,oppositePhase),"upload",List.of(OWN_UPLOAD,OTHER_UPLOAD),
                "future_other_phase",FUTURE_OTHER_PHASE,"arms",arms);
    }

    public static void main(String[] args) throws Exception {
        int rtt=Integer.parseInt(args[0]);
        int ownPhase=Integer.parseInt(args[1]), oppositePhase=Integer.parseInt(args[2]);
        var anchors=new LinkedHashMap<String,Double>();
        for (var line:Files.readAllLines(Path.of(args[3]))) {
            String[] p=line.split("\t");
            anchors.put(p[0]+p[1],Double.parseDouble(p[2]));
        }
        for (int i=6;i<args.length;i++) {
            var path=Path.of(args[i]);
            String id=path.getFileName().toString().split("\\.")[0];
            if (!anchors.containsKey(id+"a") && !anchors.containsKey(id+"b")) continue;
            var env=BoxingEdgeHitSelect.environment(path,rtt,180_000,Path.of(args[4]),Path.of(args[5]));
            for (String side:List.of("a","b")) {
                var at=anchors.get(id+side);
                if (at!=null) System.out.println(Json.encode(one(env,side.equals("a"),at,ownPhase,oppositePhase)));
            }
        }
    }
}
