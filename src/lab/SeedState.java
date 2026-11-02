package lab;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Immutable, explicit synthetic combat seed. Coordinates are arena-relative.
 * Parsing and hashing do not read game state, clocks, randomness or defaults
 * outside this file. Native application/release is owned by the seed protocol. */
public final class SeedState {
    public final int schema;
    public final double phaseAMs,phaseBMs,phaseServerMs;
    public final Side a,b;
    private final String canonical;

    public static final class Body {
        public final double x,y,z,vx,vy,vz;
        public final float yaw,pitch,headYaw,renderYaw,lastDamage,fallDistance;
        public final boolean ground,sprint,airborne,velocityChanged;
        public final int hurt,hurtResistant;
        private Body(Parser p,String key,Body fallback,boolean fromA) {
            x=p.decimal(key+"x",fallback==null?(fromA?-1.75:1.75):fallback.x,-2048,2048);
            y=p.decimal(key+"y",fallback==null?4:fallback.y,0,256);
            z=p.decimal(key+"z",fallback==null?0:fallback.z,-2048,2048);
            vx=p.decimal(key+"vx",fallback==null?0:fallback.vx,-4,4);
            vy=p.decimal(key+"vy",fallback==null?0:fallback.vy,-4,4);
            vz=p.decimal(key+"vz",fallback==null?0:fallback.vz,-4,4);
            yaw=p.single(key+"yaw",fallback==null?(fromA?-90:90):fallback.yaw,-360,360);
            pitch=p.single(key+"pitch",fallback==null?0:fallback.pitch,-90,90);
            headYaw=p.single(key+"head_yaw",fallback==null?yaw:fallback.headYaw,-360,360);
            renderYaw=p.single(key+"render_yaw",fallback==null?yaw:fallback.renderYaw,-360,360);
            ground=p.flag(key+"ground",fallback==null||fallback.ground);
            sprint=p.flag(key+"sprint",fallback!=null&&fallback.sprint);
            hurt=p.integer(key+"hurt",fallback==null?0:fallback.hurt,0,200);
            hurtResistant=p.integer(key+"hurt_resistant",fallback==null?0:fallback.hurtResistant,0,200);
            lastDamage=p.single(key+"last_damage",fallback==null?0:fallback.lastDamage,0,1024);
            fallDistance=p.single(key+"fall_distance",fallback==null?0:fallback.fallDistance,0,256);
            airborne=p.flag(key+"airborne",fallback!=null&&fallback.airborne);
            velocityChanged=p.flag(key+"velocity_changed",fallback!=null&&fallback.velocityChanged);
        }
        private void write(TreeMap<String,String> out,String key) {
            put(out,key+"x",x);put(out,key+"y",y);put(out,key+"z",z);
            put(out,key+"vx",vx);put(out,key+"vy",vy);put(out,key+"vz",vz);
            put(out,key+"yaw",yaw);put(out,key+"pitch",pitch);
            put(out,key+"head_yaw",headYaw);put(out,key+"render_yaw",renderYaw);
            put(out,key+"ground",ground);put(out,key+"sprint",sprint);
            put(out,key+"hurt",hurt);put(out,key+"hurt_resistant",hurtResistant);put(out,key+"last_damage",lastDamage);
            put(out,key+"fall_distance",fallDistance);put(out,key+"airborne",airborne);put(out,key+"velocity_changed",velocityChanged);
        }
    }
    public static final class OwnCaches {
        public final int sprintToggle,sprintingTicks,jumpTicks,positionUpdateTicks;
        public final float jumpFactor,reportedYaw,reportedPitch,inputForward,inputStrafe;
        public final double reportedX,reportedY,reportedZ,targetHurtCueAgeMs,ownHurtCueAgeMs,wtapHandledTargetHurtCueAgeMs,clickAgeMs,tapRemainingMs;
        public final boolean sentSprint,inputJump,inputSneak;
        private OwnCaches(Parser p,String key,Body own) {
            sprintToggle=p.integer(key+"sprint_toggle",0,0,20);
            sprintingTicks=p.integer(key+"sprinting_ticks",own.sprint?600:0,0,12000);
            jumpTicks=p.integer(key+"jump_ticks",0,0,200);
            jumpFactor=p.single(key+"jump_factor",own.sprint?.026f:.02f,0,.2);
            reportedX=p.decimal(key+"reported_x",own.x,-2048,2048);
            reportedY=p.decimal(key+"reported_y",own.y,0,256);
            reportedZ=p.decimal(key+"reported_z",own.z,-2048,2048);
            reportedYaw=p.single(key+"reported_yaw",own.yaw,-360,360);
            reportedPitch=p.single(key+"reported_pitch",own.pitch,-90,90);
            positionUpdateTicks=p.integer(key+"position_update_ticks",0,0,200);
            sentSprint=p.flag(key+"sent_sprint",own.sprint);
            inputForward=p.single(key+"input_forward",0,-1,1);inputStrafe=p.single(key+"input_strafe",0,-1,1);
            inputJump=p.flag(key+"input_jump",false);inputSneak=p.flag(key+"input_sneak",false);
            targetHurtCueAgeMs=p.cueAge(key+"target_hurt_cue_age_ms");ownHurtCueAgeMs=p.cueAge(key+"own_hurt_cue_age_ms");
            wtapHandledTargetHurtCueAgeMs=p.cueAge(key+"wtap_handled_target_hurt_cue_age_ms",targetHurtCueAgeMs);
            clickAgeMs=p.decimal(key+"click_age_ms",1000,0,1000);tapRemainingMs=p.decimal(key+"tap_remaining_ms",0,0,200);
        }
        private void write(TreeMap<String,String> out,String key) {
            put(out,key+"sprint_toggle",sprintToggle);put(out,key+"sprinting_ticks",sprintingTicks);
            put(out,key+"jump_ticks",jumpTicks);put(out,key+"jump_factor",jumpFactor);
            put(out,key+"reported_x",reportedX);put(out,key+"reported_y",reportedY);put(out,key+"reported_z",reportedZ);
            put(out,key+"reported_yaw",reportedYaw);put(out,key+"reported_pitch",reportedPitch);
            put(out,key+"position_update_ticks",positionUpdateTicks);put(out,key+"sent_sprint",sentSprint);
            put(out,key+"input_forward",inputForward);put(out,key+"input_strafe",inputStrafe);put(out,key+"input_jump",inputJump);put(out,key+"input_sneak",inputSneak);
            put(out,key+"target_hurt_cue_age_ms",targetHurtCueAgeMs);put(out,key+"own_hurt_cue_age_ms",ownHurtCueAgeMs);
            put(out,key+"wtap_handled_target_hurt_cue_age_ms",wtapHandledTargetHurtCueAgeMs);
            put(out,key+"click_age_ms",clickAgeMs);put(out,key+"tap_remaining_ms",tapRemainingMs);
        }
    }
    public static final class Replica {
        public final double x,y,z,goalX,goalY,goalZ,goalYaw,goalPitch;
        public final float yaw,pitch;
        public final int interpolationTicks,serverX,serverY,serverZ;
        private Replica(Parser p,String key,Body target) {
            x=p.decimal(key+"x",target.x,-2048,2048);y=p.decimal(key+"y",target.y,0,256);z=p.decimal(key+"z",target.z,-2048,2048);
            yaw=p.single(key+"yaw",target.yaw,-360,360);pitch=p.single(key+"pitch",target.pitch,-90,90);
            goalX=p.decimal(key+"goal_x",x,-2048,2048);goalY=p.decimal(key+"goal_y",y,0,256);goalZ=p.decimal(key+"goal_z",z,-2048,2048);
            goalYaw=p.decimal(key+"goal_yaw",yaw,-360,360);goalPitch=p.decimal(key+"goal_pitch",pitch,-90,90);
            interpolationTicks=p.integer(key+"interpolation_ticks",0,0,20);
            serverX=p.integer(key+"server_x",encoded(goalX),-65536,65536);
            serverY=p.integer(key+"server_y",encoded(goalY),0,8192);
            serverZ=p.integer(key+"server_z",encoded(goalZ),-65536,65536);
        }
        private void write(TreeMap<String,String> out,String key) {
            put(out,key+"x",x);put(out,key+"y",y);put(out,key+"z",z);put(out,key+"yaw",yaw);put(out,key+"pitch",pitch);
            put(out,key+"goal_x",goalX);put(out,key+"goal_y",goalY);put(out,key+"goal_z",goalZ);
            put(out,key+"goal_yaw",goalYaw);put(out,key+"goal_pitch",goalPitch);put(out,key+"interpolation_ticks",interpolationTicks);
            put(out,key+"server_x",serverX);put(out,key+"server_y",serverY);put(out,key+"server_z",serverZ);
        }
    }
    public static final class Side {
        public final Body own,server;
        public final OwnCaches caches;
        public final Replica view;
        public final Tracker tracker;
        public final NetHandlerCaches netHandler;
        private Side(Parser p,String side,Body own,Body target,boolean fromA){
            this.own=own;server=new Body(p,"server."+side+".",own,fromA);
            caches=new OwnCaches(p,side+".",own);view=new Replica(p,side+".view.",target);
            tracker=new Tracker(p,"server."+side+".tracker.",server);netHandler=new NetHandlerCaches(p,"server."+side+".handler.",server);
        }
    }
    public static final class Tracker {
        public final int encodedX,encodedY,encodedZ,encodedYaw,encodedPitch,encodedHead,updateCounter,forcedTeleportTicks;
        public final double vx,vy,vz,lastPositionX,lastPositionY,lastPositionZ;
        public final boolean ground,firstUpdateDone,playerEntitiesUpdated;
        private Tracker(Parser p,String key,Body server){
            encodedX=p.integer(key+"encoded_x",encoded(server.x),-65536,65536);
            encodedY=p.integer(key+"encoded_y",encoded(server.y),0,8192);
            encodedZ=p.integer(key+"encoded_z",encoded(server.z),-65536,65536);
            encodedYaw=p.integer(key+"yaw",encodedAngle(server.yaw),-256,256);
            encodedPitch=p.integer(key+"pitch",encodedAngle(server.pitch),-256,256);
            encodedHead=p.integer(key+"head",encodedAngle(server.headYaw),-256,256);
            updateCounter=p.integer(key+"update_counter",0,0,1000000);
            forcedTeleportTicks=p.integer(key+"forced_teleport_ticks",0,0,1000000);
            vx=p.decimal(key+"vx",server.vx,-4,4);vy=p.decimal(key+"vy",server.vy,-4,4);vz=p.decimal(key+"vz",server.vz,-4,4);
            ground=p.flag(key+"ground",server.ground);firstUpdateDone=p.flag(key+"first_update_done",true);playerEntitiesUpdated=p.flag(key+"player_entities_updated",false);
            lastPositionX=p.decimal(key+"last_position_x",server.x,-2048,2048);lastPositionY=p.decimal(key+"last_position_y",server.y,0,256);lastPositionZ=p.decimal(key+"last_position_z",server.z,-2048,2048);
        }
        private void write(TreeMap<String,String> out,String key){
            put(out,key+"encoded_x",encodedX);put(out,key+"encoded_y",encodedY);put(out,key+"encoded_z",encodedZ);
            put(out,key+"yaw",encodedYaw);put(out,key+"pitch",encodedPitch);put(out,key+"head",encodedHead);
            put(out,key+"update_counter",updateCounter);put(out,key+"forced_teleport_ticks",forcedTeleportTicks);
            put(out,key+"vx",vx);put(out,key+"vy",vy);put(out,key+"vz",vz);put(out,key+"ground",ground);
            put(out,key+"first_update_done",firstUpdateDone);put(out,key+"player_entities_updated",playerEntitiesUpdated);
            put(out,key+"last_position_x",lastPositionX);put(out,key+"last_position_y",lastPositionY);put(out,key+"last_position_z",lastPositionZ);
        }
    }
    public static final class NetHandlerCaches {
        public final double x,y,z;
        public final boolean hasMoved;
        private NetHandlerCaches(Parser p,String key,Body server){
            x=p.decimal(key+"x",server.x,-2048,2048);y=p.decimal(key+"y",server.y,0,256);z=p.decimal(key+"z",server.z,-2048,2048);
            hasMoved=p.flag(key+"has_moved",true);
        }
        private void write(TreeMap<String,String> out,String key){put(out,key+"x",x);put(out,key+"y",y);put(out,key+"z",z);put(out,key+"has_moved",hasMoved);}
    }
    private SeedState(Properties source) {
        Parser p=new Parser(source);schema=p.requiredInteger("schema",1,1);
        phaseAMs=p.decimal("phase_a_ms",0,0,100);phaseBMs=p.decimal("phase_b_ms",0,0,100);phaseServerMs=p.decimal("phase_server_ms",0,0,100);
        Body ownA=new Body(p,"a.",null,true),ownB=new Body(p,"b.",null,false);
        a=new Side(p,"a",ownA,ownB,true);b=new Side(p,"b",ownB,ownA,false);
        p.rejectUnknown();
        TreeMap<String,String> out=new TreeMap<String,String>();put(out,"schema",schema);
        put(out,"phase_a_ms",phaseAMs);put(out,"phase_b_ms",phaseBMs);put(out,"phase_server_ms",phaseServerMs);
        a.own.write(out,"a.");b.own.write(out,"b.");a.server.write(out,"server.a.");b.server.write(out,"server.b.");
        a.caches.write(out,"a.");b.caches.write(out,"b.");a.view.write(out,"a.view.");b.view.write(out,"b.view.");
        a.tracker.write(out,"server.a.tracker.");b.tracker.write(out,"server.b.tracker.");a.netHandler.write(out,"server.a.handler.");b.netHandler.write(out,"server.b.handler.");
        StringBuilder text=new StringBuilder();for(java.util.Map.Entry<String,String> e:out.entrySet())text.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        canonical=text.toString();
    }
    public static SeedState parse(Properties source){if(source==null)throw new IllegalArgumentException("Seed properties are required");return new SeedState(source);}
    public static SeedState load(Path path)throws IOException{Properties p=new Properties();try(Reader r=Files.newBufferedReader(path,StandardCharsets.UTF_8)){p.load(r);}return parse(p);}
    public String canonicalText(){return canonical;}
    public Properties canonicalProperties(){Properties p=new Properties();for(String line:canonical.split("\n")){int at=line.indexOf('=');p.setProperty(line.substring(0,at),line.substring(at+1));}return p;}
    public String sha256(){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte x:bytes)out.append(String.format(Locale.ROOT,"%02x",x&255));return out.toString();}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static int encoded(double x){return(int)Math.floor(x*32);}
    private static int encodedAngle(float angle){return(int)Math.floor(angle*256f/360f);}
    private static void put(TreeMap<String,String> p,String key,Object value){p.put(key,String.valueOf(value));}
    private static final class Parser {
        final Properties values=new Properties();final Set<String> used=new HashSet<String>();
        Parser(Properties p){
            for(Object key:p.keySet())if(!(key instanceof String)||!(p.get(key)instanceof String))throw new IllegalArgumentException("Seed keys and values must be strings");
            for(String key:p.stringPropertyNames())values.setProperty(key,p.getProperty(key));
        }
        String read(String key){used.add(key);String value=values.getProperty(key);return value==null?null:value.trim();}
        double decimal(String key,double fallback,double minimum,double maximum){String value=read(key);double result=fallback;
            try{if(value!=null)result=Double.parseDouble(value);}catch(NumberFormatException e){throw bad(key,"expected a number");}
            if(Double.isNaN(result)||Double.isInfinite(result)||result<minimum||result>maximum)throw bad(key,"expected finite "+minimum+".."+maximum);
            return result;
        }
        float single(String key,float fallback,double minimum,double maximum){return(float)decimal(key,fallback,minimum,maximum);}
        double cueAge(String key){return cueAge(key,-1);}
        double cueAge(String key,double fallback){double value=decimal(key,fallback,-1,120000);if(value<0&&value!=-1)throw bad(key,"expected -1 (absent) or 0..120000 ms");return value;}
        int requiredInteger(String key,int minimum,int maximum){if(!values.containsKey(key))throw bad(key,"required");return integer(key,minimum,minimum,maximum);}
        int integer(String key,int fallback,int minimum,int maximum){String value=read(key);int result=fallback;
            try{if(value!=null)result=Integer.parseInt(value);}catch(NumberFormatException e){throw bad(key,"expected an integer");}
            if(result<minimum||result>maximum)throw bad(key,"expected "+minimum+".."+maximum);return result;
        }
        boolean flag(String key,boolean fallback){String value=read(key);if(value==null)return fallback;if(value.equals("true"))return true;if(value.equals("false"))return false;throw bad(key,"expected true or false");}
        void rejectUnknown(){for(String key:values.stringPropertyNames())if(!used.contains(key))throw bad(key,"unknown property");}
        IllegalArgumentException bad(String key,String message){return new IllegalArgumentException("Seed "+key+": "+message);}
    }
}
