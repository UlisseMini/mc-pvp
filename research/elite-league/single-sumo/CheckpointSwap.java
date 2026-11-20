import java.nio.file.Files;
import java.nio.file.Path;
import lab.DuelNetwork;

/** Compare the two PPO checkpoints on one realized client observation history. */
final class CheckpointSwap extends DuelEnv.Player {
    private final Arena.Neural early, late;
    private final int mask;
    CheckpointSwap(Arena.Neural early,Arena.Neural late,int mask,int rtt) {
        super("checkpoint-swap",0,rtt,0);
        this.early=early;this.late=late;this.mask=mask;
    }
    @Override public void received(PeriodicComboClock.PacketObservation p) {
        early.received(p);late.received(p);
    }
    @Override public boolean injected(PeriodicComboClock.Observation o,double t) {
        boolean first=early.injected(o,t);
        if(late.injected(o,t)!=first)throw new IllegalStateException("Incompatible click injection");
        return first;
    }
    @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
        var a=early.input(o);var b=late.input(o);
        var c=new PeriodicComboClock.Control((mask&1)!=0?b.key():a.key(),(mask&2)!=0?b.strafe():a.strafe(),
                (mask&4)!=0?b.click():a.click(),(mask&8)!=0?b.jump():a.jump());
        int applied=DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump());
        early.net.applied(applied);late.net.applied(applied);
        return c;
    }
    public static void main(String[] args)throws Exception {
        LeagueWorker.load();
        var early=LeagueWorker.fighter(args[0]);var late=LeagueWorker.fighter(args[1]);var opponent=LeagueWorker.fighter(args[2]);
        int index=0;
        for(String row:Files.readAllLines(Path.of(args[3]))) {
            String[] s=row.split("\t");boolean first=s[1].equals("a");int a=Integer.parseInt(s[2]),b=Integer.parseInt(s[3]);
            long ownSeed=Long.parseLong(s[4]),otherSeed=Long.parseLong(s[5]);
            for(int mask=0;mask<16;mask++) {
                var e=new DuelEnv(LeagueWorker.clock(s[0]),first?a:b,first?b:a);
                var actor=new CheckpointSwap((Arena.Neural)LeagueWorker.player(early,a,b,e,ownSeed),
                        (Arena.Neural)LeagueWorker.player(late,a,b,e,ownSeed),mask,a);
                var g=e.new Game(first,actor,LeagueWorker.player(opponent,b,a,e,otherSeed));
                g.finish();
                System.out.println(index+"\t"+mask+"\t"+g.reward()+"\t"+(float)g.clock.time());
            }
            index++;
        }
    }
}
