import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import lab.DuelNetwork;

/** Run unchanged on both the actual Forge Java 8 and simulator Java 17. Compare action-stream digests. */
public final class RuntimeParity {
    public static void main(String[] args)throws Exception{
        for(String arg:args){
            String encoded=new String(Files.readAllBytes(Paths.get(arg)),java.nio.charset.StandardCharsets.US_ASCII).trim();
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            int decisions=0;
            for(int seat=0;seat<2;seat++)for(int rtt:new int[]{0,40,80,100})for(int seed=0;seed<8;seed++){
                Random random=new Random(12931L+seed);
                DuelNetwork n=DuelNetwork.decode(encoded),fork=null;
                double ownAge=Double.POSITIVE_INFINITY,targetAge=Double.POSITIVE_INFINITY;
                for(int t=0;t<320;t++){
                    double a=seat==0?1:-1,dx=a*(6-.055*t+.0012*t*t+random.nextGaussian()*.11);
                    double x=4096+a*Math.sin(t*.033)*4,z=a*Math.cos(t*.043)*3,tx=x+dx,tz=z+a*Math.sin(t*.067);
                    double y=12+Math.max(0,Math.cos(t*.31)),ty=12+Math.max(0,Math.sin(t*.26));
                    double vx=a*random.nextGaussian()*.19,vy=random.nextGaussian()*.15,vz=a*.03;
                    ownAge+=49.8;targetAge+=49.8;if(t%17==9)ownAge=4;if(t%19==10)targetAge=19;
                    boolean sprint=t%5<3,ground=y==12;
                    if(t==9 || t==90)fork=n.copy();
                    int action=n.step(x,y,z,tx,ty,tz,vx,vy,vz,ground,sprint,ownAge,targetAge,rtt);
                    if(fork!=null && fork.step(x,y,z,tx,ty,tz,vx,vy,vz,ground,sprint,ownAge,targetAge,rtt)!=action)
                        throw new AssertionError("Different decision after copy at "+t);
                    // Some prefixes execute W-tap during shadow observation.
                    int applied=t%11==0?DuelNetwork.pack(t%3==0?0:2,0,true,false):action;
                    n.applied(applied);if(fork!=null)fork.applied(applied);
                    digest.update((byte)action);digest.update((byte)applied);decisions++;
                }
            }
            StringBuilder hex=new StringBuilder();for(byte v:digest.digest())hex.append(String.format("%02x",v&255));
            System.out.println(Paths.get(arg).getFileName()+" "+decisions+" "+hex);
        }
    }
}
