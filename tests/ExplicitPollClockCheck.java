import java.util.*;

/** Timing-contract checks only: no policy optimization or combat performance claim. */
public final class ExplicitPollClockCheck {
    static int checks;
    static void check(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
    static void reject(Runnable task,String message){
        checks++;try{task.run();}catch(IllegalArgumentException|IllegalStateException expected){
            if(!expected.getMessage().contains(message))throw new AssertionError("Wrong rejection: "+expected,expected);return;
        }throw new AssertionError("Missing rejection: "+message);
    }
    static PeriodicComboClock.Scheduled e(long millis,long seq,PeriodicComboClock.Kind kind){return new PeriodicComboClock.Scheduled(millis*1_000_000L,seq,kind,true);}
    static final PeriodicComboClock.Policy PRESS=o->new PeriodicComboClock.Control(0,0,true);
    static final PeriodicComboClock.Policy IDLE=o->new PeriodicComboClock.Control(0,0,false);
    static PeriodicComboClock.Timing timing(boolean explicit){var t=new PeriodicComboClock.Timing();t.inputMode=PeriodicComboClock.InputMode.NATIVE_QUEUED;t.explicitPolls=explicit;return t;}
    static PeriodicComboClock clock(PeriodicComboClock.Timing t,PeriodicComboClock.Policy a,List<PeriodicComboClock.Scheduled> tape){
        var c=new PeriodicComboFightModel.Config();c.ownRtt=c.otherRtt=0;
        return new PeriodicComboClock(new PeriodicComboFightModel.State(2),c,t,a,IDLE,tape);
    }
    static class Capture implements PeriodicComboClock.Policy {
        final List<Double> completions=new ArrayList<>();final List<String> sources=new ArrayList<>();
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation own){return new PeriodicComboClock.Control(0,0,true);}
        public boolean allowInjection(PeriodicComboClock.Observation own,String source){sources.add(source);return true;}
        public boolean injected(PeriodicComboClock.Observation own,double completedAt){check(own.time()==completedAt,"Completion callback observes actual completion time");completions.add(completedAt);return true;}
    }
    public static void main(String[] args){
        var plain=List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD),e(1,1,PeriodicComboClock.Kind.FRAME_ENTRY),e(2,2,PeriodicComboClock.Kind.CONSUME));
        var legacy=clock(timing(false),PRESS,plain);legacy.runUntil(2);
        check(legacy.state.a.localContacts==1,"Default HEAD poll retains historical behavior");
        var explicit=clock(timing(true),PRESS,plain);explicit.runUntil(2);
        check(explicit.state.a.localContacts==0&&explicit.queuedClicks(true)==0,"Explicit HEAD/FRAME cannot invent poll opportunities");

        var tape=List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD),e(1,1,PeriodicComboClock.Kind.POLL_NATIVE),
            e(2,2,PeriodicComboClock.Kind.CONSUME),e(51,3,PeriodicComboClock.Kind.FRAME_ENTRY),
            e(51,4,PeriodicComboClock.Kind.POLL_FRAME),e(52,5,PeriodicComboClock.Kind.CONSUME));
        var capture=new Capture();explicit=clock(timing(true),capture,tape);var clicks=new ArrayList<PeriodicComboClock.ClickEvent>();explicit.setClickAudit(clicks::add);explicit.runUntil(52);
        check(explicit.state.a.localContacts==2,"Only measured polls generate the two newly selected presses");
        check(capture.sources.equals(List.of("native_tick","frame")),"Admission permission receives actual poll source");
        check(clicks.stream().filter(x->x.action().equals("ADMIT")).map(PeriodicComboClock.ClickEvent::time).toList().equals(List.of(1.0,51.0)),"Explicit poll timestamps replace inferred head/frame times");
        final var explicitTape=tape;
        reject(()->clock(timing(false),PRESS,explicitTape),"require explicitPolls");

        // No completion timing lookup for an empty gate or own-policy deferral.
        var never=timing(true);never.injectionTiming=(a,ns)->{throw new AssertionError("Cost sampled for a non-injection");};
        var idle=clock(never,IDLE,tape);idle.runUntil(52);
        var deferred=clock(never,new PeriodicComboClock.Policy(){
            public PeriodicComboClock.Control input(PeriodicComboClock.Observation own){return new PeriodicComboClock.Control(0,0,true);}
            public boolean allowInjection(PeriodicComboClock.Observation own,String source){return false;}
        },tape);deferred.runUntil(52);
        check(idle.queuedClicks(true)==0&&deferred.queuedClicks(true)==0,"Empty/deferred polls do not sample admitted-call overhead");

        var delayed=timing(true);delayed.injectionTiming=(a,ns)->2_000_000L;
        tape=List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD),e(1,1,PeriodicComboClock.Kind.POLL_NATIVE),
            new PeriodicComboClock.Scheduled(2_000_000,2,PeriodicComboClock.Kind.TICK_HEAD,false),
            e(4,3,PeriodicComboClock.Kind.CONSUME),e(51,4,PeriodicComboClock.Kind.POLL_FRAME),
            e(53,5,PeriodicComboClock.Kind.POLL_NATIVE),e(56,6,PeriodicComboClock.Kind.CONSUME));
        capture=new Capture();explicit=clock(delayed,capture,tape);clicks=new ArrayList<>();explicit.setClickAudit(clicks::add);
        explicit.runUntil(2);
        check(explicit.queuedClicks(true)==0&&capture.completions.isEmpty(),"Press/callback are unavailable during synchronous call; other client can run");
        var forkCapture=new Capture();var fork=explicit.fork(forkCapture,IDLE);
        fork.runUntil(4);
        check(fork.state.a.localContacts==1&&forkCapture.completions.equals(List.of(3.0)),"Fork preserves pending completion event and press");
        check(explicit.state.a.localContacts==0&&capture.completions.isEmpty(),"Fork completion cannot mutate original state/policy");
        explicit.runUntil(51);
        check(explicit.state.a.localContacts==1&&explicit.queuedClicks(true)==0,"Poll 50ms after start remains blocked: only 48ms since completion");
        explicit.runUntil(56);
        check(explicit.state.a.localContacts==2&&capture.completions.equals(List.of(3.0,55.0)),"Admission resumes exactly 50ms after previous completion");
        var admissions=clicks.stream().filter(x->x.action().equals("ADMIT")).toList();
        check(admissions.get(0).time()==3&&admissions.get(0).admittedNanos()==1_000_000L,"Audit distinguishes poll origin from completion");
        fork.runUntil(56);check(fork.state.a.localContacts==explicit.state.a.localContacts&&forkCapture.completions.equals(capture.completions),"Fork also preserves cap completion history");

        // Every synchronous same-client stage is unsupported inside the call.
        for(var kind:List.of(PeriodicComboClock.Kind.TICK_HEAD,PeriodicComboClock.Kind.FRAME_ENTRY,
                PeriodicComboClock.Kind.POLL_FRAME,PeriodicComboClock.Kind.POLL_NATIVE,PeriodicComboClock.Kind.CONSUME,
                PeriodicComboClock.Kind.CLIENT_DRAIN,PeriodicComboClock.Kind.MOVE,PeriodicComboClock.Kind.INTERP,PeriodicComboClock.Kind.FLUSH)){
            var overlap=clock(delayed,PRESS,List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD),e(1,1,PeriodicComboClock.Kind.POLL_NATIVE),e(2,2,kind),e(4,3,PeriodicComboClock.Kind.CONSUME)));
            reject(()->overlap.runUntil(4),"UNSUPPORTED_INJECTION_OVERLAP");
        }
        var boundary=clock(delayed,PRESS,List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD),e(1,1,PeriodicComboClock.Kind.POLL_NATIVE),e(3,2,PeriodicComboClock.Kind.CONSUME)));
        boundary.runUntil(3);check(boundary.state.a.localContacts==1,"Completion precedes same-nanosecond next opportunity");
        var bad=timing(true);bad.injectionTiming=(a,ns)->-1;
        var negative=clock(bad,PRESS,List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD),e(1,1,PeriodicComboClock.Kind.POLL_NATIVE)));
        reject(()->negative.runUntil(1),"Negative injection");
        var uncovered=clock(delayed,PRESS,List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD),e(1,1,PeriodicComboClock.Kind.POLL_NATIVE)));
        reject(()->uncovered.runUntil(1),"exceeds tape coverage");

        // Explicit polls must not silently refresh a selection after geometry changed.
        var stale=clock(timing(true),PRESS,List.of(e(0,0,PeriodicComboClock.Kind.TICK_HEAD),e(1,1,PeriodicComboClock.Kind.INTERP),e(2,2,PeriodicComboClock.Kind.POLL_NATIVE),e(3,3,PeriodicComboClock.Kind.CONSUME)));
        reject(()->stale.runUntil(3),"UNSUPPORTED_CACHED_SELECTION");
        System.out.println("ExplicitPollClockCheck passed: "+checks+" checks");
    }
}
