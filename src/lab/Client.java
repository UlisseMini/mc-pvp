package lab;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Policies can only change key bindings, aim, and a capped click schedule. */
public final class Client {
    static final String SLOT = System.getProperty("lab.slot", "0");
    static final Path DIR = R.ROOT.resolve("runtime/bots/" + SLOT);
    static Properties controls = new Properties();
    static long readAt, connectAt, lastClick, epoch, logAt, ticks, errors;
    static String match = "";
    static boolean connecting, forward, clickGate;
    static Object mc, player, opponent;
    static final Deque<Observation> history = new ArrayDeque<>();
    static Observation previous;
    static long lastObservedHurt, lastObservedTargetHurt;
    static double targetSpeed;
    static long scriptStarted;
    static int scriptIndex;
    static long heartbeatAt,respawnAt;

    static final class Observation {
        long time; double x,y,z,tx,ty,tz,vx,vz,distance,closing; int hurt,targetHurt; boolean sprint;
        Observation(long time) {
            this.time=time;
            x=R.num(player,"posX"); y=R.num(player,"posY"); z=R.num(player,"posZ");
            tx=R.num(opponent,"posX"); ty=R.num(opponent,"posY"); tz=R.num(opponent,"posZ");
            vx=R.num(player,"motionX"); vz=R.num(player,"motionZ");
            distance=Math.hypot(tx-x,tz-z); hurt=(int)R.num(player,"hurtTime"); targetHurt=(int)R.num(opponent,"hurtTime");
            sprint=(Boolean)R.call(player,"isSprinting");
            if(previous!=null && time>previous.time) closing=(previous.distance-distance)/((time-previous.time)/1000.0);
        }
    }

    public static void frame(Object minecraft) {
        if(!"client".equals(System.getProperty("lab.role")))return;
        mc=minecraft;
        try {
            if(player==null || opponent==null || (Boolean)R.get(opponent,"isDead") || !active())return;
            // Perfect aim uses the current local, interpolated entity, never server state.
            double dx=R.num(opponent,"posX")-R.num(player,"posX"), dz=R.num(opponent,"posZ")-R.num(player,"posZ");
            double eye=R.num(player,"posY")+((Number)R.call(player,"getEyeHeight")).doubleValue();
            double targetEye=Math.max(R.num(opponent,"posY")+.15,Math.min(eye,R.num(opponent,"posY")+1.65));
            float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
            float pitch=(float)-Math.toDegrees(Math.atan2(targetEye-eye,Math.hypot(dx,dz)));
            R.set(player,"rotationYaw",yaw); R.set(player,"rotationPitch",pitch);
            Object renderer=R.get(mc,"entityRenderer"); R.call(renderer,"getMouseOver",1.0f);
            long now=System.nanoTime();
            if(clickGate && now-lastClick>=100_000_000L && R.get(mc,"currentScreen")==null) {
                Object key=R.get(R.get(mc,"gameSettings"),"keyBindAttack");
                R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"onTick",(int)R.call(key,"getKeyCode"));
                lastClick=now;
                log(Map.of("type","click","time",System.currentTimeMillis(),"match",match,"forward",forward));
            }
        }catch(Throwable e){fail(e);}
    }

    public static void tick(Object minecraft) {
        if(!"client".equals(System.getProperty("lab.role")))return;
        mc=minecraft; ticks++;
        try {
            long now=System.currentTimeMillis();
            if(now-readAt>100){controls=R.properties(DIR.resolve("control.properties"));readAt=now;}
            player=R.get(mc,"thePlayer"); Object world=R.get(mc,"theWorld");
            if(player==null || world==null){
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
                    visible.add(R.name(e));entities.add(Map.of("name",R.name(e),"id",R.call(e,"getEntityId"),"dead",R.get(e,"isDead"),"x",R.num(e,"posX"),"y",R.num(e,"posY"),"z",R.num(e,"posZ")));
                }
                Map<String,Object> heartbeat=new LinkedHashMap<>();heartbeat.put("time",now);heartbeat.put("match",controls.getProperty("match",""));heartbeat.put("enabled",active());
                heartbeat.put("health",R.call(player,"getHealth"));heartbeat.put("alive",R.call(player,"isEntityAlive"));heartbeat.put("dead",R.get(player,"isDead"));
                heartbeat.put("visible_players",visible);heartbeat.put("x",R.num(player,"posX"));heartbeat.put("y",R.num(player,"posY"));heartbeat.put("z",R.num(player,"posZ"));
                heartbeat.put("entities",entities);
                heartbeat.put("selected_target_dead",opponent!=null&&(Boolean)R.get(opponent,"isDead"));
                Object screen=R.get(mc,"currentScreen");heartbeat.put("screen",screen==null?null:screen.getClass().getName());
                Files.createDirectories(DIR);Files.writeString(DIR.resolve("heartbeat.json"),Json.encode(heartbeat));
            }
            if(System.getProperty("lab.test_script")!=null){
                if(scriptStarted==0)scriptStarted=now;
                String[] script=System.getProperty("lab.test_script").split("\\|");
                if(scriptIndex<script.length){String[] step=script[scriptIndex].split(":",2);if(now-scriptStarted>=Long.parseLong(step[0])){R.call(player,"sendChatMessage",step[1]);scriptIndex++;}}
                if(now-scriptStarted>Long.parseLong(System.getProperty("lab.test_duration","20000")))R.call(mc,"shutdown");
            }
            String next=controls.getProperty("match","");
            if(!next.equals(match)){
                match=next;epoch=Long.parseLong(controls.getProperty("start_at",Long.toString(now)));history.clear();previous=null;lastConsumed=null;lastClick=0;
                lastObservedHurt=lastObservedTargetHurt=0;
            }
            opponent=null;
            for(Object entity:(List<?>)R.get(world,"playerEntities"))
                if(!(Boolean)R.get(entity,"isDead") && R.name(entity).equals(controls.getProperty("opponent",""))){opponent=entity;break;}
            if(!active() || opponent==null){setKeys(false);clickGate=false;return;}
            if(R.get(mc,"currentScreen")!=null)R.call(mc,"displayGuiScreen",(Object)null);
            R.call(mc,"setIngameFocus");
            Observation sample=new Observation(now); history.addLast(sample); previous=sample;
            long cutoff=now-150;
            while(history.size()>1 && history.stream().skip(1).findFirst().get().time<=cutoff)history.removeFirst();
            Observation obs=history.peekFirst();
            if(obs.time>cutoff){setKeys(false);clickGate=false;return;}
            // All tactical decisions use only observations at least 150 ms old.
            Observation before=lastConsumed;
            if(before==null || obs.time>before.time){
                if(obs.hurt>0 && (before==null || obs.hurt>before.hurt))lastObservedHurt=obs.time;
                if(obs.targetHurt>0 && (before==null || obs.targetHurt>before.targetHurt))lastObservedTargetHurt=obs.time;
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
            if(kind.equals("periodic"))w=(now-epoch)%(long)number("period_ms",400)>=(long)number("pause_ms",50);
            if(kind.equals("hitreset"))w=now-lastObservedTargetHurt>150+number("pause_ms",50);
            if(kind.equals("spacing") || kind.equals("predictive") || kind.equals("phase")) {
                // Friction creates coasting: release before the desired future gap is crossed.
                double ownToward=(obs.vx*(obs.tx-obs.x)+obs.vz*(obs.tz-obs.z))/Math.max(.001,obs.distance);
                double coast=Math.max(0,ownToward)/(1-.546);
                double latencyLead=kind.equals("predictive")?(ping+oppPing)*Math.max(0,obs.closing)/4000.0:0;
                double projected=gap-coast-latencyLead;
                w=forward?projected>desired-margin:projected>desired+margin;
            }
            setKeys(w);
            clickGate=true;
            // Deliberate burst cadence is a policy choice. No immunity-based click filtering.
            if(controls.getProperty("click_mode","fixed").equals("burst"))
                clickGate=(now-epoch)%(long)number("burst_period_ms",500)<number("burst_on_ms",300);
            if(now-logAt>=50){
                logAt=now;
                Map<String,Object> entry=new LinkedHashMap<>();
                entry.put("type","observation");entry.put("time",now);entry.put("observation_time",obs.time);entry.put("match",match);
                entry.put("x",sample.x);entry.put("y",sample.y);entry.put("z",sample.z);entry.put("target_x",sample.tx);entry.put("target_y",sample.ty);entry.put("target_z",sample.tz);
                entry.put("vx",sample.vx);entry.put("vz",sample.vz);entry.put("hurt",sample.hurt);entry.put("target_hurt",sample.targetHurt);
                entry.put("sprint",sample.sprint);entry.put("forward",forward);entry.put("click_gate",clickGate);entry.put("predicted_gap",gap);
                entry.put("target_removed",R.get(opponent,"isDead"));
                log(entry);
            }
        }catch(Throwable e){fail(e);}
    }
    static Observation lastConsumed;
    static double beforeX,beforeZ;
    static boolean beforeSprint;
    public static void attackBefore(Object self,Object target){
        if(!"client".equals(System.getProperty("lab.role")))return;
        beforeX=R.num(self,"motionX");beforeZ=R.num(self,"motionZ");beforeSprint=(Boolean)R.call(self,"isSprinting");
    }
    public static void attackAfter(Object self,Object target){
        if(!"client".equals(System.getProperty("lab.role")))return;
        Map<String,Object> event=new LinkedHashMap<>();event.put("type","engine_attack");event.put("time",System.currentTimeMillis());event.put("match",match);
        event.put("vx_before",beforeX);event.put("vz_before",beforeZ);event.put("vx_after",R.num(self,"motionX"));event.put("vz_after",R.num(self,"motionZ"));
        event.put("sprint_before",beforeSprint);event.put("sprint_after",R.call(self,"isSprinting"));event.put("target_visible_hurt",R.num(target,"hurtTime"));log(event);
    }
    static boolean active(){return Boolean.parseBoolean(controls.getProperty("enabled","false"))&&System.currentTimeMillis()>=Long.parseLong(controls.getProperty("start_at","0"));}
    static double number(String name,double fallback){return Double.parseDouble(controls.getProperty(name,Double.toString(fallback)));}
    static void setKeys(boolean w){
        forward=w; Object settings=R.get(mc,"gameSettings");
        for(String key:List.of("keyBindForward","keyBindSprint","keyBindBack","keyBindLeft","keyBindRight","keyBindJump","keyBindSneak","keyBindUseItem")){
            Object binding=R.get(settings,key);boolean down=key.equals("keyBindForward")?w:key.equals("keyBindSprint")&&w;
            R.call(R.cls("net.minecraft.client.settings.KeyBinding"),"setKeyBindState",(int)R.call(binding,"getKeyCode"),down);
        }
    }
    static void log(Map<String,?> entry){
        try{Files.createDirectories(DIR);Files.writeString(DIR.resolve("events.jsonl"),Json.encode(entry)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(IOException e){throw new RuntimeException(e);}
    }
    static void fail(Throwable e){
        if(errors++<5)e.printStackTrace();
        clickGate=false;
        if(player!=null)try{setKeys(false);}catch(Throwable ignored){}
    }
}
