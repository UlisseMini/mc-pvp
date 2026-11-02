package lab;

import java.util.*;

/** Setup-only native field application and independent read-back. Java8. */
public final class SeededEntity {
    private SeededEntity(){}
    static final String[][] BODY={
        {"vx","motionX"},{"vy","motionY"},{"vz","motionZ"},
        {"yaw","rotationYaw"},{"pitch","rotationPitch"},{"head_yaw","rotationYawHead"},{"render_yaw","renderYawOffset"},{"ground","onGround"},
        {"hurt","hurtTime"},{"hurt_resistant","hurtResistantTime"},{"last_damage","lastDamage"},
        {"airborne","isAirBorne"},{"velocity_changed","velocityChanged"},{"fall_distance","fallDistance"}};
    static final String[][] CACHE={
        {"sprint_toggle","sprintToggleTimer"},{"sprinting_ticks","sprintingTicksLeft"},
        {"jump_ticks","jumpTicks"},{"jump_factor","jumpMovementFactor"},
        {"reported_yaw","lastReportedYaw"},{"reported_pitch","lastReportedPitch"},
        {"position_update_ticks","positionUpdateTicks"},{"sent_sprint","serverSprintState"}};
    static final String[][] VIEW={
        {"yaw","rotationYaw"},{"pitch","rotationPitch"},
        {"goal_yaw","otherPlayerMPYaw"},{"goal_pitch","otherPlayerMPPitch"},
        {"interpolation_ticks","otherPlayerMPPosRotationIncrements"},
        {"server_y","serverPosY"},{"server_z","serverPosZ"}};
    static void assign(Object entity,Properties p,String prefix,String[][] fields){
        for(String[] pair:fields){String text=p.getProperty(prefix+pair[0]);
            if(text==null)throw new IllegalArgumentException("Missing canonical seed field "+prefix+pair[0]);
            Object old=R.get(entity,pair[1]),value;
            if(old instanceof Boolean)value=Boolean.valueOf(text);
            else if(old instanceof Integer)value=Integer.valueOf(text);
            else if(old instanceof Float)value=Float.valueOf(text);
            else if(old instanceof Double)value=Double.valueOf(text);
            else throw new IllegalArgumentException("Unsupported seed field type "+pair[1]);
            R.set(entity,pair[1],value);
        }
    }
    static void read(Object entity,Map<String,Object> out,String prefix,String[][] fields){
        for(String[] pair:fields)out.put(prefix+pair[0],R.get(entity,pair[1]));
    }
    public static void body(Object entity,SeedState.Body s,Properties p,String prefix,double origin){
        R.call(entity,"setPosition",origin+s.x,s.y,s.z);
        R.call(entity,"setSprinting",s.sprint);R.call(entity,"setSneaking",false);
        assign(entity,p,prefix,BODY);
        for(String field:Compat.list("prevPosX","lastTickPosX"))R.set(entity,field,origin+s.x);
        for(String field:Compat.list("prevPosY","lastTickPosY"))R.set(entity,field,s.y);
        for(String field:Compat.list("prevPosZ","lastTickPosZ"))R.set(entity,field,s.z);
        R.set(entity,"prevRotationYaw",s.yaw);R.set(entity,"prevRotationPitch",s.pitch);
        R.set(entity,"prevRotationYawHead",s.headYaw);R.set(entity,"prevRenderYawOffset",s.renderYaw);
        R.set(entity,"isCollidedHorizontally",false);R.set(entity,"isCollidedVertically",s.ground);R.set(entity,"isCollided",s.ground);
        R.set(entity,"isInWeb",false);R.set(entity,"noClip",false);
        R.set(entity,"maxHurtTime",10);R.set(entity,"attackedAtYaw",0.0f);
    }
    public static void own(Object entity,SeedState.Side s,Properties p,String side,double origin){
        body(entity,s.own,p,side+".",origin);assign(entity,p,side+".",CACHE);
        R.set(entity,"lastReportedPosX",origin+s.caches.reportedX);
        R.set(entity,"lastReportedPosY",s.caches.reportedY);R.set(entity,"lastReportedPosZ",s.caches.reportedZ);
        Object input=R.get(entity,"movementInput");R.set(input,"moveForward",s.caches.inputForward);R.set(input,"moveStrafe",s.caches.inputStrafe);
        R.set(input,"jump",s.caches.inputJump);R.set(input,"sneak",s.caches.inputSneak);
    }
    public static void replica(Object entity,SeedState.Replica s,Properties p,String side,double origin){
        R.call(entity,"setPosition",origin+s.x,s.y,s.z);assign(entity,p,side+".view.",VIEW);
        R.set(entity,"otherPlayerMPX",origin+s.goalX);R.set(entity,"otherPlayerMPY",s.goalY);R.set(entity,"otherPlayerMPZ",s.goalZ);
        R.set(entity,"serverPosX",(int)Math.floor(origin*32)+s.serverX);
        R.set(entity,"prevPosX",origin+s.x);R.set(entity,"prevPosY",s.y);R.set(entity,"prevPosZ",s.z);
        R.set(entity,"lastTickPosX",origin+s.x);R.set(entity,"lastTickPosY",s.y);R.set(entity,"lastTickPosZ",s.z);
        R.set(entity,"prevRotationYaw",s.yaw);R.set(entity,"prevRotationPitch",s.pitch);
        R.set(entity,"motionX",0.0);R.set(entity,"motionY",0.0);R.set(entity,"motionZ",0.0);
        R.set(entity,"hurtTime",0);R.set(entity,"hurtResistantTime",0);
    }
    public static void bodySnapshot(Object e,Map<String,Object> out,String prefix,double origin){
        out.put(prefix+"x",R.num(e,"posX")-origin);out.put(prefix+"y",R.get(e,"posY"));out.put(prefix+"z",R.get(e,"posZ"));
        read(e,out,prefix,BODY);out.put(prefix+"sprint",R.call(e,"isSprinting"));
    }
    public static Map<String,Object> clientSnapshot(Object own,Object view,String side,double origin){
        Map<String,Object> out=new TreeMap<String,Object>();String prefix=side+".";
        bodySnapshot(own,out,prefix,origin);read(own,out,prefix,CACHE);
        out.put(prefix+"reported_x",R.num(own,"lastReportedPosX")-origin);
        out.put(prefix+"reported_y",R.get(own,"lastReportedPosY"));out.put(prefix+"reported_z",R.get(own,"lastReportedPosZ"));
        Object input=R.get(own,"movementInput");
        out.put(prefix+"input_forward",R.get(input,"moveForward"));out.put(prefix+"input_strafe",R.get(input,"moveStrafe"));
        out.put(prefix+"input_jump",R.get(input,"jump"));out.put(prefix+"input_sneak",R.get(input,"sneak"));
        prefix=side+".view.";read(view,out,prefix,VIEW);
        out.put(prefix+"x",R.num(view,"posX")-origin);out.put(prefix+"y",R.get(view,"posY"));out.put(prefix+"z",R.get(view,"posZ"));
        out.put(prefix+"goal_x",R.num(view,"otherPlayerMPX")-origin);out.put(prefix+"goal_y",R.get(view,"otherPlayerMPY"));out.put(prefix+"goal_z",R.get(view,"otherPlayerMPZ"));
        out.put(prefix+"server_x",(int)R.num(view,"serverPosX")-(int)Math.floor(origin*32));return out;
    }
}
