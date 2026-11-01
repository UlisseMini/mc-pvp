import java.lang.reflect.*;
import java.util.*;

/** Exhaustive field-based semantic comparison; adding a state field extends the check automatically. */
public final class PeriodicComboForkCheck {
    static long comparisons;
    static boolean atomic(Object o){return o==null||o instanceof Number||o instanceof Boolean||o instanceof Character||o instanceof String||o.getClass().isEnum();}
    static void equal(Object a,Object b,String path) throws Exception {
        comparisons++;
        if(a==null||b==null){if(a!=b)throw new AssertionError(path+" null mismatch");return;}
        if(a.getClass()!=b.getClass())throw new AssertionError(path+" class mismatch");
        if(atomic(a)){if(!a.equals(b))throw new AssertionError(path+": "+a+" != "+b);return;}
        if(a.getClass().isArray()){if(Array.getLength(a)!=Array.getLength(b))throw new AssertionError(path+" size");for(int i=0;i<Array.getLength(a);i++)equal(Array.get(a,i),Array.get(b,i),path+"["+i+"]");return;}
        if(a instanceof PriorityQueue<?>){List<Object> aa=new ArrayList<>((PriorityQueue<?>)a),bb=new ArrayList<>((PriorityQueue<?>)b);Comparator<Object> cmp=(Comparator<Object>)((PriorityQueue<?>)a).comparator();aa.sort(cmp);bb.sort(cmp);equal(aa,bb,path+".ordered");return;}
        if(a instanceof List<?>){List<?> aa=(List<?>)a,bb=(List<?>)b;if(aa.size()!=bb.size())throw new AssertionError(path+" size");for(int i=0;i<aa.size();i++)equal(aa.get(i),bb.get(i),path+"["+i+"]");return;}
        Field[] fields=a.getClass().getDeclaredFields();Arrays.sort(fields,Comparator.comparing(Field::getName));
        for(Field f:fields){if(Modifier.isStatic(f.getModifiers()))continue;if(a instanceof PeriodicComboClock&&(f.getName().equals("policies")||f.getName().equals("audit")))continue;f.setAccessible(true);equal(f.get(a),f.get(b),path+"."+f.getName());}
    }
    static PeriodicComboClock.Policy pursue(){return o->new PeriodicComboClock.Control(2,0,true);}
    static PeriodicComboClock.Policy still(){return o->new PeriodicComboClock.Control(0,0,false);}
    static PeriodicComboClock make(){PeriodicComboFightModel.Config c=new PeriodicComboFightModel.Config();c.forceContacts=false;c.ownRtt=0;c.otherRtt=120;PeriodicComboClock.Timing t=new PeriodicComboClock.Timing();t.uplinkA=t.uplinkB=t.downlinkA=t.downlinkB=.4;return new PeriodicComboClock(new PeriodicComboFightModel.State(4093,0,4099,0),c,t,pursue(),still());}
    public static void main(String[] args)throws Exception {
        // Include mid-tick forks with consumed-but-not-traveled controls and unflushed outbound C03/C0B.
        double[] stops={0,.15,.3,.45,.6,25.1,25.5,25.9,180,510,810,1200,1850,2450};
        for(double stop:stops){PeriodicComboClock base=make();base.runUntil(stop);PeriodicComboClock fork=base.fork(pursue(),still());equal(base,fork,"initial@"+stop);
            int[] calls={0};base.setAudit((t,e,s)->calls[0]++);fork.runUntil(stop+400);if(calls[0]!=0)throw new AssertionError("Fork retained source audit");
            // A second fork observes the untouched source after its sibling ran.
            PeriodicComboClock untouched=base.fork(pursue(),still());equal(base,untouched,"isolation@"+stop);base.runUntil(stop+400);equal(base,fork,"continued@"+stop);
        }
        PeriodicComboClock base=make();base.runUntil(700);PeriodicComboClock saved=base.fork(pursue(),still()),changed=base.fork(still(),still());changed.config.kbScale=.8;changed.timing.framePeriod=3;changed.state.extraLatency[0]=8;changed.runUntil(900);equal(base,saved,"independent mutation");
        if(changed.state.a.q.equals(base.state.a.q))throw new AssertionError("Replacement policy had no effect");
        PeriodicComboFightModel.State s=base.state.deepCopy();s.transport=new PeriodicComboTransport();s.transport.add(new String[]{"NETWORK","0","1000","1050","1","4","0","0","1","0","0","4"});PeriodicComboFightModel.State copied=s.deepCopy();equal(s,copied,"transport clone");copied.transport.next[0]=1;copied.transport.routes[0].get(0).generated=true;if(s.transport.next[0]!=0||s.transport.routes[0].get(0).generated)throw new AssertionError("Transport alias");
        System.out.println("PeriodicComboForkCheck passed: "+stops.length+" forks, "+comparisons+" semantic comparisons; mid-tick queues, transport, policy replacement and isolation checked");
    }
}
