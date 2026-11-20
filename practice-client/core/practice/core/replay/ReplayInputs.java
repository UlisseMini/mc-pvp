package practice.core.replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Local physical input indexed by recording time, including after a seek. */
public final class ReplayInputs {
    public enum Key {
        W(17), A(30), S(31), D(32), SPACE(57), LMB(0), RMB(1);

        public final int code;
        Key(int code){this.code=code;}
        public int mask(){return 1<<ordinal();}

        static Key keyboard(int code){
            switch(code){
                case 17:return W;
                case 30:return A;
                case 31:return S;
                case 32:return D;
                case 57:return SPACE;
                default:return null;
            }
        }
        static Key mouse(int button){return button==0?LMB:button==1?RMB:null;}
    }

    public static final class State {
        public final int mask,leftCps,rightCps;
        public State(int mask,int leftCps,int rightCps){this.mask=mask;this.leftCps=leftCps;this.rightCps=rightCps;}
        public boolean down(Key key){return (mask&key.mask())!=0;}
    }

    static final class Event {
        final double at;
        final Key key;
        final boolean down;
        Event(double at,Key key,boolean down){this.at=at;this.key=key;this.down=down;}
    }

    private final double[] times,leftClicks,rightClicks;
    private final int[] masks;
    public final boolean available;

    ReplayInputs(List<Event> events){
        events.sort(Comparator.comparingDouble(event->event.at));
        times=new double[events.size()];
        masks=new int[events.size()];
        List<Double> left=new ArrayList<>(),right=new ArrayList<>();
        boolean captured=false;
        int mask=0;
        for(int i=0;i<events.size();i++){
            Event event=events.get(i);
            Key key=event.key;
            if(key==null)mask=0;
            else{
                captured=true;
                mask=event.down?mask|key.mask():mask&~key.mask();
                if(event.down&&key==Key.LMB)left.add(event.at);
                if(event.down&&key==Key.RMB)right.add(event.at);
            }
            times[i]=event.at;
            masks[i]=mask;
        }
        available=captured;
        leftClicks=left.stream().mapToDouble(Double::doubleValue).toArray();
        rightClicks=right.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public State at(double ms){
        int end=after(times,ms);
        return new State(end==0?0:masks[end-1],cps(leftClicks,ms),cps(rightClicks,ms));
    }

    private static int cps(double[] clicks,double ms){return after(clicks,ms)-after(clicks,ms-1000);}

    private static int after(double[] values,double ms){
        int lo=0,hi=values.length;
        while(lo<hi){
            int mid=(lo+hi)>>>1;
            if(values[mid]<=ms)lo=mid+1;
            else hi=mid;
        }
        return lo;
    }
}
