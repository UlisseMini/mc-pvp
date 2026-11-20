import java.io.*;
import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;

/** Both controllers watch the same local view and the same executed input, including actual press times. */
final class Demonstrations {
    static final class Mix extends DuelEnv.Player {
        final Arena.Neural student; final DuelEnv.Player teacher;final SplittableRandom draw;final double teacherFraction;
        final DataOutputStream rows;int heads;
        Mix(Arena.Neural n,DuelEnv.Player t,int own,long seed,double fraction,DataOutputStream rows){
            super("demonstrations",0,own,0);student=n;teacher=t;draw=new SplittableRandom(seed);teacherFraction=fraction;this.rows=rows;
        }
        public void received(PeriodicComboClock.PacketObservation e){student.received(e);teacher.received(e);}
        public boolean injected(PeriodicComboClock.Observation o,double t){teacher.injected(o,t);return student.injected(o,t);}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var a=student.input(o);var b=teacher.input(o);var c=draw.nextDouble()<teacherFraction?b:a;
            int actual=DuelNetwork.pack(c.key(),(int)Math.signum(c.strafe()),c.click(),c.jump());
            student.net.applied(actual);if(teacher instanceof Arena.Neural n)n.net.applied(actual);
            try{
                for(double x:student.net.features())rows.writeFloat((float)x);
                rows.writeFloat(DuelNetwork.pack(b.key(),(int)Math.signum(b.strafe()),b.click(),b.jump()));
            }catch(IOException e){throw new UncheckedIOException(e);}
            heads++;return c;
        }
    }
    public static void main(String[] args)throws Exception{
        LeagueWorker.load();
        try(var in=Files.newBufferedReader(Path.of(args[0]));var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[1]))))){
            String row;while((row=in.readLine())!=null){
                String[] p=row.split("\t");var t=MemoryAssay.trial(p);
                var e=new DuelEnv(t.base(),t.seat()?t.own():t.other(),t.seat()?t.other():t.own());
                var teacher=LeagueWorker.player(MemoryAssay.resolve(p[8]),t.own(),t.other(),e,t.aSeed()+1);
                var student=(Arena.Neural)LeagueWorker.player(t.actor(),t.own(),t.other(),e,t.aSeed());
                var bytes=new ByteArrayOutputStream();var stream=new DataOutputStream(bytes);
                var mix=new Mix(student,teacher,t.own(),t.aSeed()+2,Double.parseDouble(p[9]),stream);
                var g=e.new Game(t.seat(),mix,LeagueWorker.player(t.foe(),t.other(),t.own(),e,t.bSeed()));
                g.finish();out.writeInt(mix.heads);out.writeFloat((float)g.reward());bytes.writeTo(out);
            }
        }
    }
}
