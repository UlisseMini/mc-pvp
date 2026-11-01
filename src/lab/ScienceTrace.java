package lab;

import java.util.*;

/** Read-only native transition probes. Data is written to disk, never fed to a policy. */
public final class ScienceTrace {
    private static Map<String,Object> movement, interpolation, attack, click;
    private static long sequence;
    static boolean client() {
        return "client".equals(System.getProperty("lab.role")) && Client.active()
            && Client.controls.getProperty("science_trace","0").equals("1");
    }
    static Map<String,Object> state(Object entity) {
        Map<String,Object> s=new LinkedHashMap<>();
        for(String k:Compat.list("posX","posY","posZ","motionX","motionY","motionZ","rotationYaw","rotationYawHead","prevRotationYawHead","rotationPitch","hurtResistantTime","fallDistance","lastDamage"))s.put(k,R.num(entity,k));
        s.put("ground",R.get(entity,"onGround"));s.put("sprint",R.call(entity,"isSprinting"));
        Object box=R.call(entity,"getEntityBoundingBox");
        s.put("box",Compat.list(R.num(box,"minX"),R.num(box,"minY"),R.num(box,"minZ"),R.num(box,"maxX"),R.num(box,"maxY"),R.num(box,"maxZ")));
        for(String k:Compat.list("width","height","stepHeight"))s.put(k,R.num(entity,k));
        for(String k:Compat.list("noClip","isInWeb","isCollidedHorizontally","isCollidedVertically","isCollided"))s.put(k,R.get(entity,k));
        s.put("sneaking",R.call(entity,"isSneaking"));
        return s;
    }
    static Map<String,Object> event(String type) {
        Map<String,Object> e=new LinkedHashMap<>();e.put("type",type);e.put("time",System.currentTimeMillis());
        e.put("monotonic_ns",System.nanoTime());e.put("sequence",++sequence);e.put("tick",Client.ticks);e.put("match",Client.match);return e;
    }
    /** Observe actual vanilla click consumption, including native miss cooldown. */
    public static void clickBefore(Object minecraft){
        if(!client())return;
        try{
            click=event("science_click");click.put("miss_cooldown_before",R.num(minecraft,"leftClickCounter"));
            click.put("own",state(Client.player));click.put("target",state(Client.opponent));
            Object ray=R.get(minecraft,"objectMouseOver"),selected=ray==null?null:R.get(ray,"entityHit");
            click.put("ray_type",ray==null?"null":R.get(ray,"typeOfHit").toString());
            click.put("selected_target",selected==Client.opponent);
            Object point=ray==null?null:R.get(ray,"hitVec");
            if(point!=null)click.put("ray_point",Compat.list(R.num(point,"xCoord"),R.num(point,"yCoord"),R.num(point,"zCoord")));
        }catch(Throwable e){click=null;Client.fail(e);}
    }
    public static void clickAfter(Object minecraft){
        if(click==null)return;
        try{click.put("miss_cooldown_after",R.num(minecraft,"leftClickCounter"));
            click.put("monotonic_end_ns",System.nanoTime());Client.log(click);click=null;
        }catch(Throwable e){click=null;Client.fail(e);}
    }
    static long clickId(){return click==null?-1:((Number)click.get("sequence")).longValue();}
    public static void movementBefore(Object self,float strafe,float forward) {
        if(!client() || self!=Client.player)return;
        try {
            movement=event("science_move");movement.put("before",state(self));
            movement.put("strafe",strafe);movement.put("forward",forward);
            movement.put("ground_acceleration",R.call(self,"getAIMoveSpeed"));
            movement.put("air_acceleration",R.num(self,"jumpMovementFactor"));
            movement.put("water",R.call(self,"isInWater"));movement.put("lava",R.call(self,"isInLava"));
            movement.put("ladder",R.call(self,"isOnLadder"));
            Object world=R.get(self,"worldObj"),box=R.call(self,"getEntityBoundingBox");
            int supportY=(int)Math.floor(R.num(box,"minY"))-1;
            Object support=R.construct("net.minecraft.util.BlockPos",(int)Math.floor(R.num(self,"posX")),supportY,(int)Math.floor(R.num(self,"posZ")));
            Object block=R.call(R.call(world,"getBlockState",support),"getBlock");
            movement.put("floor_slipperiness",R.num(block,"slipperiness"));movement.put("support_block_y",supportY);
            movement.put("floor_top_y",4.0);movement.put("loaded_column",loadedColumn(self));
        } catch(Throwable e){Client.fail(e);}
    }
    public static void movementAfter(Object self) {
        if(!client() || self!=Client.player || movement==null)return;
        try {movement.put("after",state(self));movement.put("loaded_column_after",loadedColumn(self));Client.log(movement);movement=null;}catch(Throwable e){Client.fail(e);}
    }
    static boolean loadedColumn(Object self){
        // Native gravity branch uses truncating casts here, unlike support lookup.
        Object pos=R.construct("net.minecraft.util.BlockPos",(int)R.num(self,"posX"),0,(int)R.num(self,"posZ"));
        Object world=R.get(self,"worldObj");
        return (Boolean)R.call(world,"isBlockLoaded",pos)
            && (Boolean)R.call(R.call(world,"getChunkFromBlockCoords",pos),"isLoaded");
    }
    public static void interpolationBefore(Object self) {
        if(!client() || self!=Client.opponent)return;
        try {
            interpolation=event("science_interpolation");interpolation.put("before",state(self));
            interpolation.put("steps",R.num(self,"otherPlayerMPPosRotationIncrements"));
            interpolation.put("goal",Compat.list(R.num(self,"otherPlayerMPX"),R.num(self,"otherPlayerMPY"),R.num(self,"otherPlayerMPZ")));
        }catch(Throwable e){Client.fail(e);}
    }
    public static void interpolationAfter(Object self) {
        if(!client() || self!=Client.opponent || interpolation==null)return;
        try {interpolation.put("after",state(self));Client.log(interpolation);interpolation=null;}catch(Throwable e){Client.fail(e);}
    }
    public static void remoteUpdate(Object self) {
        if(!client() || self!=Client.opponent)return;
        try {Map<String,Object> e=event("science_remote_update");e.put("steps",R.num(self,"otherPlayerMPPosRotationIncrements"));
            e.put("goal",Compat.list(R.num(self,"otherPlayerMPX"),R.num(self,"otherPlayerMPY"),R.num(self,"otherPlayerMPZ")));Client.log(e);
        }catch(Throwable e){Client.fail(e);}
    }
}
