import lab.Json;
import java.nio.file.*;
import java.util.*;

/** Bounded, interpretable input families on the canonical fight kernel.
 * This search can discover a finite witness, never prove universal infeasibility.
 * Policies get only their own view; the full state is used exclusively to score.
 */
public final class ComboCycleSearch {
    record Plan(int period,int coastStart,int coastLength,int strength,int burst,int strafe,double feedbackRange,double feedbackGain) {
        Plan(int period,int coastStart,int coastLength,int strength,int burst,int strafe){this(period,coastStart,coastLength,strength,burst,strafe,0,0);}
        String word(){StringBuilder w=new StringBuilder();for(int i=0;i<period;i++)w.append(key(i)==0?'R':key(i)==1?'W':'S');return w.toString();}
        int key(int phase){
            if((phase-coastStart+period)%period<coastLength)return 0;
            return strength==0?2:strength==1?1:phase<period/2?2:1;
        }
        Map<String,Object> json(){return Map.of("period",period,"coast_start",coastStart,"coast_length",coastLength,"strength",strength,"burst",burst,"strafe",strafe,"word",word(),"feedback_range",feedbackRange,"feedback_gain",feedbackGain);}
    }
    static final class Controller implements PeriodicComboClock.Policy {
        final Plan plan;final int hitTicks;double anchor=Double.NaN;int coast;
        Controller(Plan p,int hitTicks){plan=p;this.hitTicks=hitTicks;}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            if(Double.isNaN(anchor)&&o.localContacts()>0)anchor=o.lastContactTime();
            if(Double.isNaN(anchor))return new PeriodicComboClock.Control(2,0,true);
            long tick=Math.round((o.time()-anchor)/50);
            int phase=(int)(tick%plan.period),hitPhase=(int)(tick%hitTicks);
            int key=plan.key(phase);
            if(plan.feedbackGain>0){
                if(phase==plan.coastStart)coast=Math.max(1,Math.min(plan.period-2,(int)Math.round(plan.coastLength+plan.feedbackGain*(plan.feedbackRange-o.nearest()))));
                key=(phase-plan.coastStart+plan.period)%plan.period<coast?0:2;
            }
            return new PeriodicComboClock.Control(key,(float)(plan.strafe*.98),hitPhase<plan.burst);
        }
    }
    record Sample(double time,double ray,double nearest,double defender) {}
    static final class ReadyWindow {final double open;double close=Double.POSITIVE_INFINITY;ReadyWindow(double open){this.open=open;}}
    static final class Score implements PeriodicComboClock.Audit {
        final PeriodicComboFightModel.Config c;final PeriodicComboClock.Timing timing;
        final List<Double> hits=new ArrayList<>();final List<Sample> inputs=new ArrayList<>();final List<ReadyWindow> windows=new ArrayList<>();
        int priorH;ReadyWindow currentWindow;
        int a,b,bAccepted,chain,longest,unanswered,longestUnanswered,freshBeforeReturn;boolean firstReturn;
        double firstReturnAt=Double.NaN,chainStart,longestStart,longestEnd,chainMargin=1e9,bestMargin;
        String stop="duration";
        Score(PeriodicComboFightModel.Config c,PeriodicComboClock.Timing timing){this.c=c;this.timing=timing;}
        public void event(double time,String event,PeriodicComboFightModel.State s){
            double denial=PeriodicComboFightModel.their(s)-c.defenderReach;
            if(s.resistant<=c.hitTicks&&priorH>c.hitTicks){currentWindow=new ReadyWindow(time);windows.add(currentWindow);}
            if(s.resistant>c.hitTicks&&priorH<=c.hitTicks&&currentWindow!=null){currentWindow.close=time;currentWindow=null;}
            priorH=s.resistant;
            boolean returned=s.acceptedB>bAccepted;
            if(!firstReturn){
                if(s.hits>a&&(!returned||s.lastFreshOrderA<s.lastAcceptedOrderB))freshBeforeReturn+=s.hits-a;
                if(returned){firstReturn=true;firstReturnAt=time;}
            }
            // Preserve order when both attacks are handled in one server drain.
            // At these fixed20Hz phases each side has at most one fresh hit per
            // drain. Any accepted return (including a damage upgrade) breaks it.
            if(returned&&(!(s.hits>a)||s.lastAcceptedOrderB<s.lastFreshOrderA))unanswered=0;
            if(s.hits>a){unanswered+=s.hits-a;longestUnanswered=Math.max(longestUnanswered,unanswered);}
            if(returned&&s.lastAcceptedOrderB>s.lastFreshOrderA)unanswered=0;
            if(denial<=0||returned||time<2000){chain=0;chainMargin=1e9;}
            else chainMargin=Math.min(chainMargin,denial);
            if(event.equals("INPUTA"))inputs.add(new Sample(time,PeriodicComboFightModel.ray(s.a,s.ab),PeriodicComboFightModel.own(s),PeriodicComboFightModel.their(s)));
            if(s.hits>a){
                double last=hits.isEmpty()?Double.NEGATIVE_INFINITY:hits.get(hits.size()-1);hits.add(time);
                if(time-last>c.hitTicks*timing.serverPeriod+.01){chain=0;chainMargin=denial;}
                if(denial>0&&!returned&&time>=2000){if(chain==0){chainStart=time;chainMargin=denial;}chain++;
                    if(chain>longest){longest=chain;longestStart=chainStart;longestEnd=time;bestMargin=chainMargin;}}
            }
            a=s.hits;b=s.incomingHits;bAccepted=s.acceptedB;
        }
        Map<String,Object> result(Plan p,PeriodicComboFightModel.State state,double requested,double ran){
            int ready=0,miss=0,censored=0,chainReady=0,chainMiss=0;double worstOwn=0,maxGap=0;
            for(ReadyWindow window:windows){
                if(window.open<2000)continue;
                Sample earliest=null;
                for(Sample sample:inputs){
                    double arrival=sample.time+c.ownRtt/2+timing.uplinkA;
                    double processing=timing.serverPhase+Math.ceil((arrival-timing.serverPhase)/timing.serverPeriod)*timing.serverPeriod;
                    if(processing>window.open&&processing<=Math.min(ran,window.close)){earliest=sample;break;}
                }
                if(earliest!=null){ready++;worstOwn=Math.max(worstOwn,Double.isFinite(earliest.ray)?earliest.ray:99);if(earliest.ray>3)miss++;
                    if(window.open>longestStart&&window.open<longestEnd){chainReady++;if(earliest.ray>3)chainMiss++;}}
                else censored++;
            }
            for(int i=1;i<hits.size();i++)maxGap=Math.max(maxGap,hits.get(i)-hits.get(i-1));
            Map<String,Object> out=new LinkedHashMap<>();out.put("plan",p.json());out.put("fresh_hits",List.of(a,b));out.put("longest_denied_regular_chain",longest);
            out.put("longest_unanswered_fresh_run",longestUnanswered);
            out.put("fresh_hits_before_first_return",freshBeforeReturn);out.put("zero_returns_through_executed_horizon",!firstReturn);
            out.put("first_return_ms",Double.isNaN(firstReturnAt)?null:firstReturnAt);
            boolean completed=stop.equals("duration")&&ran>=requested-.001;
            out.put("primary_metric_complete",firstReturn||completed);
            out.put("zero_return_horizon",firstReturn?false:completed?true:null);
            out.put("chain_start_ms",longestStart);out.put("chain_end_ms",longestEnd);out.put("chain_min_defender_margin",bestMargin);
            out.put("ready_windows",ready);out.put("missed_first_eligible_input_windows",miss);out.put("worst_first_eligible_ray",worstOwn);out.put("max_fresh_gap_ms",maxGap);
            out.put("censored_ready_windows",censored);out.put("best_chain_ready_windows",chainReady);out.put("best_chain_missed_ready_windows",chainMiss);
            out.put("requested_ms",requested);out.put("executed_ms",ran);out.put("stop",stop);out.put("final_gap",Math.hypot(state.b.q.x()-state.a.q.x(),state.b.q.z()-state.a.q.z()));
            out.put("fresh_times",hits);return out;
        }
    }
    static Map<String,Object> trial(Plan p,double ownRtt,double oppRtt,double reach,double horizontal,double vertical,int hitTicks,double seconds,double bPhase,double serverPhase){
        var c=new PeriodicComboFightModel.Config();c.ownRtt=ownRtt;c.otherRtt=oppRtt;c.defenderReach=reach;c.kbScale=horizontal;c.verticalScale=vertical;c.hitTicks=hitTicks;c.forceContacts=false;
        var timing=new PeriodicComboClock.Timing();timing.bTickPhase=bPhase;timing.serverPhase=serverPhase;
        timing.uplinkA=timing.uplinkB=timing.downlinkA=timing.downlinkB=.4;
        var state=new PeriodicComboFightModel.State(4093,0,4099,0);
        PeriodicComboFightModel.defenderReach=reach;
        var clock=new PeriodicComboClock(state,c,timing,new Controller(p,hitTicks),o->new PeriodicComboClock.Control(2,0,o.time()>=2000));
        var score=new Score(c,timing);clock.setAudit(score);
        try{clock.runUntil(seconds*1000);}catch(IllegalStateException e){score.stop=e.getMessage();}
        return score.result(p,state,seconds*1000,clock.time());
    }
    static double rank(Map<String,Object> row){
        int longest=(int)row.get("longest_denied_regular_chain"),miss=(int)row.get("missed_first_eligible_input_windows");
        return longest*1000-Math.min(999,miss*5)+Math.min(1,(double)row.get("chain_min_defender_margin"));
    }
    public static void main(String[] args)throws Exception{
        if(args.length<7)throw new IllegalArgumentException("output.json ownRTT oppRTT opponentReach horizontal vertical hitTicks [seconds=20] [strafe=0]");
        Path output=Path.of(args[0]);if(Files.exists(output))throw new IllegalArgumentException("Refuse to overwrite "+output);
        double own=Double.parseDouble(args[1]),opp=Double.parseDouble(args[2]),reach=Double.parseDouble(args[3]),h=Double.parseDouble(args[4]),v=Double.parseDouble(args[5]);int ticks=Integer.parseInt(args[6]);
        double seconds=args.length>7?Double.parseDouble(args[7]):20;int strafe=args.length>8?Integer.parseInt(args[8]):0;
        List<Map<String,Object>> results=new ArrayList<>();Set<String> words=new HashSet<>();int count=0;
        for(int strength=0;strength<3;strength++)for(int start=0;start<2*ticks;start++)for(int length=0;length<=Math.min(12,2*ticks-1);length++)for(int burst:new int[]{1,2}){
            Plan plan=new Plan(2*ticks,start,length,strength,burst,strafe);
            if(!words.add(plan.word()+":"+burst))continue;
            Map<String,Object> row=trial(plan,own,opp,reach,h,v,ticks,seconds,10,25);results.add(row);count++;
            if(count%200==0)System.err.println("evaluated "+count);
        }
        results.sort(Comparator.comparingDouble(ComboCycleSearch::rank).reversed());
        Map<String,Object> report=new LinkedHashMap<>();report.put("scope","Bounded 1D/constant-strafe input-word search, fixed explicit clocks, own-view-only controls. Target holds W+Ctrl with 20CPS after a declared 2s no-click opening handicap. Not an infeasibility proof.");
        report.put("rtt",List.of(own,opp));report.put("defender_reach",reach);report.put("combat",List.of(h,v,ticks));report.put("duration_seconds",seconds);report.put("candidates",count);
        report.put("timing","A/B/server tick phases0/10/25ms; per-leg residual0.4ms; frames2.5ms; independently queued native event transitions");report.put("results",results);
        Files.writeString(output,Json.encode(report)+"\n");
        for(Map<String,Object> row:results.subList(0,Math.min(5,results.size())))System.out.println(Json.encode(row));
    }
}
