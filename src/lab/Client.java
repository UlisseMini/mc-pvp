package lab;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Policies drive keys, aim and capped clicks; explicit practice setups can change the native selection reach. */
public final class Client {
    private static ClientServices services=ClientServices.legacy();
    public static void installServices(ClientServices service){services=Objects.requireNonNull(service);}
    public static Properties configuration(){Properties p=new Properties();p.putAll(controls);return p;}
    public static long nativeTicks(){return ticks;}
    public static void releaseInputs(){clickGate=false;blockKey=false;targetReady=false;
        if(mc!=null)try{setKeys(false);R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"unPressAllKeys");}catch(Throwable ignored){}
    }
    static final String SLOT = System.getProperty("lab.slot", "0");
    static final Path DIR = R.ROOT.resolve("runtime/bots/" + SLOT);
    static Properties controls = new Properties();
    static long readAt, connectAt, lastClick, firstClickEligible, epoch, logAt, ticks, errors;
    static String match = "";
    static boolean connecting, forward, clickGate;
    static boolean sprintPulse=true,sprintKey;
    static int strafe;
    static int zestPhase,zestUnanswered;
    static long zestCue,zestIncoming,zestRelease,zestResume,zestEntryAt;
    static boolean zestSelecting,zestOpened;
    static Object mc, player, opponent;
    static final Deque<Observation> history = new ArrayDeque<>();
    static Observation previous;
    static long lastObservedHurt, lastObservedTargetHurt;
    static long handledTapCue,pendingTapCue,tapUntil;
    static String tapReason="";
    static double targetSpeed;
    static long scriptStarted;
    static int scriptIndex;
    static long heartbeatAt,respawnAt,probeAt;
    static final TargetWatchdog targetWatchdog=new TargetWatchdog();
    static final MomentumController momentum=new MomentumController();
    static final OrbitController orbit=new OrbitController();
    static final CycleController cycle=new CycleController();
    static final FrozenLegalCycleController frozenCycle=new FrozenLegalCycleController();
    static final TickIndexedLegalCycleController tickCycle=new TickIndexedLegalCycleController(true,false);
    static StrongRelaxationPolicy strongGap=new StrongRelaxationPolicy();
    static final BurstClickController burstClicks=new BurstClickController();
    static BurstClickController.Decision burstDecision;
    static long burstObservedAt;
    static boolean nativeInputPoll;
    static final CriticalClickController criticalClicks=new CriticalClickController();
    static boolean targetReady;
    static final BlockhitController blockhit=new BlockhitController();
    static final LinearHitPredictor hitPredictor=new LinearHitPredictor();
    static final TimedBlockController timedBlock=new TimedBlockController();
    static final Deque<Long> blockTapCues=new ArrayDeque<>();
    static long blockTapQueued;
    static boolean blockKey;
    static long freshHurtStatus;
    static long freshTargetHurtStatus;
    static final AimController aim=new AimController();
    static Object aimTarget;
    static long aimLogAt;

    static final class Observation {
        long time, tick, freshHurtCue, targetFreshHurtCue; double x,y,z,tx,ty,tz,vx,vy,vz,distance,localRange,closing,ux,uz,tvx,tvz; int hurt,targetHurt; boolean sprint,ground,sentSprint;
        Observation(long time) {
            this.time=time;this.tick=ticks;
            freshHurtCue=freshHurtStatus;
            targetFreshHurtCue=freshTargetHurtStatus;
            x=R.num(player,"posX"); y=R.num(player,"posY"); z=R.num(player,"posZ");
            tx=R.num(opponent,"posX"); ty=R.num(opponent,"posY"); tz=R.num(opponent,"posZ");
            vx=R.num(player,"motionX");vy=R.num(player,"motionY"); vz=R.num(player,"motionZ");
            distance=Math.hypot(tx-x,tz-z); hurt=(int)R.num(player,"hurtTime"); targetHurt=(int)R.num(opponent,"hurtTime");
            Object box=R.call(opponent,"getEntityBoundingBox");
            double border=((Number)R.call(opponent,"getCollisionBorderSize")).doubleValue();
            double eye=y+((Number)R.call(player,"getEyeHeight")).doubleValue();
            double nearX=Math.max(0,Math.max(R.num(box,"minX")-border-x,x-R.num(box,"maxX")-border));
            double nearY=Math.max(0,Math.max(R.num(box,"minY")-border-eye,eye-R.num(box,"maxY")-border));
            double nearZ=Math.max(0,Math.max(R.num(box,"minZ")-border-z,z-R.num(box,"maxZ")-border));
            localRange=Math.sqrt(nearX*nearX+nearY*nearY+nearZ*nearZ);
            sprint=(Boolean)R.call(player,"isSprinting");
            sentSprint=(Boolean)R.get(player,"serverSprintState");
            ground=(Boolean)R.get(player,"onGround");
            if(previous!=null && time>previous.time){
                double dt=(time-previous.time)/1000.0;
                closing=(previous.distance-distance)/dt;
                ux=(x-previous.x)/dt;uz=(z-previous.z)/dt;
                tvx=.5*previous.tvx+.5*(tx-previous.tx)/dt;tvz=.5*previous.tvz+.5*(tz-previous.tz)/dt;
            }
        }
    }

    public static void frame(Object minecraft) {
        if(!"client".equals(System.getProperty("lab.role")))return;
        mc=minecraft;
        if(!nativeInputPoll)ReplicationTrace.frame();
        try {
            if(services!=null)services.frame();
            if(player==null || opponent==null || !targetReady || (Boolean)R.get(opponent,"isDead") || !active())return;
            // Tracking uses only this client's entity view; defaults preserve perfect aim.
            double dx=R.num(opponent,"posX")-R.num(player,"posX"), dz=R.num(opponent,"posZ")-R.num(player,"posZ");
            double eye=R.num(player,"posY")+((Number)R.call(player,"getEyeHeight")).doubleValue();
            long aimNow=System.currentTimeMillis();
            if(aimTarget!=opponent){aim.reset();aimTarget=opponent;}
            AimController.Settings aimSettings=AimController.Settings.from(controls);
            AimController.Result tracking=aim.aim(aimNow,R.num(player,"posX"),eye,R.num(player,"posZ"),
                R.num(opponent,"posX"),R.num(opponent,"posY"),R.num(opponent,"posZ"),
                (float)R.num(player,"rotationYaw"),(float)R.num(player,"rotationPitch"),aimSettings);
            R.set(player,"rotationYaw",tracking.yaw); R.set(player,"rotationPitch",tracking.pitch);
            if(aimNow-aimLogAt>=50){aimLogAt=aimNow;Map<String,Object> a=new LinkedHashMap<>();
                a.put("type","aim_plan");a.put("time",aimNow);a.put("match",match);a.put("applied",Compat.list(tracking.yaw,tracking.pitch));
                a.put("ideal",Compat.list(tracking.idealYaw,tracking.idealPitch));a.put("error_deg",tracking.errorDegrees);
                a.put("sample_age_ms",tracking.sampleAge);a.put("noise",Compat.list(tracking.noiseYaw,tracking.noisePitch));
                a.put("delay_ms",aimSettings.delayMs);a.put("noise_deg",aimSettings.noiseDegrees);a.put("turn_rate_dps",aimSettings.turnRate);a.put("seed",aimSettings.seed);log(a);}
            Object renderer=R.get(mc,"entityRenderer"); R.call(renderer,"getMouseOver",1.0f);
            Object ray=R.get(mc,"objectMouseOver"),selected=ray==null?null:R.get(ray,"entityHit");
            long wall=System.currentTimeMillis();
            Object world=R.get(mc,"theWorld");
            boolean canonical=R.call(world,"getEntityByID",(int)R.call(opponent,"getEntityId"))==opponent;
            boolean staleRay=selected!=null&&selected!=opponent&&R.name(selected).equals(controls.getProperty("opponent",""));
            if(!canonical||!identityContains(R.get(world,"loadedEntityList"),opponent)||staleRay){
                targetWatchdog.invalidate();targetReady=false;clickGate=false;setKeys(false);
                R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"unPressAllKeys");
                log(Compat.map("type","invalid_native_view","time",wall,"match",match,"canonical",canonical,"stale_raycast",staleRay));return;
            }
            if(wall-probeAt>=50){
                probeAt=wall;Map<String,Object> probe=new LinkedHashMap<>();
                probe.put("type","reach_probe");probe.put("time",wall);probe.put("match",match);
                probe.put("in_range",selected==opponent);probe.put("could_attempt",selected==opponent&&R.num(mc,"leftClickCounter")<=0);
                probe.put("attack_reach",attackReach());
                if(selected==opponent)probe.put("selected_ray_distance",R.call(R.call(player,"getPositionEyes",1.0f),"distanceTo",R.get(ray,"hitVec")));
                probe.put("miss_cooldown",R.num(mc,"leftClickCounter"));probe.put("target_id",R.call(opponent,"getEntityId"));
                probe.put("raycast_stale",selected!=null&&selected!=opponent&&R.name(selected).equals(controls.getProperty("opponent","")));
                if(ScienceTrace.client()){
                    probe.put("own",ScienceTrace.state(player));probe.put("target",ScienceTrace.state(opponent));
                    probe.put("eye_height",R.call(player,"getEyeHeight"));
                    Object eyeVector=R.call(player,"getPositionEyes",1.0f),lookVector=R.call(player,"getLook",1.0f);
                    probe.put("native_eye",Compat.list(R.num(eyeVector,"xCoord"),R.num(eyeVector,"yCoord"),R.num(eyeVector,"zCoord")));
                    probe.put("native_look",Compat.list(R.num(lookVector,"xCoord"),R.num(lookVector,"yCoord"),R.num(lookVector,"zCoord")));
                    Object camera=R.call(mc,"getRenderViewEntity");probe.put("camera_is_player",camera==player);
                    probe.put("target_collidable",R.call(opponent,"canBeCollidedWith"));
                    double border=((Number)R.call(opponent,"getCollisionBorderSize")).doubleValue();
                    Object box=R.call(R.call(opponent,"getEntityBoundingBox"),"expand",border,border,border);
                    probe.put("expanded_box",Compat.list(R.num(box,"minX"),R.num(box,"minY"),R.num(box,"minZ"),R.num(box,"maxX"),R.num(box,"maxY"),R.num(box,"maxZ")));
                    double reach=attackReach();
                    Object end=R.call(eyeVector,"addVector",R.num(lookVector,"xCoord")*reach,R.num(lookVector,"yCoord")*reach,R.num(lookVector,"zCoord")*reach);
                    Object intercept=R.call(box,"calculateIntercept",eyeVector,end);
                    probe.put("native_box_intercept",intercept!=null);
                    if(intercept!=null)probe.put("native_box_ray_distance",R.call(eyeVector,"distanceTo",R.get(intercept,"hitVec")));
                    probe.put("ray_type",ray==null?"null":R.get(ray,"typeOfHit").toString());
                    probe.put("selected_entity",selected==null?"":R.name(selected));
                }
                log(probe);
            }
            MotionClickController.Decision motionClick=null;
            double radialMotion=0;boolean localSprint=false,localContactReady=false,criticalOpportunity=false,localCritical=false;
            if(controls.getProperty("click_mode","fixed").equals("motion-burst") ||
                    controls.getProperty("kind","").equals("orbit")&&orbit.maintaining()&&number("orbit_selective",0)>0){
                if(number("reaction_ms",150)!=0)throw new IllegalArgumentException("Motion burst requires zero artificial reaction delay");
                radialMotion=(R.num(player,"motionX")*dx+R.num(player,"motionZ")*dz)/Math.max(.001,Math.hypot(dx,dz));
                localSprint=(Boolean)R.call(player,"isSprinting");
                localContactReady=selected==opponent&&R.num(mc,"leftClickCounter")<=0;
                motionClick=MotionClickController.decide(new MotionClickController.Input(wall,lastObservedTargetHurt,
                        number("ping",0),radialMotion,localSprint,nativeInputPoll,localContactReady),MotionClickController.Settings.from(controls));
                if(number("burst_critical",0)>0&&nativeInputPoll&&localContactReady){
                    // Native 1.8 critical predicates, using only our current local
                    // body. The server can still disagree at a movement boundary.
                    localCritical=R.num(player,"fallDistance")>0&&!(Boolean)R.get(player,"onGround")
                            &&!(Boolean)R.call(player,"isOnLadder")&&!(Boolean)R.call(player,"isInWater")
                            &&!(Boolean)R.call(player,"isPotionActive",R.get(R.cls("net.minecraft.potion.Potion"),"blindness"))
                            &&R.get(player,"ridingEntity")==null;
                    criticalOpportunity=criticalClicks.eligible(wall,lastObservedTargetHurt,motionClick.attackStartAt(),
                            motionClick.opening(),motionClick.attackWindow(),nativeInputPoll,localContactReady,localCritical);
                }
                clickGate=motionClick.click()||criticalOpportunity;
                if(nativeInputPoll){
                    Map<String,Object> plan=new LinkedHashMap<>();plan.put("type","motion_click_plan");plan.put("time",wall);
                    plan.put("monotonic_ns",System.nanoTime());plan.put("match",match);plan.put("native_tick",ticks);
                    plan.put("observation_time",wall);plan.put("landed_cue",lastObservedTargetHurt);
                    plan.put("radial_motion",radialMotion);plan.put("local_sprint",localSprint);
                    plan.put("local_contact_ready",localContactReady);
                    plan.put("local_critical",localCritical);plan.put("critical_opportunity",criticalOpportunity);
                    plan.put("opening",motionClick.opening());plan.put("attack_window",motionClick.attackWindow());
                    plan.put("recoil",motionClick.recoil());plan.put("recoil_opportunity",motionClick.recoilOpportunity());
                    plan.put("reserved",motionClick.reserved());plan.put("estimated_ready_at",motionClick.readyAt());
                    plan.put("attack_start_at",motionClick.attackStartAt());plan.put("click_gate",clickGate);log(plan);
                }
            }
            long now=System.nanoTime();
            if(clickGate&&firstClickEligible==0)firstClickEligible=now;
            long firstDelay=(long)Math.max(0,Math.min(100,number("first_click_delay_ms",0)))*1_000_000L;
            // Contact-clicking is a normal input policy: begin the capped
            // cadence when the native ray selects the opponent. It does not
            // inspect immunity, accepted hits or any server-only state.
            boolean contactClicks=controls.getProperty("click_mode","fixed").equals("contact");
            if(clickGate && (!controls.getProperty("kind","").equals("cycle")||nativeInputPoll) && (!contactClicks||selected==opponent) && now-firstClickEligible>=firstDelay && ClickRate.ready(now,lastClick,controls) && R.get(mc,"currentScreen")==null) {
                Object key=R.get(R.get(mc,"gameSettings"),"keyBindAttack");
                R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"onTick",(int)R.call(key,"getKeyCode"));
                if(controls.getProperty("kind","").equals("cycle")){cycle.queued(ticks);clickGate=false;}
                if(controls.getProperty("kind","").equals("frozen-cycle") || System.currentTimeMillis()-epoch<number("frozen_cycle_opening_ms",0))clickGate=frozenCycle.queued();
                if(controls.getProperty("kind","").equals("tick-cycle"))clickGate=tickCycle.queued();
                // A planned Ctrl pulse ends after the first queued click. Native
                // sprint persists until its ordinary stop condition/attack; later
                // clicks do not automatically re-enable it. No hit-result oracle.
                if(controls.getProperty("sprint_mode","hold").equals("cycle"))sprintPulse=false;
                // Pace from completed input injection, not from before reflection/GC pauses.
                // No catch-up inputs: wait the full declared interval after completion.
                lastClick=System.nanoTime();
                if(criticalOpportunity)criticalClicks.injected(lastObservedTargetHurt);
                ModelController.click(System.currentTimeMillis());
                Map<String,Object> issued=new LinkedHashMap<>();
                issued.put("type","click");issued.put("time",System.currentTimeMillis());issued.put("monotonic_ns",lastClick);
                issued.put("match",match);issued.put("forward",forward);
                issued.put("input_poll",nativeInputPoll?"native_tick":"frame");
                issued.put("reaction_ms",(int)number("reaction_ms",150));
                issued.put("click_cps",ClickRate.cps(controls));
                issued.put("attack_reach",attackReach());
                issued.put("native_target_in_range",selected==opponent);
                issued.put("minimum_click_interval_ns",ClickRate.intervalNanos(controls));
                if(controls.getProperty("click_mode","fixed").equals("reactive-burst")&&burstDecision!=null){
                    issued.put("burst_opening",burstDecision.opening());issued.put("burst_defensive",burstDecision.defensive());
                    issued.put("burst_attack_window",burstDecision.attackWindow());issued.put("burst_observation_time",burstObservedAt);
                }
                if(motionClick!=null){
                    issued.put("burst_opening",motionClick.opening());issued.put("burst_attack_window",motionClick.attackWindow());
                    issued.put("burst_recoil",motionClick.recoil());issued.put("burst_observation_time",wall);
                    issued.put("burst_radial_motion",radialMotion);issued.put("burst_local_sprint",localSprint);
                    issued.put("burst_local_contact_ready",localContactReady);
                    issued.put("burst_critical",criticalOpportunity);issued.put("burst_local_critical",localCritical);
                    issued.put("burst_landed_cue",lastObservedTargetHurt);
                    issued.put("burst_attack_start_at",motionClick.attackStartAt());
                }
                log(issued);
            }
        }catch(Throwable e){fail(e);}
    }

    public static void tick(Object minecraft) {
        if(!"client".equals(System.getProperty("lab.role")))return;
        mc=minecraft; ticks++;
        try {
            long now=System.currentTimeMillis();
            if(now-readAt>100){
                Properties nextControls=R.properties(DIR.resolve("control.properties"));
                validateControls(nextControls);controls=nextControls;readAt=now;
            }
            player=R.get(mc,"thePlayer"); Object world=R.get(mc,"theWorld");
            if(services!=null)services.tick();
            if(player==null || world==null){
                releaseInputs();opponent=null;
                if(connecting&&now-connectAt>5000)connecting=false;
                if(!connecting && ticks>30){
                    connecting=true;connectAt=now;
                    Object gui=R.construct("net.minecraft.client.multiplayer.GuiConnecting",null,mc,"127.0.0.1",Integer.parseInt(System.getProperty("lab.port")));
                    R.call(mc,"displayGuiScreen",gui);
                    System.out.println("[PvP Lab] Connecting slot "+SLOT);
                }
                return;
            }
            if(((Number)R.call(player,"getHealth")).floatValue()<=0 && now-respawnAt>1000){
                // Ordinary respawn input, including recovery from an interrupted old round.
                setKeys(false);clickGate=false;R.call(player,"respawnPlayer");respawnAt=now;return;
            }
            if(now-heartbeatAt>1000){
                heartbeatAt=now;List<String> visible=new ArrayList<>();List<Map<String,Object>> entities=new ArrayList<>();
                for(Object e:(List<?>)R.get(world,"playerEntities")){
                    visible.add(R.name(e));entities.add(Compat.map("name",R.name(e),"id",R.call(e,"getEntityId"),"dead",R.get(e,"isDead"),"x",R.num(e,"posX"),"y",R.num(e,"posY"),"z",R.num(e,"posZ"),"canonical",R.call(world,"getEntityByID",(int)R.call(e,"getEntityId"))==e));
                }
                Map<String,Object> heartbeat=new LinkedHashMap<>();heartbeat.put("time",now);heartbeat.put("match",controls.getProperty("match",""));heartbeat.put("enabled",active());
                heartbeat.put("health",R.call(player,"getHealth"));heartbeat.put("alive",R.call(player,"isEntityAlive"));heartbeat.put("dead",R.get(player,"isDead"));
                heartbeat.put("visible_players",visible);heartbeat.put("x",R.num(player,"posX"));heartbeat.put("y",R.num(player,"posY"));heartbeat.put("z",R.num(player,"posZ"));
                heartbeat.put("entities",entities);
                heartbeat.put("selected_target_dead",opponent!=null&&(Boolean)R.get(opponent,"isDead"));
                if(opponent!=null){heartbeat.put("target_ticks",R.num(opponent,"ticksExisted"));heartbeat.put("target_region_loaded",regionLoaded(world,opponent));}
                heartbeat.put("agent_sha256",System.getProperty("lab.agent_sha256","unknown"));
                heartbeat.put("backend",System.getProperty("lab.backend","legacy"));heartbeat.put("role","bot");
                heartbeat.put("policy_errors",errors);
                heartbeat.put("bridge_sha256",System.getProperty("practice.bridge_sha256",""));
                heartbeat.put("module_sha256",System.getProperty("practice.module_sha256",""));
                heartbeat.put("frame_sleep_ms",Integer.getInteger("hmc.lwjgl.update_sleep",10));
                heartbeat.put("target_ready",targetReady);
                heartbeat.put("attack_reach",attackReach());heartbeat.put("click_cps",ClickRate.cps(controls));
                heartbeat.put("native_ticks",ticks);
                Object screen=R.get(mc,"currentScreen");heartbeat.put("screen",screen==null?null:screen.getClass().getName());
                Files.createDirectories(DIR);Compat.writeString(DIR.resolve("heartbeat.json"),Json.encode(heartbeat));
                Properties integrity=new Properties();integrity.setProperty("time",Long.toString(now));
                integrity.setProperty("match",controls.getProperty("match",""));integrity.setProperty("target_ready",Boolean.toString(targetReady));
                R.writeProperties(DIR.resolve("view.properties"),integrity);
            }
            if(System.getProperty("lab.test_script")!=null){
                if(scriptStarted==0)scriptStarted=now;
                String[] script=System.getProperty("lab.test_script").split("\\|");
                if(scriptIndex<script.length){String[] step=script[scriptIndex].split(":",2);if(now-scriptStarted>=Long.parseLong(step[0])){R.call(player,"sendChatMessage",step[1]);scriptIndex++;}}
                if(now-scriptStarted>Long.parseLong(System.getProperty("lab.test_duration","20000")))R.call(mc,"shutdown");
            }
            if(services!=null&&services.probe(mc,player,now-scriptStarted))return;
            String next=controls.getProperty("match","");
            if(!next.equals(match)){
                match=next;epoch=Long.parseLong(controls.getProperty("start_at",Long.toString(now)));history.clear();previous=null;lastConsumed=null;lastClick=firstClickEligible=0;
                lastObservedHurt=lastObservedTargetHurt=0;
                handledTapCue=pendingTapCue=tapUntil=0;tapReason="";
                zestPhase=zestUnanswered=0;zestCue=zestIncoming=zestRelease=zestResume=zestEntryAt=0;
                zestSelecting=controls.getProperty("entry","select").equals("select");
                zestOpened=false;
                sprintPulse=true;
                ModelController.reset();
                momentum.reset();
                orbit.reset();
                cycle.reset();
                frozenCycle.reset();
                tickCycle.reset();
                strongGap=new StrongRelaxationPolicy();freshTargetHurtStatus=0;
                burstClicks.reset();burstDecision=null;burstObservedAt=0;
                criticalClicks.reset();
                blockhit.reset();blockKey=false;freshHurtStatus=0;
                hitPredictor.reset();timedBlock.reset();blockTapCues.clear();blockTapQueued=0;
                targetWatchdog.reset();targetReady=false;
                aim.reset();aimTarget=null;aimLogAt=0;
            }
            opponent=null;
            for(Object entity:(List<?>)R.get(world,"playerEntities")){
                if(!R.name(entity).equals(controls.getProperty("opponent","")))continue;
                // The list can retain replaced copies after spectator/arena
                // transitions, even copies not yet marked dead. Packets update
                // the entity-ID map, so resolve the canonical native object.
                Object live=R.call(world,"getEntityByID",(int)R.call(entity,"getEntityId"));
                if(live!=null && !(Boolean)R.get(live,"isDead") && R.name(live).equals(controls.getProperty("opponent",""))){opponent=live;break;}
            }
            targetReady=opponent!=null&&targetWatchdog.update(opponent,now,
                (long)R.num(opponent,"ticksExisted"),identityContains(R.get(world,"loadedEntityList"),opponent),
                (Boolean)R.get(opponent,"addedToChunk"),regionLoaded(world,opponent));
            if(!active() || !targetReady){
                setKeys(false);clickGate=false;blockKey=false;
                // Invalid world/setup: also discard an already queued physical
                // click. This guard never filters ordinary immune-frame clicks.
                R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"unPressAllKeys");
                if(opponent!=null&&now-probeAt>=250){
                    probeAt=now;Map<String,Object> health=new LinkedHashMap<>();
                    health.put("type","target_guard");health.put("time",now);health.put("match",match);
                    health.put("target_ticks",R.num(opponent,"ticksExisted"));health.put("target_loaded_identity",identityContains(R.get(world,"loadedEntityList"),opponent));
                    health.put("target_added_to_chunk",R.get(opponent,"addedToChunk"));health.put("target_region_loaded",regionLoaded(world,opponent));
                    health.put("target_spawn_queue_identity",identityContains(R.get(world,"entitySpawnQueue"),opponent));
                    health.put("target_unloaded_identity",identityContains(R.get(world,"unloadedEntityList"),opponent));log(health);
                }
                if(controls.getProperty("kind","").equals("mpcprobe"))ModelController.idle(now);return;
            }
            if(R.get(mc,"currentScreen")!=null)R.call(mc,"displayGuiScreen",(Object)null);
            R.call(mc,"setIngameFocus");
            Observation sample=new Observation(now); history.addLast(sample); previous=sample;
            long reaction=(long)number("reaction_ms",150);
            if(reaction<0||reaction>1000)throw new IllegalArgumentException("Invalid reaction delay");
            long cutoff=now-reaction;
            if(controls.getProperty("kind","").equals("mpcprobe")){
                // Feed all eligible native ticks before deque compaction. The
                // visible interpolation inverse needs consecutive samples.
                for(Observation eligible:history){
                    if(eligible.time>cutoff)break;
                    ModelController.observeEligible(eligible);
                }
            }
            while(history.size()>1 && history.stream().skip(1).findFirst().get().time<=cutoff)history.removeFirst();
            Observation obs=history.peekFirst();
            if(obs.time>cutoff){setKeys(false);clickGate=false;if(controls.getProperty("kind","").equals("mpcprobe"))ModelController.idle(now);return;}
            // The declared artificial observation delay can be zero in fast policies.
            Observation before=lastConsumed;
            if(before==null || obs.time>before.time){
                if(obs.hurt>0 && (before==null || obs.hurt>before.hurt))lastObservedHurt=obs.time;
                if(controls.getProperty("target_hurt_cue","sampled").equals("status")){
                    if(obs.targetFreshHurtCue>lastObservedTargetHurt)lastObservedTargetHurt=obs.targetFreshHurtCue;
                }else if(obs.targetHurt>0 && (before==null || obs.targetHurt>before.targetHurt))lastObservedTargetHurt=obs.time;
                lastConsumed=obs;
            }
            String kind=controls.getProperty("kind","hold");
            double age=(now-obs.time)/1000.0;
            double ping=number("ping",0), oppPing=number("opponent_ping",0);
            double horizon=age+number("horizon_ms",80)/1000.0;
            double gap=obs.distance-Math.max(-8,Math.min(8,obs.closing))*horizon;
            double desired=number("gap",2.9), margin=number("margin",.10);
            if(kind.equals("phase")){
                // Predict next ordinary equal-damage hit window from delayed visible cues.
                // This changes movement only. All nominal clicks still reach the engine.
                double nextServerWindow=lastObservedTargetHurt-ping/2.0+500;
                double untilLocalClick=Math.max(0,nextServerWindow-ping/2.0-now)/1000.0;
                gap=obs.distance-Math.max(-8,Math.min(8,obs.closing))*(age+Math.min(.5,untilLocalClick));
            }
            boolean w=true;
            if(kind.equals("standstill"))w=false;
            if(kind.equals("periodic"))w=(now-epoch)%(long)number("period_ms",400)>=(long)number("pause_ms",50);
            if(kind.equals("hitreset"))w=now-lastObservedTargetHurt>reaction+number("pause_ms",50);
            boolean timedBlockTap=kind.equals("blockhit")&&number("block_wtap",0)>0;
            if(kind.equals("wtap") || kind.equals("tradetap") || timedBlockTap){
                // A landed-hit cue arms one reset. No periodic taps and no hidden server feedback.
                if(timedBlockTap){
                    if(lastObservedTargetHurt>blockTapQueued){blockTapCues.addLast(lastObservedTargetHurt);blockTapQueued=lastObservedTargetHurt;}
                    while(!blockTapCues.isEmpty()&&now>=blockTapCues.peekFirst()+(long)number("block_tap_delay_ms",0))pendingTapCue=blockTapCues.removeFirst();
                }else if(lastObservedTargetHurt>handledTapCue)pendingTapCue=lastObservedTargetHurt;
                if(pendingTapCue>0){
                    boolean traded=lastObservedHurt>=pendingTapCue-number("trade_window_ms",200);
                    boolean waited=now-pendingTapCue>=reaction+number("reply_wait_ms",150)+oppPing/2;
                    if(kind.equals("wtap") || timedBlockTap || traded || waited){
                        tapUntil=now+(long)number("pause_ms",50);handledTapCue=pendingTapCue;pendingTapCue=0;
                        tapReason=traded?"landed_hit_after_received_hit":waited?"landed_hit_reply_wait_expired":"landed_hit";
                        log(Compat.map("type","wtap","time",now,"match",match,"cue_time",handledTapCue,"release_until",tapUntil,"reason",tapReason));
                    }
                }
                w=now>=tapUntil;
            }
            if(kind.equals("spacing") || kind.equals("predictive") || kind.equals("phase")) {
                // Friction creates coasting: release before the desired future gap is crossed.
                double ownToward=(obs.vx*(obs.tx-obs.x)+obs.vz*(obs.tz-obs.z))/Math.max(.001,obs.distance);
                double coast=Math.max(0,ownToward)/(1-.546);
                double latencyLead=kind.equals("predictive")?(ping+oppPing)*Math.max(0,obs.closing)/4000.0:0;
                double projected=gap-coast-latencyLead;
                w=forward?projected>desired-margin:projected>desired+margin;
            }
            int lateral=0;
            clickGate=true;
            if(kind.equals("zest")){
                // Count delayed, visible hurt animations, never referee counters.
                boolean received=lastObservedHurt>zestIncoming;
                boolean landed=lastObservedTargetHurt>zestCue;
                int chain=Math.max(2,Math.min(3,(int)number("cycle_hits",3)));
                if(received){
                    boolean lost=zestUnanswered>=2 && number("recover",1)>0;
                    zestIncoming=lastObservedHurt;zestUnanswered=0;
                    if(zestSelecting || lost){
                        zestSelecting=true;zestEntryAt=now+(long)number("select_delay_ms",0);
                        zestPhase=0;zestRelease=zestResume=0;
                        log(Compat.map("type","zest_select","time",now,"match",match,"cue_time",zestIncoming,"resume_at",zestEntryAt,"recovery",lost));
                    }
                }
                if(landed){
                    zestCue=lastObservedTargetHurt;zestUnanswered++;
                    zestPhase=(zestPhase+1)%chain;
                    if(zestPhase==chain-1){
                        // One contact earlier, plan a W-up hit. The visible cue arrives
                        // after outbound+inbound latency; subtract RTT to predict our
                        // next local attack opportunity. Execution is preplanned, not
                        // a sub-150-ms reaction to a new event.
                        zestRelease=Math.max(now,zestCue+(long)(number("hit_window_ms",500)-ping-number("release_lead_ms",100)));
                        zestResume=zestRelease+(long)number("release_ms",200);
                        log(Compat.map("type","zest_plan","time",now,"match",match,"cue_time",zestCue,"phase",zestPhase,"release_at",zestRelease,"resume_at",zestResume));
                    }
                }
                if(zestSelecting && zestEntryAt>0 && now>=zestEntryAt)zestSelecting=false;
                // A non-attacking opponent must not leave an entry-select bot inert.
                if(zestSelecting && zestEntryAt==0 && now-epoch>number("entry_timeout_ms",2000))zestSelecting=false;
                clickGate=!zestSelecting;
                w=!(now>=zestRelease && now<zestResume);
                if(number("strafe",0)>0){
                    // Lateral normal keys only, with a direction chosen per hit cycle.
                    // A/D is confined to the explicitly requested strafing variant.
                    long cycle=(long)number("strafe_period_ms",1500);
                    lateral=((now-epoch)/cycle)%2==0?1:-1;
                    if(number("strafe_during_release_only",0)>0 && w)lateral=0;
                }
                // Optional spacing ceiling is a movement policy, not an attack filter.
                if(number("spacing",0)>0 && !zestSelecting && now-lastObservedHurt>number("spacing_after_ms",750)){
                    double predicted=obs.distance-Math.max(-8,Math.min(8,obs.closing))*(age+number("spacing_horizon_ms",50)/1000.0);
                    if(predicted<number("zest_gap",2.7))w=false;
                }
                if(number("lock_control",0)>0){
                    double nx=(obs.tx-obs.x)/Math.max(.001,obs.distance),nz=(obs.tz-obs.z)/Math.max(.001,obs.distance);
                    double ownAlong=obs.ux*nx+obs.uz*nz,targetAlong=obs.tvx*nx+obs.tvz*nz;
                    // Predict our visible separation, then estimate the opponent's
                    // older view of us. Position age includes network and native
                    // tracking/interpolation, not just the declared RTT.
                    double viewAge=(ping+oppPing)/2000+number("interpolation_ms",150)/1000.0;
                    double ourGap=obs.distance+(targetAlong-ownAlong)*(age+number("control_horizon_ms",50)/1000.0);
                    double theirGap=ourGap+(targetAlong+ownAlong)*viewAge;
                    double vertical=Math.max(0,Math.max(obs.ty-.1-(obs.y+1.62),(obs.y+1.62)-(obs.ty+1.9)));
                    double reach=Math.sqrt(Math.max(0,9-vertical*vertical))+.4;
                    double theirVertical=Math.max(0,Math.max(obs.y-.1-(obs.ty+1.62),(obs.ty+1.62)-(obs.y+1.9)));
                    double theirReach=Math.sqrt(Math.max(0,9-theirVertical*theirVertical))+.4;
                    // Coasting matters after release; using instantaneous gap alone
                    // brakes too late and repeatedly closes the opponent's window.
                    double friction=obs.ground?.546:.91;
                    double coastTicks=Math.max(1,Math.min(10,number("release_ms",200)/50));
                    double coast=Math.max(0,obs.vx*nx+obs.vz*nz)*(1-Math.pow(friction,coastTicks))/(1-friction);
                    double upper=reach-number("lock_margin",.2);
                    double lower=theirReach-(targetAlong+ownAlong)*viewAge+number("deny_margin",.1);
                    double desiredGap=lower+Math.max(0,upper-lower)*number("window_bias",.9);
                    boolean pursuing=targetAlong>number("chase_speed",.3) && ownAlong>0;
                    if(pursuing && lower<upper && lastObservedTargetHurt>0 && !zestSelecting){
                        w &= ourGap-coast>desiredGap+(forward?-number("lock_hysteresis",.06):number("lock_hysteresis",.06));
                    }
                    if(number("adaptive_reset",0)>0 && landed && lower>=upper && !zestSelecting){
                        // When no one-sided interval is predicted, re-establish
                        // separation with a post-hit sprint reset. In a viable
                        // pursuit window retain ordinary Zest contacts instead.
                        zestRelease=now;zestResume=now+(long)number("emergency_tap_ms",50);zestPhase=0;w=false;
                        log(Compat.map("type","zest_plan","time",now,"match",match,"cue_time",zestCue,"phase",0,"release_at",zestRelease,"resume_at",zestResume,"reason","predicted_window_collapsed"));
                    }
                    if(number("opening_range",0)>0 && !zestOpened && !zestSelecting){
                        // Deliberate opening hit selection: do not start a click
                        // train in empty air. Once started, keep its native misses.
                        zestOpened=ourGap<=reach-number("opening_margin",.05);clickGate=zestOpened;
                    }
                    if(now-logAt>=50)log(Compat.map("type","lock_estimate","time",now,"observation_time",obs.time,"match",match,"our_gap",ourGap,"their_gap",theirGap,"target_away_speed",targetAlong,"own_toward_speed",ownAlong,"desired_gap",desiredGap,"pursuing",pursuing));
                }
            }
            setKeys(w,lateral);
            if(kind.equals("mpcprobe")){
                ComboModel.Action action=ModelController.decide(obs,now);
                setKeys(action.forward,0,action.sprintKey);
            }
            if(kind.equals("momentum")){
                double nx=(obs.tx-obs.x)/Math.max(.001,obs.distance),nz=(obs.tz-obs.z)/Math.max(.001,obs.distance);
                MomentumController.Decision decision=momentum.decide(new MomentumController.Input(
                    now,obs.time,lastObservedTargetHurt,lastObservedHurt,ModelController.lastInjection,obs.distance,
                    obs.ux*nx+obs.uz*nz,obs.tvx*nx+obs.tvz*nz,obs.vx*nx+obs.vz*nz,
                    obs.y,obs.ty,obs.ground,ping,oppPing),MomentumController.Settings.from(controls));
                setKeys(decision.forward(),decision.lateral(),decision.sprintKey());
                if(decision.resetStarted())log(Compat.map("type","wtap","time",now,"match",match,
                    "cue_time",decision.cue(),"release_until",decision.releaseUntil(),"reason","momentum_planned_reset",
                    "pause_ticks",controls.getProperty("pause_ticks","0"),"native_tick",ticks,"last_click_ms",ModelController.lastInjection));
                if(now-logAt>=50){
                    Map<String,Object> entry=new LinkedHashMap<>();entry.put("type","momentum_plan");entry.put("time",now);
                    entry.put("observation_time",obs.time);entry.put("match",match);entry.put("forward",decision.forward());
                    entry.put("reset_skipped",decision.resetSkipped());entry.put("landed_cues",decision.landedCount());
                    entry.put("scheduled_reset_at",decision.scheduledResetAt());
                    entry.put("sprint_key",decision.sprintKey());
                    entry.put("pursuing",decision.pursuing());entry.put("predicted_gap",decision.predictedGap());
                    entry.put("desired_gap",decision.desiredGap());entry.put("window_lower",decision.lower());entry.put("window_upper",decision.upper());log(entry);
                }
            }
            if(kind.equals("orbit")){
                OrbitController.Settings orbitSettings=OrbitController.Settings.from(controls);
                double nx=(obs.tx-obs.x)/Math.max(.001,obs.distance),nz=(obs.tz-obs.z)/Math.max(.001,obs.distance);
                OrbitController.Decision decision=orbit.decide(now,lastObservedTargetHurt,lastObservedHurt,obs.localRange,
                    obs.vx*nx+obs.vz*nz,obs.tvx*nx+obs.tvz*nz,obs.ground,obs.sprint,orbitSettings);
                setKeys(decision.forward,0,decision.sprintKey);
                clickGate=decision.click;
                if(decision.resetStarted)log(Compat.map("type","wtap","time",now,"match",match,
                    "cue_time",lastObservedTargetHurt,"release_until",now+50,"reason",decision.maintenance?"orbit_repair":"orbit_acquisition","pause_ticks",1,"native_tick",ticks));
                Map<String,Object> plan=new LinkedHashMap<>();plan.put("type","orbit_plan");plan.put("time",now);plan.put("match",match);
                plan.put("observation_time",obs.time);plan.put("landed_cue",lastObservedTargetHurt);plan.put("incoming_cue",lastObservedHurt);
                plan.put("mode",decision.maintenance?"maintenance":"acquire");plan.put("unanswered_cues",decision.unanswered);
                plan.put("opening_wait",decision.openingWait);plan.put("local_range",obs.localRange);plan.put("repair_every",orbitSettings.repairEvery);
                plan.put("projected_range",Double.isFinite(decision.projectedRange)?decision.projectedRange:null);
                plan.put("spacing_range",orbitSettings.spacingRange);
                plan.put("click_gate",decision.click);plan.put("min_contact_ms",orbitSettings.minContactMs);
                plan.put("forward",decision.forward);plan.put("sprint_key",decision.sprintKey);plan.put("local_sprint",obs.sprint);
                plan.put("sent_sprint",obs.sentSprint);plan.put("scheduled_reset_at",decision.scheduledResetAt);log(plan);
            }
            if(kind.equals("cycle")){
                CycleController.Settings settings=CycleController.Settings.from(controls);
                CycleController.Decision d=cycle.decide(ticks,obs.localRange,settings,now,lastObservedTargetHurt,ping);
                setKeys(d.forward,d.anchored?settings.strafe:0,d.sprint);clickGate=d.click;
                Map<String,Object> plan=new LinkedHashMap<>();plan.put("type","cycle_plan");plan.put("time",now);plan.put("match",match);
                plan.put("native_tick",ticks);plan.put("phase",d.phase);plan.put("coast_ticks",d.coastTicks);plan.put("anchored",d.anchored);
                plan.put("local_range",obs.localRange);plan.put("forward",d.forward);plan.put("click_gate",d.click);
                plan.put("local_sprint",obs.sprint);plan.put("sent_sprint",obs.sentSprint);plan.put("landed_cue",lastObservedTargetHurt);log(plan);
            }
            // Matched maintenance baseline: shadow ordinary W-tap above, but
            // apply the same acquisition inputs until the declared switch.
            if(kind.equals("frozen-cycle") || now-epoch<number("frozen_cycle_opening_ms",0)){
                FrozenLegalCycleController.Decision d=frozenCycle.decide(ticks,now-epoch,now,lastObservedTargetHurt);
                setKeys(d.key!=0,0,d.key==2);clickGate=d.click;
                Map<String,Object> plan=new LinkedHashMap<>();plan.put("type","frozen_cycle_plan");plan.put("time",now);plan.put("match",match);
                plan.put("native_tick",ticks);plan.put("observation_time",obs.time);plan.put("landed_cue",lastObservedTargetHurt);
                plan.put("phase",d.phase);plan.put("contact_phase",d.contactPhase);plan.put("maintenance",d.maintenance);
                plan.put("key",d.key);plan.put("click_gate",d.click);plan.put("persistent",d.persistent);plan.put("local_range",obs.localRange);
                plan.put("local_sprint",obs.sprint);plan.put("sent_sprint",obs.sentSprint);log(plan);
            }
            if(kind.equals("tick-cycle")){
                TickIndexedLegalCycleController.Decision d=tickCycle.decide(ticks,now-epoch,now,lastObservedTargetHurt);
                setKeys(d.key!=0,0,d.key==2);clickGate=d.click;
                Map<String,Object> plan=new LinkedHashMap<>();plan.put("type","tick_cycle_plan");plan.put("time",now);plan.put("match",match);
                plan.put("native_tick",ticks);plan.put("observation_time",obs.time);plan.put("landed_cue",lastObservedTargetHurt);
                plan.put("cue_tick",d.cueTick);plan.put("phase",d.phase);plan.put("contact_phase",d.contactPhase);plan.put("maintenance",d.maintenance);
                plan.put("key",d.key);plan.put("click_gate",d.click);plan.put("persistent",d.persistent);plan.put("local_range",obs.localRange);
                plan.put("local_sprint",obs.sprint);plan.put("sent_sprint",obs.sentSprint);log(plan);
            }
            if(kind.equals("gap-strafe") || now-epoch<number("gap_opening_ms",0)){
                StrongRelaxationPolicy.Control d=strongGap.step(obs.localRange);
                // Native positive moveStrafe means Left; our key helper uses
                // negative lateral for Left and positive lateral for Right.
                int side=d.strafe>0?-1:d.strafe<0?1:0;
                setKeys(d.key!=0,side,d.key==2);clickGate=d.click;
                Map<String,Object> plan=new LinkedHashMap<>();plan.put("type","gap_strafe_plan");plan.put("time",now);plan.put("match",match);
                plan.put("native_tick",ticks);plan.put("observation_time",obs.time);plan.put("local_range",obs.localRange);
                plan.put("key",d.key);plan.put("native_strafe",d.strafe);plan.put("lateral_key",side);plan.put("click_gate",d.click);
                plan.put("local_sprint",obs.sprint);plan.put("sent_sprint",obs.sentSprint);log(plan);
            }
            if(controls.getProperty("click_mode","fixed").equals("none"))clickGate=false;
            if(kind.equals("blockhit")){
                boolean linear=controls.getProperty("block_model","legacy").equals("linear");
                Map<String,Object> plan=new LinkedHashMap<>();boolean requested;String reason;
                if(linear){
                    // Poll the same capped native left-click input before the
                    // block admission check, so left/right can share this tick.
                    if(reaction==0){nativeInputPoll=true;try{frame(mc);}finally{nativeInputPoll=false;}}
                    LinearHitPredictor.Prediction prediction=hitPredictor.predict(new LinearHitPredictor.Sample(obs.time,obs.x,obs.y,obs.z,obs.tx,obs.ty,obs.tz),
                        obs.freshHurtCue,ping,oppPing,LinearHitPredictor.Settings.from(controls));
                    Object attack=R.get(R.get(mc,"gameSettings"),"keyBindAttack");
                    Object ray=R.get(mc,"objectMouseOver");
                    boolean queued=R.num(attack,"pressTime")>0,inRange=ray!=null&&R.get(ray,"entityHit")==opponent;
                    TimedBlockController.Decision decision=timedBlock.decide(new TimedBlockController.Input(now,ticks,obs.freshHurtCue,prediction.commandAt(),queued,inRange),TimedBlockController.Settings.from(controls));
                    requested=decision.block();reason=decision.reason();
                    plan.put("model","linear");plan.put("predicted_receipt_at",Double.isFinite(prediction.receiptAt())?prediction.receiptAt():null);
                    plan.put("predicted_command_at",Double.isFinite(prediction.commandAt())?prediction.commandAt():null);
                    plan.put("eligible_at",prediction.eligibleAt());plan.put("opponent_range",prediction.opponentRangeNow());
                    plan.put("own_velocity",Compat.list(prediction.ownVx(),prediction.ownVy(),prediction.ownVz()));
                    plan.put("target_velocity",Compat.list(prediction.targetVx(),prediction.targetVy(),prediction.targetVz()));
                    plan.put("own_position",Compat.list(obs.x,obs.y,obs.z));plan.put("target_position",Compat.list(obs.tx,obs.ty,obs.tz));
                    plan.put("attack_queued",queued);plan.put("in_range",inRange);plan.put("release_tick",decision.releaseTick());plan.put("native_tick",ticks);
                }else{
                    BlockhitController.Decision decision=blockhit.decide(new BlockhitController.Input(now,obs.freshHurtCue,ping,obs.distance,obs.closing),BlockhitController.Settings.from(controls));
                    requested=decision.block();reason=decision.reason();
                    plan.put("ready_at",decision.readyAt());plan.put("window_start",decision.windowStart());plan.put("window_end",decision.windowEnd());
                }
                boolean block=number("block_enabled",1)>0&&requested;
                Object key=R.get(R.get(mc,"gameSettings"),"keyBindUseItem");
                R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"setKeyBindState",(int)R.call(key,"getKeyCode"),block);
                // A real right-mouse press generates both key state and one press
                // event. Vanilla itself starts/stops use and suppresses attacks.
                if(block&&!blockKey)R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"onTick",(int)R.call(key,"getKeyCode"));
                blockKey=block;
                plan.put("type","blockhit_plan");plan.put("time",now);plan.put("match",match);
                plan.put("observation_time",obs.time);plan.put("incoming_cue",lastObservedHurt);plan.put("block_key",block);
                plan.put("fresh_hurt_cue",obs.freshHurtCue);
                plan.put("native_blocking",R.call(player,"isBlocking"));plan.put("reason",reason);plan.put("requested_block",requested);
                plan.put("distance",obs.distance);plan.put("own_rtt",ping);log(plan);
            }else blockKey=false;
            if(kind.equals("scripted")){
                // Open-loop, predeclared ordinary inputs. No state oracle or adaptive click filtering.
                long elapsed=now-Long.parseLong(controls.getProperty("start_at","0"));
                boolean sw=false,sc=false;int sa=0;
                for(String step:controls.getProperty("input_script","0:0:0:0").split("\\|")){
                    String[] fields=step.split(":");if(fields.length!=4)throw new IllegalArgumentException("input_script: time:W:Ctrl:strafe");
                    if(elapsed>=Long.parseLong(fields[0])){sw=fields[1].equals("1");sc=fields[2].equals("1");sa=Integer.parseInt(fields[3]);}
                }
                if(Math.abs(sa)>1)throw new IllegalArgumentException("Invalid scripted strafe");
                setKeys(sw,sa,sc);
            }
            // A declared opening handicap tests combo acquisition separately from maintenance.
            if(controls.containsKey("click_start_ms") && now<Long.parseLong(controls.getProperty("start_at","0"))+(long)number("click_start_ms",0))clickGate=false;
            // Deliberate burst cadence is a policy choice. No immunity-based click filtering.
            if(controls.getProperty("click_mode","fixed").equals("burst"))
                clickGate=(now-epoch)%(long)number("burst_period_ms",500)<number("burst_on_ms",300);
            if(controls.getProperty("click_mode","fixed").equals("reactive-burst")){
                burstObservedAt=obs.time;
                burstDecision=burstClicks.decide(new BurstClickController.Input(now,obs.time,lastObservedTargetHurt,lastObservedHurt,ping),
                        BurstClickController.Settings.from(controls));
                clickGate &= burstDecision.click();
                Map<String,Object> plan=new LinkedHashMap<>();plan.put("type","burst_plan");plan.put("time",now);
                plan.put("monotonic_ns",System.nanoTime());plan.put("match",match);plan.put("native_tick",ticks);
                plan.put("observation_time",obs.time);plan.put("landed_cue",lastObservedTargetHurt);plan.put("incoming_cue",lastObservedHurt);
                plan.put("opening",burstDecision.opening());plan.put("defensive",burstDecision.defensive());
                plan.put("attack_window",burstDecision.attackWindow());plan.put("defensive_until",burstDecision.defensiveUntil());
                plan.put("estimated_ready_at",burstDecision.estimatedReadyAt());plan.put("attack_start_at",burstDecision.attackStartAt());
                plan.put("click_gate",clickGate);log(plan);
            }
            // Zero-delay controls also poll ordinary capped input at the head
            // of runTick, after packet work and before vanilla input consumption.
            // Every fast baseline uses this same opportunity; no queued input is
            // canceled and no attack is called directly.
            if(reaction==0){nativeInputPoll=true;try{frame(mc);}finally{nativeInputPoll=false;}}
            if(now-logAt>=50){
                logAt=now;
                Map<String,Object> entry=new LinkedHashMap<>();
                entry.put("type","observation");entry.put("time",now);entry.put("observation_time",obs.time);entry.put("match",match);
                entry.put("x",sample.x);entry.put("y",sample.y);entry.put("z",sample.z);entry.put("target_x",sample.tx);entry.put("target_y",sample.ty);entry.put("target_z",sample.tz);
                entry.put("vx",sample.vx);entry.put("vz",sample.vz);entry.put("hurt",sample.hurt);entry.put("target_hurt",sample.targetHurt);
                entry.put("sprint",sample.sprint);entry.put("forward",forward);entry.put("click_gate",clickGate);entry.put("predicted_gap",gap);
                entry.put("strafe",strafe);entry.put("zest_phase",zestPhase);entry.put("zest_selecting",zestSelecting);
                entry.put("sprint_key",sprintKey);entry.put("on_ground",sample.ground);
                entry.put("target_removed",R.get(opponent,"isDead"));
                entry.put("target_id",R.call(opponent,"getEntityId"));entry.put("target_is_canonical",R.call(world,"getEntityByID",(int)R.call(opponent,"getEntityId"))==opponent);
                entry.put("target_ticks",R.num(opponent,"ticksExisted"));entry.put("target_region_loaded",regionLoaded(world,opponent));entry.put("target_interpolation_ticks",R.num(opponent,"otherPlayerMPPosRotationIncrements"));
                entry.put("target_server_x",R.num(opponent,"serverPosX")/32);entry.put("target_server_y",R.num(opponent,"serverPosY")/32);entry.put("target_server_z",R.num(opponent,"serverPosZ")/32);
                log(entry);
            }
        }catch(Throwable e){fail(e);}
    }
    static Observation lastConsumed;
    static void validateControls(Properties p){
        if(!Boolean.parseBoolean(p.getProperty("enabled","false")))return;
        if(!p.getProperty("match","").matches("m[0-9]{4,9}")||!p.getProperty("opponent","").matches("[A-Za-z0-9_]{1,16}"))
            throw new IllegalArgumentException("Enabled bot controls require a valid match and opponent");
        if(!Compat.list("hold","standstill","periodic","wtap","hitreset","tradetap","spacing","predictive","phase","zest","mpcprobe","momentum","orbit","cycle","frozen-cycle","tick-cycle","gap-strafe","blockhit","scripted").contains(p.getProperty("kind","hold")))
            throw new IllegalArgumentException("Unsupported bot strategy: "+p.getProperty("kind"));
        ClickRate.cps(p);DuelSettings.reach(p);
        AimController.Settings.from(p);
        if(p.getProperty("kind","").equals("cycle"))CycleController.Settings.from(p);
        double reaction=Double.parseDouble(p.getProperty("reaction_ms","150"));
        if(!Double.isFinite(reaction)||reaction<0||reaction>1000)throw new IllegalArgumentException("Invalid reaction delay");
        Long.parseLong(p.getProperty("start_at","0"));
    }
    static boolean identityContains(Object collection,Object target){for(Object item:(Iterable<?>)collection)if(item==target)return true;return false;}
    static boolean regionLoaded(Object world,Object target){
        int x=(int)Math.floor(R.num(target,"posX")),z=(int)Math.floor(R.num(target,"posZ"));
        return (Boolean)R.call(world,"isAreaLoaded",x-32,0,z-32,x+32,0,z+32,true);
    }
    /** Read-only diagnosis of native guard cancellation; never supplies a policy input. */
    public static void blockUseClearing(Object self){
        if(!"client".equals(System.getProperty("lab.role"))||self!=player||!active()||!controls.getProperty("kind","").equals("blockhit"))return;
        try{
            Object used=R.get(self,"itemInUse");if(used==null)return;
            Object held=R.call(self,"getHeldItem");
            Map<String,Object> event=new LinkedHashMap<>();event.put("type","block_use_clear");event.put("time",System.currentTimeMillis());event.put("match",match);
            event.put("block_key",blockKey);event.put("held_is_used",held==used);
            event.put("used_damage",R.call(used,"getItemDamage"));event.put("held_damage",held==null?null:R.call(held,"getItemDamage"));
            event.put("caller",Arrays.stream(Thread.currentThread().getStackTrace()).skip(2).limit(5).map(StackTraceElement::toString).collect(java.util.stream.Collectors.toList()));log(event);
        }catch(Throwable e){if(errors++<5)e.printStackTrace();}
    }
    /** Native hurt-status message: emitted for a fresh hit, unlike health updates for immunity upgrades. */
    public static void hurtStatus(Object packet){
        if(!"client".equals(System.getProperty("lab.role"))||player==null||!active())return;
        try{
            if(((Number)R.call(packet,"getOpCode")).intValue()!=2)return;
            Object entity=R.call(packet,"getEntity",R.get(mc,"theWorld"));
            if(entity==opponent){freshTargetHurtStatus=System.currentTimeMillis();
                log(Compat.map("type","fresh_target_hurt_status","time",freshTargetHurtStatus,"match",match));
            }
            if(entity==player){freshHurtStatus=System.currentTimeMillis();
                log(Compat.map("type","fresh_hurt_status","time",freshHurtStatus,"match",match));
                if(Boolean.parseBoolean(controls.getProperty("block_adapt","false"))){
                    LinearHitPredictor.Calibration calibration=hitPredictor.hurt(freshHurtStatus,number("ping",0));
                    if(calibration!=null)log(Compat.map("type","prediction_calibration","time",freshHurtStatus,"match",match,
                        "forecast_time",calibration.forecastTime(),"error_ms",calibration.error(),"correction_ms",calibration.correction(),"count",calibration.count()));
                }
            }
        }catch(Throwable e){fail(e);}
    }
    public static void velocityPacket(Object packet){
        if(!"client".equals(System.getProperty("lab.role"))||player==null)return;
        try{
            Map<String,Object> event=new LinkedHashMap<>();event.put("type","velocity_packet");event.put("time",System.currentTimeMillis());event.put("match",match);
            event.put("entity_id",R.num(packet,"entityID"));event.put("own_entity",(int)R.num(packet,"entityID")==((Number)R.call(player,"getEntityId")).intValue());
            event.put("vx",R.num(packet,"motionX")/8000);event.put("vy",R.num(packet,"motionY")/8000);event.put("vz",R.num(packet,"motionZ")/8000);
            if(ScienceTrace.client() && Boolean.TRUE.equals(event.get("own_entity")))event.put("applied_motion",Compat.list(R.num(player,"motionX"),R.num(player,"motionY"),R.num(player,"motionZ")));
            log(event);
        }catch(Throwable e){fail(e);}
    }
    static double beforeX,beforeZ;
    static boolean beforeSprint;
    public static void attackBefore(Object self,Object target){
        if(!"client".equals(System.getProperty("lab.role")))return;
        beforeX=R.num(self,"motionX");beforeZ=R.num(self,"motionZ");beforeSprint=(Boolean)R.call(self,"isSprinting");
    }
    public static void attackAfter(Object self,Object target){
        if(!"client".equals(System.getProperty("lab.role")))return;
        if(controls.getProperty("kind","").equals("cycle")){cycle.contact(ticks);clickGate=false;}
        if(controls.getProperty("kind","").equals("frozen-cycle") || number("frozen_cycle_opening_ms",0)>0)frozenCycle.contact(ticks);
        if(controls.getProperty("kind","").equals("tick-cycle"))clickGate=tickCycle.contact(ticks);
        Map<String,Object> event=new LinkedHashMap<>();event.put("type","engine_attack");event.put("time",System.currentTimeMillis());event.put("match",match);
        event.put("attack_reach",attackReach());
        event.put("monotonic_ns",System.nanoTime());event.put("click_attempt",ScienceTrace.clickId());
        event.put("vx_before",beforeX);event.put("vz_before",beforeZ);event.put("vx_after",R.num(self,"motionX"));event.put("vz_after",R.num(self,"motionZ"));
        event.put("target_is_canonical",R.call(R.get(mc,"theWorld"),"getEntityByID",(int)R.call(target,"getEntityId"))==target);
        event.put("target_name",R.name(target));event.put("target_id",R.call(target,"getEntityId"));
        event.put("target_ticks",R.num(target,"ticksExisted"));event.put("target_ready",targetReady);
        event.put("target_loaded_identity",identityContains(R.get(R.get(mc,"theWorld"),"loadedEntityList"),target));
        Object ray=R.get(mc,"objectMouseOver");Object hitVec=ray==null?null:R.get(ray,"hitVec");
        event.put("ray_selected_target",ray!=null&&R.get(ray,"entityHit")==target);
        if(hitVec!=null){
            double dx=R.num(hitVec,"xCoord")-R.num(self,"posX"),dy=R.num(hitVec,"yCoord")-R.num(self,"posY")-((Number)R.call(self,"getEyeHeight")).doubleValue(),dz=R.num(hitVec,"zCoord")-R.num(self,"posZ");
            event.put("native_raycast_distance",Math.sqrt(dx*dx+dy*dy+dz*dz));
        }
        event.put("own_x",R.num(self,"posX"));event.put("own_y",R.num(self,"posY"));event.put("own_z",R.num(self,"posZ"));
        event.put("target_x",R.num(target,"posX"));event.put("target_y",R.num(target,"posY"));event.put("target_z",R.num(target,"posZ"));
        event.put("sprint_before",beforeSprint);event.put("sprint_after",R.call(self,"isSprinting"));event.put("target_visible_hurt",R.num(target,"hurtTime"));log(event);
    }
    public static double attackReach(){
        return "client".equals(System.getProperty("lab.role"))&&SLOT.matches("[0-9]+")&&active()?DuelSettings.reach(controls):3.0;
    }
    public static float selectionRayLength(float vanilla){return (float)Math.max(vanilla,attackReach());}
    static boolean active(){return Boolean.parseBoolean(controls.getProperty("enabled","false"))&&System.currentTimeMillis()>=Long.parseLong(controls.getProperty("start_at","0"));}
    static double number(String name,double fallback){return Double.parseDouble(controls.getProperty(name,Double.toString(fallback)));}
    static void setKeys(boolean w){
        setKeys(w,0);
    }
    static void setKeys(boolean w,int lateral){
        if(w&&!forward)sprintPulse=true;
        String mode=controls.getProperty("sprint_mode","hold");
        sprintKey=w && (mode.equals("phase")?zestPhase==0:mode.equals("cycle")?sprintPulse:!mode.equals("walk"));
        setKeys(w,lateral,sprintKey);
    }
    static void setKeys(boolean w,int lateral,boolean ctrl){
        sprintKey=w&&ctrl;forward=w;strafe=lateral; Object settings=R.get(mc,"gameSettings");
        for(String key:Compat.list("keyBindForward","keyBindSprint","keyBindBack","keyBindLeft","keyBindRight","keyBindJump","keyBindSneak","keyBindUseItem")){
            Object binding=R.get(settings,key);boolean down=key.equals("keyBindForward")?w:key.equals("keyBindSprint")?sprintKey:key.equals("keyBindLeft")?lateral<0:key.equals("keyBindRight")&&lateral>0;
            R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"setKeyBindState",(int)R.call(binding,"getKeyCode"),down);
        }
    }
    static void log(Map<String,?> entry){
        try{String encoded=Json.encode(entry);if(services!=null)services.event(encoded);Files.createDirectories(DIR);Compat.writeString(DIR.resolve("events.jsonl"),encoded+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(IOException e){throw new RuntimeException(e);}
    }
    static void fail(Throwable e){
        if(errors++<5)e.printStackTrace();
        releaseInputs();
    }
}
