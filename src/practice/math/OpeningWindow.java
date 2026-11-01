package practice.math;

import java.util.*;

/** Merge timestamped client observations before detecting approaches. Never use arrival order.
 * A transition is interval-censored between the last outside and first inside sample. */
public final class OpeningWindow {
    private static final class Sample {int side;long time;double margin;Sample(int s,long t,double m){side=s;time=t;margin=m;}}
    private final PriorityQueue<Sample> pending=new PriorityQueue<>(Comparator.comparingLong(s->s.time));
    private final long[] latest={Long.MIN_VALUE,Long.MIN_VALUE};
    private final Sample[] previous=new Sample[2];
    private final long[][] crossing=new long[2][];
    private final long start;
    private boolean armed;
    private long outsideSince=-1,watermark;
    private int count;
    private Map<String,Object> result=Collections.emptyMap();
    private final List<Map<String,Object>> history=new ArrayList<>();
    public OpeningWindow(long start){this.start=start;}
    public void add(int side,long time,double margin){
        if(side<0||side>1||!Double.isFinite(margin)||time<start-1000||time<=latest[side])return;
        latest[side]=time;pending.add(new Sample(side,time,margin));
        watermark=Math.min(latest[0],latest[1]);
        while(!pending.isEmpty()&&pending.peek().time<=watermark)accept(pending.poll());
        while(pending.size()>2048){pending.poll();armed=false;crossing[0]=crossing[1]=null;}
    }
    private void accept(Sample s){
        Sample old=previous[s.side];previous[s.side]=s;
        if(old!=null&&s.time-old.time>150){armed=false;crossing[0]=crossing[1]=null;outsideSince=-1;}
        Sample a=previous[0],b=previous[1];
        boolean bothOutside=a!=null&&b!=null&&a.margin<0&&b.margin<0&&Math.abs(a.time-b.time)<=150;
        if(bothOutside){
            if(outsideSince<0)outsideSince=s.time;
            if(s.time-outsideSince>=200&&(crossing[0]!=null||crossing[1]!=null)){armed=false;crossing[0]=crossing[1]=null;}
            if(!armed&&(count==0||s.time-outsideSince>=200)){armed=true;crossing[0]=crossing[1]=null;}
        }else outsideSince=-1;
        if(!armed||s.time<start)return;
        if(crossing[s.side]==null&&old!=null&&old.margin<0&&s.margin>=0&&s.time-old.time<=150)
            crossing[s.side]=new long[]{Math.max(start,old.time),s.time};
        if(crossing[0]!=null&&crossing[1]!=null){
            double low=crossing[1][0]-crossing[0][1],high=crossing[1][1]-crossing[0][0];
            Map<String,Object> r=new LinkedHashMap<>();r.put("index",++count);r.put("time_ms",s.time);
            r.put("lead_a_ms",(low+high)/2);r.put("low_a_ms",low);r.put("high_a_ms",high);r.put("uncertainty_ms",(high-low)/2);
            r.put("a_window",Arrays.asList(crossing[0][0],crossing[0][1]));r.put("b_window",Arrays.asList(crossing[1][0],crossing[1][1]));
            result=Collections.unmodifiableMap(r);history.add(result);if(history.size()>8)history.remove(0);
            armed=false;crossing[0]=crossing[1]=null;
        }
    }
    public Map<String,Object> state(){
        Map<String,Object> out=new LinkedHashMap<>();out.put("last",result);out.put("history",new ArrayList<>(history));
        out.put("definition","geometric range entry; aim, immunity and click timing excluded");
        out.put("status",latest[0]==Long.MIN_VALUE||latest[1]==Long.MIN_VALUE?"waiting_for_views":armed?"approaching":"waiting_for_separation");
        out.put("verified_until_ms",watermark==Long.MIN_VALUE?0:watermark);
        if(armed&&(crossing[0]!=null||crossing[1]!=null)){
            int side=crossing[0]!=null?0:1;out.put("status","one_sided");out.put("first_side",side);
            out.put("minimum_lead_ms",Math.max(0,watermark-crossing[side][1]));
        }
        return out;
    }
}
