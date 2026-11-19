package lab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;

/** Start signal for a controlled two-bot boxing drill. There is no telemetry channel during the suffix. */
public final class BoxingLandmark {
    private static final String WAKE="boxing-drill-wake.properties", START="boxing-drill-start.properties";
    private static void write(Path path,String match,long at){
        Properties p=new Properties();p.setProperty("match",match);p.setProperty("time",Long.toString(at));
        R.writeProperties(path,p);
    }
    private static long read(Path path,String match){
        if(!Files.isRegularFile(path))return -1;
        Properties p=R.properties(path);
        return match.equals(p.getProperty("match"))?Long.parseLong(p.getProperty("time")):-1;
    }
    static void click(Path slot,String match,long at){write(slot.resolve(WAKE),match,at);}
    static boolean started(Path slot,String match){return read(slot.resolve(START),match)>=0;}

    static final class Session{
        final int threshold;
        final String match,subject;
        final Path a,b,target;
        final ArrayList<Accepted> pending=new ArrayList<Accepted>();
        long wake=-1,landmark=-1,announced=-1;
        int streak;
        Session(int k,String match,String subject,Path a,Path b,Path target){
            if(k!=3&&k!=5&&k!=10)throw new IllegalArgumentException("Expected a K3, K5 or K10 drill");
            this.threshold=k;this.match=match;this.subject=subject;this.a=a;this.b=b;this.target=target;
        }
        void accepted(String attacker,boolean upgrade,long time){
            if(landmark>=0)return;
            Accepted hit=new Accepted(subject.equals(attacker),upgrade,time);
            if(wake<0)pending.add(hit);else count(hit);
        }
        private void count(Accepted hit){
            if(hit.at<wake||landmark>=0)return;
            if(!hit.own)streak=0;
            else if(!hit.upgrade && ++streak==threshold)landmark=hit.at;
        }
        void poll(){
            if(announced>=0)return;
            if(wake<0){
                long x=read(a.resolve(WAKE),match),y=read(b.resolve(WAKE),match);
                if(x<0||y<0)return;
                wake=Math.max(x,y);
                for(Accepted h:pending)count(h);
                pending.clear();
            }
            if(landmark>=0){
                long at=System.currentTimeMillis();write(target.resolve(START),match,at);announced=at;
            }
        }
    }
    private static final class Accepted{
        final boolean own,upgrade;final long at;
        Accepted(boolean own,boolean upgrade,long at){this.own=own;this.upgrade=upgrade;this.at=at;}
    }
}
