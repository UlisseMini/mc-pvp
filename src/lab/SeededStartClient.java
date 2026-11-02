package lab;

import java.nio.file.*;
import java.util.*;

/** Bot-only pre-combat seed. Never changes state or suppresses packets after release. */
public final class SeededStartClient {
    private static SeedState seed;
    private static Properties canonical;
    private static Path directory;
    private static String side="",id="",seedHash="";
    private static double origin;
    private static boolean ready,released,controlStarted;
    private static long suppressed,releaseNs,readAt;
    public static void begin(Properties controls)throws Exception{
        seed=null;canonical=null;ready=released=controlStarted=false;suppressed=releaseNs=readAt=0;id=controls.getProperty("match","");
        if(!controls.containsKey("seed_file"))return;
        String relative=controls.getProperty("seed_file");
        if(!relative.equals("runtime/seed-starts/"+id+"/seed.properties"))throw new IllegalArgumentException("Invalid local seed path");
        directory=R.ROOT.resolve(relative).getParent();seed=SeedState.load(directory.resolve("seed.properties"));
        seedHash=seed.sha256();
        if(!seedHash.equals(controls.getProperty("seed_sha256")))throw new IllegalArgumentException("Seed hash changed");
        canonical=seed.canonicalProperties();side=controls.getProperty("archive_side","");
        if(!side.equals("a")&&!side.equals("b"))throw new IllegalArgumentException("Seed side required");
        origin=Double.parseDouble(controls.getProperty("seed_origin_x"));
    }
    public static boolean active(){return seed==null||(released&&controlStarted);}
    public static boolean initialized(){return seed!=null&&released;}
    public static boolean awaitingInitialization(){return seed!=null&&!released&&ready&&releaseNs!=0;}
    public static boolean seeded(){return seed!=null;}
    public static boolean suppressPacket(Object packet){
        if(seed==null||released||!id.equals(Client.match)||!Boolean.parseBoolean(Client.controls.getProperty("enabled","false")))return false;
        String k=R.classTag(packet);
        boolean stop=k.equals("ip")||k.startsWith("ip$")||k.equals("in")||k.equals("is");
        if(stop)suppressed++;return stop;
    }
    /** Runs before policy input selection, with canonical target resolution complete. */
    public static boolean head(long now,long ns)throws Exception{
        if(seed==null)return false;
        if(released){startControls(now,ns);return false;}
        long hookStart=System.nanoTime();
        Client.clickGate=false;Client.blockKey=false;Client.setKeys(false);
        R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"unPressAllKeys");
        if(!Client.targetReady)return true;
        if(!ready){
            // Resolve reflection mappings and class initialization during the
            // setup barrier, not inside the first combat HEAD. These setup
            // values are applied again at release; no inputs are emitted yet.
            long warmStart=System.nanoTime();SeedState.Side warm=side.equals("a")?seed.a:seed.b;
            SeededEntity.own(Client.player,warm,canonical,side,origin);
            SeededEntity.replica(Client.opponent,warm.view,canonical,side,origin);
            SeededEntity.clientSnapshot(Client.player,Client.opponent,side,origin);
            new Client.Observation(now); // Warm policy observation mappings too.
            R.get(R.get(R.get(Client.mc,"gameSettings"),"keyBindAttack"),"pressTime");
            R.set(Client.mc,"leftClickCounter",0);
            Properties ack=new Properties();ack.setProperty("match",id);ack.setProperty("seed_sha256",seedHash);
            ack.setProperty("side",side);ack.setProperty("ready_ns",Long.toString(ns));
            ack.setProperty("warmup_duration_ns",Long.toString(System.nanoTime()-warmStart));
            ack.setProperty("ready_ms",Long.toString(now));R.writeProperties(Client.DIR.resolve("seed-"+id+"-ready.properties"),ack);ready=true;
        }
        if(releaseNs==0&&ns-readAt>50_000_000L){readAt=ns;
            Properties commit=R.properties(directory.resolve("commit.properties"));
            if(id.equals(commit.getProperty("match"))&&seedHash.equals(commit.getProperty("seed_sha256"))){
                releaseNs=Long.parseLong(commit.getProperty("release_ns"))+(long)((side.equals("a")?seed.phaseAMs:seed.phaseBMs)*1_000_000);
                Client.epoch=Long.parseLong(commit.getProperty("release_ms"));
                Client.controls.setProperty("start_at",Long.toString(Client.epoch));
            }
        }
        if(releaseNs==0||ns<releaseNs)return true;
        initialize(now,ns,hookStart,"input_head");
        startControls(now,ns);
        return false;
    }
    /** Main-thread handler entry, before vanilla changes any field. Never holds
     * or replays a packet. A later input HEAD must preserve these live effects.
     * Ready/commit preparation and canonical target resolution occurred at HEAD.
     */
    public static boolean beforePacket(long now,long ns)throws Exception{
        if(seed==null||released||!ready||releaseNs==0||ns<releaseNs
                ||!id.equals(Client.match)||!Boolean.parseBoolean(Client.controls.getProperty("enabled","false")))return false;
        if(!Client.targetReady)throw new IllegalStateException("Seed target lost at post-deadline packet; invalidate match");
        Client.requireCanonicalSeedEntities();
        initialize(now,ns,System.nanoTime(),"packet_handler");
        return true;
    }
    private static void startControls(long now,long ns){
        if(controlStarted)return;
        controlStarted=true;
        Map<String,Object> event=new LinkedHashMap<String,Object>();
        event.put("type","client_seed_controls_started");event.put("match",id);event.put("side",side);
        event.put("time",now);event.put("monotonic_ns",ns);event.put("native_tick",Client.ticks);
        event.put("handled_target_cue_ms",Double.isFinite(Client.handledTargetHurtMs)?Client.handledTargetHurtMs:null);
        event.put("last_click_ns",Client.lastClick);Client.log(event);
    }
    private static void initialize(long now,long ns,long hookStart,String stage)throws Exception{
        if(released)throw new IllegalStateException("Seed already initialized");
        long applyStart=System.nanoTime();
        SeedState.Side s=side.equals("a")?seed.a:seed.b;
        SeededEntity.own(Client.player,s,canonical,side,origin);
        SeededEntity.replica(Client.opponent,s.view,canonical,side,origin);
        Client.history.clear();Client.previous=null;Client.lastConsumed=null;
        Client.lastClick=ns-Math.round(s.caches.clickAgeMs*1_000_000);Client.firstClickEligible=0;
        Client.freshTargetHurtStatus=s.caches.targetHurtCueAgeMs<0?0:now-Math.round(s.caches.targetHurtCueAgeMs);
        Client.freshHurtStatus=s.caches.ownHurtCueAgeMs<0?0:now-Math.round(s.caches.ownHurtCueAgeMs);
        Client.lastObservedTargetHurt=Client.freshTargetHurtStatus;
        Client.handledTapCue=s.caches.wtapHandledTargetHurtCueAgeMs<0?0:now-Math.round(s.caches.wtapHandledTargetHurtCueAgeMs);
        Client.lastObservedHurt=Client.freshHurtStatus;Client.pendingTapCue=0;
        Client.tapUntil=s.caches.tapRemainingMs>0?now+Math.round(s.caches.tapRemainingMs):0;
        Client.handledTargetHurtMs=s.caches.targetHurtCueAgeMs<0?Double.NaN:ns/1_000_000.0-s.caches.targetHurtCueAgeMs;
        Client.maintenanceOwnHurtMs=s.caches.ownHurtCueAgeMs<0?Double.NaN:ns/1_000_000.0-s.caches.ownHurtCueAgeMs;
        // An already-hurt seed is history, not a newly observed incoming hit.
        // Keep the explicit cue ages instead of inventing a hurt edge at HEAD0.
        Client.lastConsumed=new Client.Observation(now);
        R.set(Client.mc,"leftClickCounter",0);
        Map<String,Object> event=new LinkedHashMap<String,Object>();event.put("type","client_seed_released");
        event.put("match",id);event.put("side",side);event.put("time",now);event.put("monotonic_ns",ns);
        event.put("release_stage",stage);event.put("controls_started",false);
        event.put("native_tick",Client.ticks);event.put("seed_sha256",seedHash);event.put("target_release_ns",releaseNs);
        event.put("hook_entry_ns",hookStart);event.put("body_apply_start_ns",applyStart);
        Map<String,Object> fields=SeededEntity.clientSnapshot(Client.player,Client.opponent,side,origin);
        fields.put(side+".target_hurt_cue_age_ms",Double.isFinite(Client.handledTargetHurtMs)?ns/1_000_000.0-Client.handledTargetHurtMs:-1);
        fields.put(side+".own_hurt_cue_age_ms",Double.isFinite(Client.maintenanceOwnHurtMs)?ns/1_000_000.0-Client.maintenanceOwnHurtMs:-1);
        fields.put(side+".click_age_ms",(ns-Client.lastClick)/1_000_000.0);
        fields.put(side+".tap_remaining_ms",Client.tapUntil>0?Math.max(0,Client.tapUntil-now):0);
        fields.put(side+".wtap_handled_target_hurt_cue_age_ms",Client.handledTapCue>0?now-Client.handledTapCue:-1);
        event.put("origin_x",origin);event.put("fields",fields);
        event.put("last_click_ns",Client.lastClick);event.put("handled_target_cue_ms",Double.isFinite(Client.handledTargetHurtMs)?Client.handledTargetHurtMs:null);
        event.put("handled_own_cue_ms",Double.isFinite(Client.maintenanceOwnHurtMs)?Client.maintenanceOwnHurtMs:null);
        event.put("wtap_handled_cue",Client.handledTapCue);event.put("wtap_until_ms",Client.tapUntil);
        event.put("suppressed_setup_packets",suppressed);event.put("queued_attack_presses",R.get(R.get(R.get(Client.mc,"gameSettings"),"keyBindAttack"),"pressTime"));
        event.put("left_click_counter",R.get(Client.mc,"leftClickCounter"));event.put("apply_complete_ns",System.nanoTime());
        Client.log(event);released=true;
        Properties ack=new Properties();ack.setProperty("match",id);ack.setProperty("seed_sha256",seedHash);
        ack.setProperty("released_ns",Long.toString(ns));R.writeProperties(Client.DIR.resolve("seed-"+id+"-released.properties"),ack);
    }
}
