package practice.core.replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Local physical input indexed by recording time, including after a seek. */
public final class ReplayInputs {
    public enum Key {
        W(17), A(30), S(31), D(32), SPACE(57), LMB(0), RMB(1), SPRINT(-1);

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

    // A legacy bot can record its Sprint input without recording which physical key was bound.
    public static final int BINDING_UNKNOWN=Integer.MIN_VALUE, INPUT_UNKNOWN=Integer.MIN_VALUE+1;
    public static final class State {
        public final int mask,leftCps,rightCps,sprintCode;
        public State(int mask,int leftCps,int rightCps){this(mask,leftCps,rightCps,INPUT_UNKNOWN);}
        public State(int mask,int leftCps,int rightCps,int sprintCode){this.mask=mask;this.leftCps=leftCps;this.rightCps=rightCps;this.sprintCode=sprintCode;}
        public boolean down(Key key){return (mask&key.mask())!=0;}
    }

    static final class Event {
        final double at;
        final Key key;
        final boolean down;
        final int sprintCode;
        Event(double at,Key key,boolean down){this(at,key,down,BINDING_UNKNOWN);}
        Event(double at,Key key,boolean down,int sprintCode){this.at=at;this.key=key;this.down=down;this.sprintCode=sprintCode;}
        static Event recordedSprint(double ms,java.util.Map<String,Object> data){
            Object down=data.get("sprint_key_down"),code=data.get("sprint_key_code");
            return down instanceof Boolean && code instanceof Number
                ?new Event(ms,Key.SPRINT,(Boolean)down,((Number)code).intValue()):null;
        }
    }

    private final double[] times,leftClicks,rightClicks;
    private final int[] masks,sprintCodes;
    public final boolean available;

    ReplayInputs(List<Event> events){
        events.sort(Comparator.comparingDouble(event->event.at));
        times=new double[events.size()];
        masks=new int[events.size()];
        sprintCodes=new int[events.size()];
        List<Double> left=new ArrayList<>(),right=new ArrayList<>();
        boolean captured=false;
        int mask=0,sprintCode=INPUT_UNKNOWN;
        for(int i=0;i<events.size();i++){
            Event event=events.get(i);
            Key key=event.key;
            if(key==null){mask=0;sprintCode=INPUT_UNKNOWN;}
            else{
                captured=true;
                if(key==Key.SPRINT)sprintCode=event.sprintCode;
                mask=event.down?mask|key.mask():mask&~key.mask();
                if(event.down&&key==Key.LMB)left.add(event.at);
                if(event.down&&key==Key.RMB)right.add(event.at);
            }
            times[i]=event.at;
            masks[i]=mask;
            sprintCodes[i]=sprintCode;
        }
        available=captured;
        leftClicks=left.stream().mapToDouble(Double::doubleValue).toArray();
        rightClicks=right.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public State at(double ms){
        int end=after(times,ms);
        return new State(end==0?0:masks[end-1],cps(leftClicks,ms),cps(rightClicks,ms),end==0?INPUT_UNKNOWN:sprintCodes[end-1]);
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
