package lab;

import java.util.*;

/** A point indexes a real recording row. Equal timestamps retain recording order. */
final class ReplaySteps {
    record Point(long ms,String label,Map<String,Object> frame) {}
    Map<String,Object> recordedFrame=Map.of();
    boolean partial;
    final List<Point> events=new ArrayList<>(),ticks=new ArrayList<>();
    final String source;
    ReplaySteps(String source){this.source=source;}
    void sort(){
        Comparator<Point> order=Comparator.comparingLong(Point::ms);
        events.sort(order);ticks.sort(order);
    }
    List<Point> points(boolean tick){return tick?ticks:events;}
    // With no selection, include a mark at the playhead. With a selection, advance by identity.
    static int adjacent(List<Point> points,long ms,int current,boolean next){
        if(current>=0)return current+(next?1:-1);
        int lo=0,hi=points.size();
        while(lo<hi){int mid=(lo+hi)>>>1;long t=points.get(mid).ms();
            if(t<ms||(!next&&t==ms))lo=mid+1;else hi=mid;}
        return next?lo:lo-1;
    }
    void event(long ms,String label){events.add(new Point(ms,label,recordedFrame));}
    void tick(long ms,String label){ticks.add(new Point(ms,label,recordedFrame));}
    void transition(long ms,Map<String,Object> before,Map<String,Object> after,String field,String on,String off){
        if(before.get(field) instanceof Boolean old && after.get(field) instanceof Boolean value && !old.equals(value))
            event(ms,value?on:off);
    }
}
