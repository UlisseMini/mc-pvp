package sword;

import java.nio.file.*;
import java.util.*;
import static sword.Fighter.*;

/** One-tick interventions on recorded inputs. All queues and input history are replayed before the fork. */
public final class JumpFork {
    record Round(List<KernelReplay.Sample> a,List<KernelReplay.Sample> b,int[] phases,float[] yaw,boolean[] localFirst,int lag) {}
    record Step(boolean matched,double x,double y,double z) {}

    static Step replay(Round r,int seat,int tick,boolean release) {
        var sims=new FastDuel.Seat[2];
        var views=List.of(r.a,r.b);
        for(int i=0;i<2;i++){
            var s=new FastDuel.Seat(r.lag,r.phases[i],i+1);
            KernelReplay.start(s,views.get(i).getFirst());
            s.local.yaw=s.server.yaw=r.yaw[i];s.localFirst=r.localFirst[i];sims[i]=s;
        }
        boolean matched=false;
        for(int t=0;t<=tick;t++){
            for(int i=0;i<2;i++){
                var s=sims[i];s.receive(t);
                var sample=views.get(i).get(t);var k=sample.keys();
                if(t==tick&&seat==i){
                    var p=s.local;
                    matched=p.x==sample.x()&&p.y==sample.y()&&p.z==sample.z()
                        &&p.vx==sample.vx()&&p.vy==sample.vy()&&p.vz==sample.vz()
                        &&p.ground==sample.ground()&&s.sprint==sample.sprint()
                        &&s.tx==sample.tx()&&s.ty==sample.ty()&&s.tz==sample.tz();
                    if(release)k=new Keys(k.forward(),k.side(),k.sprint(),false,k.click(),k.offset());
                }
                s.input(t,k,sample.yaw(),sample.pitch());
            }
            var a=sims[0];var b=sims[1];
            a.handle(t,b);b.handle(t,a);a.broadcast(t,b);b.broadcast(t,a);a.serverTick(b);b.serverTick(a);
        }
        var own=sims[seat].local;
        return new Step(matched,own.x,own.y,own.z);
    }

    public static void main(String[] args)throws Exception{
        Path dir=Path.of(args[0]);String id=args[1];int seat=Integer.parseInt(args[2]),lag=Integer.parseInt(args[3]);
        var a=KernelReplay.read(dir.resolve(id+"-A.tsv"));var b=KernelReplay.read(dir.resolve(id+"-B.tsv"));
        var own=seat==0?a:b;
        int[] phases=Arrays.stream(Files.readAllLines(dir.resolve(id+"-clock.tsv")).get(1).split("\t")).mapToInt(Integer::parseInt).toArray();
        float[] yaw=new float[2];boolean[] localFirst=new boolean[2];
        for(int i=0;i<2;i++){
            String[] init=Files.readString(dir.resolve(id+"-"+"AB".charAt(i)+".initial")).strip().split("\t");
            yaw[i]=Float.parseFloat(init[0]);localFirst[i]=Boolean.parseBoolean(init[1]);
        }
        var r=new Round(a,b,phases,yaw,localFirst,lag);
        for(var line:Files.readAllLines(dir.resolve(id+"-contacts.tsv")).stream().skip(1).toList()){
            var c=line.split("\t");
            if(!c[2].equals("SwordLab"+"AB".charAt(seat))||Integer.parseInt(c[9])>10)continue;
            int t=Math.toIntExact((Long.parseLong(c[11])-15-own.getFirst().stamp())/50)+lag+1;
            if(t<=0||t+1>=Math.min(a.size(),b.size()))continue;
            var v=own.get(t);
            if(!v.ground()||!v.keys().jump()||own.get(t-1).keys().jump())continue;
            Step actual=replay(r,seat,t,false),control=replay(r,seat,t,true);
            var next=own.get(t+1);
            boolean qualified=actual.matched&&control.matched&&actual.x==next.x()&&actual.y==next.y()&&actual.z==next.z();
            double dx=v.x()-v.tx(),dz=v.z()-v.tz(),length=Math.hypot(dx,dz);
            double saved=length==0?0:((control.x-actual.x)*dx+(control.z-actual.z)*dz)/length;
            System.out.printf(Locale.ROOT,"%d,%b,%.9f%n",t,qualified,saved);
        }
    }
}
