import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Records own-client inputs and opponent action labels on the same clock. */
final class PredictionTrace {
    static final int FEATURES = 27;
    static final int LABELS = 4;
    static final int HORIZON_MS = 100;
    static final int MAX_HEADS = 320;

    record Head(double time, int key, int side, boolean jump, float[] features) {}

    static final class Recorder extends DuelEnv.Player {
        final DuelEnv.Player policy;
        final boolean observe;
        final List<Head> heads = new ArrayList<>();
        final List<Double> presses = new ArrayList<>();
        PeriodicComboClock.Control previous = new PeriodicComboClock.Control(0, 0, false, false);
        double lastPress = Double.NEGATIVE_INFINITY;
        double lastHead = Double.NaN;
        double lastTargetX, lastTargetY, lastTargetZ;

        Recorder(DuelEnv.Player policy, boolean observe) {
            super("prediction-recording", 0, policy.rtt, 0);
            this.policy = policy;
            this.observe = observe;
        }

        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            float[] x = observe && heads.size() < MAX_HEADS ? features(o) : null;
            var c = policy.input(o);
            if (observe ? x != null : o.time() <= (MAX_HEADS + 12) * 50.0)
                heads.add(new Head(o.time(), c.key(), (int)Math.signum(c.strafe()) + 1, c.jump(), x));
            previous = c;
            return c;
        }

        private static float age(double now, double event) {
            return (float)Math.max(0, Math.min(1.5, (now-event)/1000));
        }

        private float[] features(PeriodicComboClock.Observation o) {
            var p = o.own();
            double dx = o.targetX()-p.x(), dz = o.targetZ()-p.z(), gap = Math.hypot(dx,dz);
            double n = Math.max(.001,gap);
            double elapsed = Double.isNaN(lastHead) ? 50 : Math.max(8,o.time()-lastHead);
            double tx = Double.isNaN(lastHead) ? 0 : (o.targetX()-lastTargetX)*50/elapsed;
            double ty = Double.isNaN(lastHead) ? 0 : (o.targetY()-lastTargetY)*50/elapsed;
            double tz = Double.isNaN(lastHead) ? 0 : (o.targetZ()-lastTargetZ)*50/elapsed;
            lastHead = o.time(); lastTargetX = o.targetX(); lastTargetY = o.targetY(); lastTargetZ = o.targetZ();
            return new float[] {
                (float)gap, (float)o.nearest(), (float)(p.y()-4), (float)(o.targetY()-4),
                (float)((dx*p.vx()+dz*p.vz())/n), (float)p.vy(), (float)((dz*p.vx()-dx*p.vz())/n),
                p.onGround()?1:0, o.sprint()?1:0,
                (float)((dx*tx+dz*tz)/n), (float)ty, (float)((dz*tx-dx*tz)/n),
                (float)Math.hypot(p.x(),p.z()), (float)Math.hypot(o.targetX(),o.targetZ()),
                (float)((dx*p.x()+dz*p.z())/n), (float)((dz*p.x()-dx*p.z())/n),
                age(o.time(),o.lastOwnHurtTime()), age(o.time(),o.lastTargetHurtTime()),
                age(o.time(),o.lastContactTime()), age(o.time(),lastPress),
                (float)Math.min(.25,elapsed/1000), rtt/1000f,
                previous.key()==0?1:0,previous.key()==1?1:0,previous.key()==2?1:0,
                (float)Math.signum(previous.strafe()),previous.jump()?1:0,
            };
        }

        @Override public void received(PeriodicComboClock.PacketObservation p){policy.received(p);}
        @Override public float aimYaw(float yaw){return policy.aimYaw(yaw);}
        @Override public lab.MouseLook mouseLook(float yaw,float pitch,float ty,float tp,double elapsed){
            return policy.mouseLook(yaw,pitch,ty,tp,elapsed);
        }
        @Override public boolean contactOnly(){return policy.contactOnly();}
        @Override public boolean allowInjection(PeriodicComboClock.Observation o,String source){
            return policy.allowInjection(o,source);
        }
        @Override public boolean allowInjection(PeriodicComboClock.Observation o,String source,BooleanSupplier ready){
            return policy.allowInjection(o,source,ready);
        }
        @Override public boolean injected(PeriodicComboClock.Observation o,double t){
            lastPress = t;
            return policy.injected(o,t);
        }
        @Override public void consumed(PeriodicComboClock.Observation o,long id){policy.consumed(o,id);}
        @Override public boolean releaseSprintForMove(PeriodicComboClock.Observation o){return policy.releaseSprintForMove(o);}
        @Override public PeriodicComboClock.Control movement(PeriodicComboClock.Control c){return policy.movement(c);}
    }

    static DuelEnv.Player opponent(String[] f,int ourPing,int foePing,DuelEnv env,long seed)
            throws IOException {
        var first = LeagueWorker.player(LeagueWorker.fighter(f[1]),foePing,ourPing,env,seed);
        if (f.length==8) return first;
        var second = LeagueWorker.player(LeagueWorker.fighter(f[8]),foePing,ourPing,env,seed);
        double switchAt = Double.parseDouble(f[9]);
        var switcher = new ResponseReversal.Primed(foePing) {
            @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
                choice = o.time()>=switchAt?1:0;
                return super.input(o);
            }
        };
        switcher.options.add(first);
        switcher.options.add(second);
        return switcher;
    }

    static void run(String row, DataOutputStream out, boolean check) throws IOException {
        String[] f = row.split("\t");
        boolean first = f[3].equals("a");
        int ourPing = Integer.parseInt(f[4]), foePing = Integer.parseInt(f[5]);
        long ourSeed = Long.parseLong(f[6]), foeSeed = Long.parseLong(f[7]);
        var env = new DuelEnv(LeagueWorker.clock(f[2]),first?ourPing:foePing,first?foePing:ourPing);
        var actor = LeagueWorker.fighter(f[0]);
        var ours = new Recorder(LeagueWorker.player(actor,ourPing,foePing,env,ourSeed),true);
        var theirs = new Recorder(opponent(f,ourPing,foePing,env,foeSeed),false);
        var game = env.new Game(first,ours,theirs);
        long[] last = {-LeagueWorker.INTERVAL,-LeagueWorker.INTERVAL};
        game.clock.setClickAudit(ev -> {
            if (!ev.action().equals("ADMIT")) return;
            int side = ev.fromA()?0:1;
            if (ev.admittedNanos()-last[side] < LeagueWorker.INTERVAL) throw new IllegalStateException("CPS cap");
            last[side] = ev.admittedNanos();
            if (ev.fromA()!=first) theirs.presses.add(ev.admittedNanos()/1_000_000.0);
        });
        game.finish();
        if (check) {
            var baseline = env.new Game(first,LeagueWorker.player(actor,ourPing,foePing,env,ourSeed),
                                        opponent(f,ourPing,foePing,env,foeSeed));
            baseline.finish();
            var a = game.clock.state; var b = baseline.clock.state;
            if (game.reward()!=baseline.reward() || game.clock.time()!=baseline.clock.time()
                    || !a.a.q.equals(b.a.q) || !a.b.q.equals(b.b.q)
                    || a.acceptedA!=b.acceptedA || a.acceptedB!=b.acceptedB)
                throw new AssertionError("Read-only recorder changed the original duel");
        }
        out.writeInt(ours.heads.size());
        out.writeFloat((float)game.reward());
        out.writeFloat((float)game.clock.time());
        int head = 0, press = 0;
        for (Head a : ours.heads) {
            double end = a.time()+HORIZON_MS;
            while (head<theirs.heads.size() && theirs.heads.get(head).time()<=a.time()) head++;
            while (press<theirs.presses.size() && theirs.presses.get(press)<=a.time()) press++;
            boolean valid = end<=game.clock.time() && head<theirs.heads.size()
                && theirs.heads.get(head).time()<=end;
            out.writeFloat((float)a.time());
            for (float x : a.features()) out.writeFloat(x);
            if (valid) {
                Head b = theirs.heads.get(head);
                boolean jump = false;
                for (int j=head;j<theirs.heads.size() && theirs.heads.get(j).time()<=end;j++)
                    jump |= theirs.heads.get(j).jump();
                out.writeByte(b.key()); out.writeByte(b.side());
                out.writeByte(press<theirs.presses.size() && theirs.presses.get(press)<=end?1:0);
                out.writeByte(jump?1:0);
            } else {
                for (int j=0;j<LABELS;j++) out.writeByte(-1);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        LeagueWorker.load();
        List<String> fixtures = Files.readAllLines(Path.of(args[0]));
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[1]))))) {
            out.writeInt(fixtures.size()); out.writeInt(FEATURES); out.writeInt(LABELS);
            for (int i=0;i<fixtures.size();i++) {
                run(fixtures.get(i),out,i<24);
                if ((i+1)%512==0) System.out.printf("Recorded %d/%d duels%n",i+1,fixtures.size());
            }
        }
    }
}
