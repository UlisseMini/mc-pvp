package sword;

import java.nio.file.*;
import java.util.*;
import static sword.Fighter.*;
import static sword.FlatPhysics.*;

/** Full-duel replay from a common native opening. Each row is the beginning
 * of a client tick, after inbound packets and before the physical key press. */
public final class KernelReplay {
    record Sample(double x,double y,double z,double tx,double ty,double tz,double vx,double vy,double vz,
                  boolean ground,boolean sprint,double charge,double hp,Keys keys,float yaw,float pitch,long stamp){}
    static List<Sample> read(Path file)throws Exception{
        var rows=new ArrayList<Sample>();
        for(var line:Files.readAllLines(file).stream().skip(1).toList()){
            var s=line.split("\t");var n=new double[10];for(int i=1;i<10;i++)n[i]=Double.parseDouble(s[i]);
            rows.add(new Sample(n[1],n[2],n[3],n[4],n[5],n[6],n[7],n[8],n[9],Boolean.parseBoolean(s[10]),
                Boolean.parseBoolean(s[11]),Double.parseDouble(s[12]),Double.parseDouble(s[13]),
                new Keys(Integer.parseInt(s[16]),Integer.parseInt(s[17]),Boolean.parseBoolean(s[18]),Boolean.parseBoolean(s[19]),
                    Boolean.parseBoolean(s[20]),0),Float.parseFloat(s[21]),Float.parseFloat(s[22]),Long.parseLong(s[23])));
        }return rows;
    }
    static void start(FastDuel.Seat a,Sample r) {
        a.start(r.x,r.y,r.z,r.yaw,r.tx,r.ty,r.tz);a.local.vx=r.vx;a.local.vy=r.vy;a.local.vz=r.vz;
        a.local.ground=r.ground;a.lastSent=a.sprint=r.sprint;
    }
    static double distance(double x,double y,double z){return Math.sqrt(x*x+y*y+z*z);}
    static double quantile(List<Double> xs,double q){return xs.get((int)Math.floor((xs.size()-1)*q));}
    public static void main(String[] args)throws Exception {
        Path dir=Path.of(args[0]);String id=args[1];int lag=Integer.parseInt(args[2]);
        boolean verbose=Boolean.getBoolean("sword.verbose");
        var left=read(dir.resolve(id+"-A.tsv"));var right=read(dir.resolve(id+"-B.tsv"));
        int phaseA=Integer.getInteger("sword.phaseA",0),phaseB=Integer.getInteger("sword.phaseB",0);
        Path clock=dir.resolve(id+"-clock.tsv");
        if(Files.exists(clock)){
            String[] phases=Files.readAllLines(clock).get(1).split("\t");
            phaseA=Integer.parseInt(phases[0]);phaseB=Integer.parseInt(phases[1]);
        }
        var a=new FastDuel.Seat(lag,phaseA,1);var b=new FastDuel.Seat(lag,phaseB,2);
        start(a,left.getFirst());start(b,right.getFirst());
        Model.Controller ca=args.length>3?Controls.get(args[3]):null,cb=args.length>3?Controls.get(args[4]):null;
        // The recorded yaw has already been steered once. Reconstruct the spawn facing for closed-loop tests.
        if(ca!=null){
            a.local.yaw=(float)(Math.round((Math.toDegrees(Math.atan2(b.local.z-a.local.z,b.local.x-a.local.x))-90)*100)/100.);
            b.local.yaw=(float)Fighter.angle(a.local.yaw+180);
            a.local.yaw=(float)Fighter.angle(a.local.yaw);
        }
        for(int i=0;i<2;i++){
            var side=i==0?a:b;var initial=dir.resolve(id+(i==0?"-A":"-B")+".initial");
            if(Files.exists(initial)){
                var fields=Files.readString(initial).strip().split("\t");
                side.local.yaw=side.server.yaw=Float.parseFloat(fields[0]);side.localFirst=Boolean.parseBoolean(fields[1]);
            }
        }
        int steps=Math.min(left.size(),right.size()),first=-1,firstHP=-1,keysEqual=0,hpExact=0,poseExact=0;
        var events=new ArrayList<String>();
        var expected=new ArrayList<String>();
        long origin=left.getFirst().stamp;
        if(origin!=right.getFirst().stamp)throw new IllegalArgumentException("Client tick origins differ");
        for(String line:Files.readAllLines(dir.resolve(id+"-contacts.tsv")).stream().skip(1).toList()){
            var c=line.split("\t");int tick=Math.toIntExact((Long.parseLong(c[11])-15-origin)/50);
            expected.add(event(tick,c[1].endsWith("A")?0:1,Integer.parseInt(c[9])<=10,Boolean.parseBoolean(c[3]),Boolean.parseBoolean(c[6]),Float.parseFloat(c[10])));
        }
        var server=Files.readAllLines(dir.resolve(id+"-server.tsv"));
        long endStamp=Long.parseLong(server.getLast().split("\t")[15]);
        int nativeEnd=Math.toIntExact((endStamp-15-origin)/50),modelEnd=-1;
        int observed=0;
        var errors=new ArrayList<Double>();var remote=new ArrayList<Double>();
        for(int t=0;t<=(ca==null?Math.min(nativeEnd,steps+lag-1):Model.LIMIT)&&modelEnd<0;t++) {
            if(t>=steps&&ca==null){
                execute(t,a,b,events);if(a.hp==0||b.hp==0)modelEnd=t;continue;
            }
            a.receive(t);b.receive(t);
            for(int seat=0;seat<2;seat++){
                var s=seat==0?a:b;var c=seat==0?ca:cb;
                if(t>=steps){var v=s.view();var k=c.act(v);s.drive(t,v,k);continue;}
                var r=(seat==0?left:right).get(t);observed++;
                var p=s.local;double err=distance(p.x-r.x,p.y-r.y,p.z-r.z);
                double targetError=distance(s.tx-r.tx,s.ty-r.ty,s.tz-r.tz);
                errors.add(err);remote.add(targetError);
                if(err==0&&targetError==0&&p.vx==r.vx&&p.vy==r.vy&&p.vz==r.vz&&p.ground==r.ground&&s.sprint==r.sprint&&s.view().charge()==r.charge)poseExact++;
                if(first<0&&err>.25)first=t;
                if(s.seenHP==(float)r.hp)hpExact++;else if(firstHP<0)firstHP=t;
                var v=s.view();var k=c==null?r.keys:c.act(v);
                if(k.forward()==r.keys.forward()&&k.side()==r.keys.side()&&k.sprint()==r.keys.sprint()
                    &&k.jump()==r.keys.jump()&&k.click()==r.keys.click())keysEqual++;
                if(verbose)System.err.printf(Locale.ROOT,"%s %d %d err %.9f remote %.6f x %.6f %.6f vy %.7f %.7f vx %.7f %.7f hp %.4f %.4f sprint %b %b svel %.8f %.8f srvG %b falling %b age %d charge %.8f target %.17g %.17g %.17g native %.17g %.17g %.17g yaw %.6f %.6f%n",
                    id,t,seat,err,targetError,p.x,r.x,p.vy,r.vy,p.vx,r.vx,s.seenHP,r.hp,s.sprint,r.sprint,s.server.vx,s.server.vy,s.server.ground,s.fall,s.age,r.charge,s.tx,s.ty,s.tz,r.tx,r.ty,r.tz,(float)steer(v,k),r.yaw);
                if(c==null)s.input(t,k,r.yaw,r.pitch);else s.drive(t,v,k);
            }
            execute(t,a,b,events);if(a.hp==0||b.hp==0)modelEnd=t;
        }
        if(modelEnd<0&&ca!=null)modelEnd=Model.LIMIT;
        Collections.sort(errors);Collections.sort(remote);
        float[] actual={0,0};String[] outcome=Files.readString(dir.resolve(id+".result")).trim().split("\t");
        for(int i=0;i<2;i++)actual[i]=Float.parseFloat(outcome[i]);
        System.out.printf(Locale.ROOT,"%s,%d,%d,%.9g,%.9g,%.9g,%d,%d,%d,%d,%.7f,%.7f,%.7f,%.7f,%d,%d,%d,%d,%d,%b,%d,%d,%.9g,%.9g%n",
            id,lag,steps,quantile(errors,.5),quantile(errors,.95),quantile(remote,.95),first,firstHP,hpExact,keysEqual,a.hp,b.hp,actual[0],actual[1],poseExact,observed,events.size(),expected.size(),modelEnd,events.equals(expected),nativeEnd,(a.hp==actual[0]&&b.hp==actual[1])?1:0,errors.getLast(),remote.getLast());
    }
    static String event(int tick,int seat,boolean fresh,boolean crit,boolean boost,float charge){
        return tick+"/"+seat+"/"+fresh+"/"+crit+"/"+boost+"/"+Float.floatToIntBits(charge);
    }
    // Offline sensitivity check. A negative tick preserves the recorded clock.
    private static final int REVERSE_UPLINK_TICK = Integer.getInteger("sword.reverseUplinkTick", -1);
    static void handle(int t, int seat, FastDuel.Seat player, FastDuel.Seat other, List<String> events) {
        int before = player.stats.hits;
        player.handle(t, other);
        if (player.stats.hits != before)
            events.add(event(t,seat,player.stats.lastFresh==t,player.lastCritical,player.lastBoost,player.lastCharge));
    }
    static void execute(int t,FastDuel.Seat a,FastDuel.Seat b,List<String> events){
        if (t == REVERSE_UPLINK_TICK) {
            handle(t,1,b,a,events);
            handle(t,0,a,b,events);
        } else {
            handle(t,0,a,b,events);
            handle(t,1,b,a,events);
        }
        a.broadcast(t,b);b.broadcast(t,a);a.serverTick(b);b.serverTick(a);
    }
}
