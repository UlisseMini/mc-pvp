package lab;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Own-received-cue landmark only; never a certificate of server return denial. */
public final class MaintenanceHandoff {
    public static final class Settings {
        public final boolean enabled;
        public final String acquisition, arm;
        public final int hits;
        public final long defenderWakeMs;
        public final long cueGuardMs;
        public final int feedbackMirror;

        private Settings(boolean enabled,String acquisition,String arm,int hits,long wake,long guard,int mirror) {
            this.enabled=enabled;this.acquisition=acquisition;this.arm=arm;
            this.hits=hits;this.defenderWakeMs=wake;this.cueGuardMs=guard;this.feedbackMirror=mirror;
        }

        public static Settings from(Properties p) {
            String acquisition=p.getProperty("maintenance_acquisition","");
            if(acquisition.isEmpty()) {
                for(String name:p.stringPropertyNames())if(name.startsWith("maintenance_"))
                    throw new IllegalArgumentException("maintenance_acquisition is required with maintenance settings");
                return new Settings(false,"","",3,4000,100,1);
            }
            if(!acquisition.equals("lock8")&&!acquisition.equals("gap")&&!acquisition.equals("feedback"))
                throw new IllegalArgumentException("maintenance_acquisition must be lock8, gap or feedback");
            int mirror=Integer.parseInt(p.getProperty("maintenance_feedback_mirror","1"));
            if(Math.abs(mirror)!=1)throw new IllegalArgumentException("maintenance_feedback_mirror must be -1 or 1");
            if(acquisition.equals("feedback")&&(Integer.parseInt(p.getProperty("click_cps","10"))!=20
                    ||Double.parseDouble(p.getProperty("click_min_interval_ms","0"))!=51
                    ||Double.parseDouble(p.getProperty("click_start_ms","0"))!=0))
                throw new IllegalArgumentException("Frozen feedback requires 20 CPS, 51ms minimum spacing and no attack opening delay");
            String arm=p.getProperty("maintenance_arm","");
            if(!arm.equals("continue")&&!arm.equals("wtap"))
                throw new IllegalArgumentException("maintenance_arm must be continue or wtap");
            int hits=Integer.parseInt(p.getProperty("maintenance_handoff_hits","3"));
            long wake=Long.parseLong(p.getProperty("maintenance_defender_wake_ms","4000"));
            double ownPing=Double.parseDouble(p.getProperty("ping","0"));
            if(!Double.isFinite(ownPing)||ownPing<0||ownPing>10000)throw new IllegalArgumentException("Invalid maintenance own RTT");
            long guard=Long.parseLong(p.getProperty("maintenance_cue_guard_ms",Long.toString((long)Math.ceil(ownPing/2+100))));
            if(hits<1||hits>100||wake<0||wake>120000||guard<0||guard>120000)
                throw new IllegalArgumentException("Invalid maintenance landmark or defender wake delay");
            if(!p.getProperty("opponent","").matches("LabSlot[0-9]+"))
                throw new IllegalArgumentException("Maintenance research is restricted to lab bot opponents");
            String kind=p.getProperty("kind","hold");
            if(!kind.equals("wtap")&&!kind.equals("hurt-window-coast")&&!kind.equals("gap-strafe"))
                throw new IllegalArgumentException("Maintenance research requires wtap, hurt-window-coast or gap-strafe kind");
            if(Double.parseDouble(p.getProperty("reaction_ms","150"))!=0
                    ||!p.getProperty("click_mode","fixed").equals("fixed")
                    ||!p.getProperty("target_hurt_cue","sampled").equals("status")
                    ||!p.getProperty("sprint_mode","hold").equals("hold"))
                throw new IllegalArgumentException("Maintenance research requires zero reaction, status cues, fixed clicks and hold sprint mode");
            for(String name:new String[]{"hold_opening_ms","gap_opening_ms","hurt_window_opening_ms",
                    "frozen_cycle_opening_ms","open_delay_ms"})
                if(Double.parseDouble(p.getProperty(name,"0"))!=0)
                    throw new IllegalArgumentException("Maintenance research cannot combine with "+name);
            if(p.containsKey("input_word"))throw new IllegalArgumentException("Maintenance research cannot combine with an input word");
            return new Settings(true,acquisition,arm,hits,wake,guard,mirror);
        }
    }

    public final Settings settings;
    private final long epochWallMs,wakeMonoNs,wakeWallMs,eligibleMonoNs;
    private long targetCues,targetCuesAfterWake,eligibleTargetCues,ownFreshCues,ownVelocityCues,returnResets;
    private int streak;
    private long streakStartWallMs,streakStartMonoNs,triggerWallMs,triggerMonoNs;
    private long handoffWallMs,handoffMonoNs,handoffTick;
    private boolean handed,ended;
    private String endReason="";

    public MaintenanceHandoff(Settings settings,long epochWallMs,long observedWallMs,long observedMonoNs) {
        this.settings=settings;this.epochWallMs=epochWallMs;
        wakeWallMs=epochWallMs+settings.defenderWakeMs;
        wakeMonoNs=observedMonoNs+(wakeWallMs-observedWallMs)*1000000L;
        eligibleMonoNs=wakeMonoNs+settings.cueGuardMs*1000000L;
    }

    public boolean enabled(){return settings.enabled;}
    public boolean ended(){return ended;}
    public boolean handed(){return handed;}
    public boolean useAcquisition(){return enabled()&&!ended&&(!handed||settings.arm.equals("continue"));}
    public double elapsedMs(long monoNs){return (monoNs-(wakeMonoNs-settings.defenderWakeMs*1000000L))/1000000.0;}
    /** Matches the frozen model seed ending at 3499.999ms; first HEAD >=3500 uses feedback. */
    public boolean feedbackOpening(long monoNs){return settings.acquisition.equals("feedback")&&elapsedMs(monoNs)<3500;}
    /** Admission changes at3500ms even between HEADs; retained keys/queued presses do not. */
    public boolean coastAdmissionActive(boolean requested,long monoNs){
        return requested&&(!settings.acquisition.equals("feedback")||feedbackOpening(monoNs));
    }

    /** Called once per handled target S19(status=2), not once per sampled hurtTime. */
    public void targetHurt(long wallMs,long monoNs) {
        if(!enabled()||ended)return;
        targetCues++;
        if(monoNs<wakeMonoNs)return;
        targetCuesAfterWake++;
        if(monoNs<eligibleMonoNs)return;
        eligibleTargetCues++;
        if(streak==0){streakStartWallMs=wallMs;streakStartMonoNs=monoNs;}
        streak++;
        if(!handed&&streak==settings.hits){triggerWallMs=wallMs;triggerMonoNs=monoNs;}
    }

    /** S19 misses damage upgrades; S12 is conservative and can also be non-combat. */
    public void ownReturnCue(boolean velocity,long wallMs,long monoNs) {
        if(!enabled()||ended)return;
        if(velocity)ownVelocityCues++;else ownFreshCues++;
        if(monoNs<wakeMonoNs)return;
        returnResets++;
        streak=0;streakStartWallMs=streakStartMonoNs=0;
        if(!handed)triggerWallMs=triggerMonoNs=0;
    }

    /** Commit at a real bot input HEAD; input queues and policy history stay intact. */
    public boolean inputHead(long wallMs,long monoNs,long nativeTick) {
        if(!enabled()||ended||handed||monoNs<eligibleMonoNs||streak<settings.hits)return false;
        handed=true;handoffWallMs=wallMs;handoffMonoNs=monoNs;handoffTick=nativeTick;
        return true;
    }

    public boolean finish(String reason) {
        if(!enabled()||ended)return false;
        ended=true;endReason=reason;return true;
    }

    public Map<String,Object> snapshot(long monoNs) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("enabled",enabled());out.put("acquisition",settings.acquisition);out.put("arm",settings.arm);
        out.put("required_hits",settings.hits);out.put("defender_wake_ms",settings.defenderWakeMs);
        if(settings.acquisition.equals("feedback")){
            out.put("feedback_mirror",settings.feedbackMirror);out.put("feedback_opening_ms",3500);
            out.put("feedback_opening",feedbackOpening(monoNs));out.put("elapsed_ms",elapsedMs(monoNs));
        }
        out.put("cue_guard_ms",settings.cueGuardMs);out.put("eligible_monotonic_ns",eligibleMonoNs);
        out.put("epoch_wall_ms",epochWallMs);out.put("wake_wall_ms",wakeWallMs);out.put("wake_monotonic_ns",wakeMonoNs);
        out.put("status",!enabled()?"disabled":ended?(handed?"ended_after_handoff":"ended_no_handoff"):
                handed?"maintenance":monoNs<wakeMonoNs?"before_defender_wake":monoNs<eligibleMonoNs?"cue_guard":streak>=settings.hits?"ready":"acquiring");
        out.put("handed",handed);out.put("target_s19_total",targetCues);out.put("target_s19_after_wake",targetCuesAfterWake);
        out.put("eligible_target_s19",eligibleTargetCues);
        out.put("own_s19_total",ownFreshCues);out.put("own_s12_total",ownVelocityCues);
        out.put("return_cue_events_after_wake",returnResets);out.put("unanswered_target_s19",streak);
        out.put("streak_start_wall_ms",streakStartWallMs);out.put("streak_start_monotonic_ns",streakStartMonoNs);
        out.put("trigger_wall_ms",triggerWallMs);out.put("trigger_monotonic_ns",triggerMonoNs);
        out.put("handoff_wall_ms",handoffWallMs);out.put("handoff_monotonic_ns",handoffMonoNs);out.put("handoff_native_tick",handoffTick);
        out.put("end_reason",endReason);out.put("server_return_audit_required",true);
        return out;
    }
}
