package practice.bridge;

import static practice.core.JsonData.map;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.*;

/** Deliberately selected game fields; never session/account or arbitrary reflection dumps. */
final class GameObservations {
    private static final Properties names=new Properties();
    private static final Map<String,Field> fields=new ConcurrentHashMap<>();
    static{try(InputStream in=GameObservations.class.getResourceAsStream("/practice/field-names.properties")){if(in!=null)names.load(in);}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    static Object field(Object object,String name){
        if(object==null)return null;
        String key=object.getClass().getName()+"#"+name;Field f=fields.get(key);
        if(f==null){
            for(Class<?> c=object.getClass();c!=null && f==null;c=c.getSuperclass())for(Field candidate:c.getDeclaredFields())
                if(candidate.getName().equals(name)||names.getProperty(candidate.getName(),"").equals(name)){candidate.setAccessible(true);f=candidate;break;}
            if(f==null)return null;fields.put(key,f);
        }
        try{return f.get(object);}catch(IllegalAccessException e){return null;}
    }
    static Map<String,Object> entity(Entity e){
        Map<String,Object> out=map("id",e.func_145782_y(),"kind",e.getClass().getSimpleName(),
            "pos",Arrays.asList(e.field_70165_t,e.field_70163_u,e.field_70161_v),
            "previous",Arrays.asList(e.field_70169_q,e.field_70167_r,e.field_70166_s),
            "motion",Arrays.asList(e.field_70159_w,e.field_70181_x,e.field_70179_y),
            "yaw",e.field_70177_z,"pitch",e.field_70125_A,"ground",e.field_70122_E,
            "sprinting",e.func_70051_ag(),"sneaking",e.func_70093_af(),"age",e.field_70173_aa);
        out.put("border",e.func_70111_Y());
        AxisAlignedBB box=e.func_174813_aQ();
        out.put("box",Arrays.asList(box.field_72340_a,box.field_72338_b,box.field_72339_c,box.field_72336_d,box.field_72337_e,box.field_72334_f));
        for(String name:new String[]{"fallDistance","width","height","stepHeight","isCollidedHorizontally","isCollidedVertically","isInWeb",
            "hurtTime","hurtResistantTime","maxHurtResistantTime","lastDamage","swingProgress","prevSwingProgress","swingProgressInt",
            "serverPosX","serverPosY","serverPosZ","otherPlayerMPPosRotationIncrements","otherPlayerMPX","otherPlayerMPY","otherPlayerMPZ",
            "otherPlayerMPYaw","otherPlayerMPPitch","rotationYawHead","prevRotationYawHead","renderYawOffset","prevRenderYawOffset",
            "jumpTicks","jumpMovementFactor","sprintingTicksLeft","serverSprintState","isAirBorne","noClip","isSwingInProgress","limbSwing","limbSwingAmount","prevLimbSwingAmount"}){
            Object v=field(e,name);if(v instanceof Number||v instanceof Boolean)out.put(name,v);
        }
        if(e instanceof EntityLivingBase){EntityLivingBase l=(EntityLivingBase)e;out.put("health",l.func_110143_aJ());out.put("eye_height",l.func_70047_e());}
        if(e instanceof EntityPlayer){
            EntityPlayer p=(EntityPlayer)e;out.put("uuid",p.func_110124_au().toString());out.put("name",p.func_70005_c_());
            if(p instanceof net.minecraft.client.entity.AbstractClientPlayer&&Minecraft.func_71410_x().func_147114_u()!=null)
                ReplaySkins.captureAppearance(out,(net.minecraft.client.entity.AbstractClientPlayer)p);
            if(p instanceof net.minecraft.client.entity.EntityPlayerSP){
                net.minecraft.client.entity.EntityPlayerSP local=(net.minecraft.client.entity.EntityPlayerSP)p;
                out.put("hand_yaw_offset",MathHelper.func_76142_g(local.field_70177_z-local.field_71154_f));
                out.put("hand_pitch_offset",local.field_70125_A-local.field_71155_g);
            }
            out.put("blocking",p.func_70632_aY());out.put("using_item",p.func_71039_bw());out.put("use_ticks",p.func_71052_bv());
            out.put("held",item(p.func_70694_bm()));out.put("slot",p.field_71071_by.field_70461_c);
            out.put("held_item_id",p.func_70694_bm()==null?0:Item.func_150891_b(p.func_70694_bm().func_77973_b()));
            Object hook=field(p,"fishEntity");if(hook instanceof Entity){Entity h=(Entity)hook;out.put("fish_hook",map("id",h.func_145782_y(),"x",h.field_70165_t,"y",h.field_70163_u,"z",h.field_70161_v));}
            out.put("food",p.func_71024_bL().func_75116_a());out.put("saturation",p.func_71024_bL().func_75115_e());
            out.put("armor_points",p.func_70658_aO());
            List<Object> armor=new ArrayList<>();for(ItemStack stack:p.field_71071_by.field_70460_b)armor.add(item(stack));out.put("armor",armor);
            Object input=field(e,"movementInput");if(input!=null){Map<String,Object> movement=new LinkedHashMap<>();
                for(String k:new String[]{"moveForward","moveStrafe","jump","sneak"})movement.put(k,field(input,k));out.put("movement_input",movement);}
        }
        return out;
    }
    private static Object item(ItemStack stack){
        if(stack==null)return null;NBTTagCompound tag=new NBTTagCompound();stack.func_77955_b(tag);return tag.toString();
    }
    static Map<String,Object> ray(Minecraft mc){
        MovingObjectPosition r=mc.field_71476_x;if(r==null)return map("type","none");
        Map<String,Object> out=map("type",r.field_72313_a.name(),"entity",r.field_72308_g==null?null:r.field_72308_g.func_145782_y());
        if(r.field_72307_f!=null)out.put("point",Arrays.asList(r.field_72307_f.field_72450_a,r.field_72307_f.field_72448_b,r.field_72307_f.field_72449_c));return out;
    }
    static Map<String,Object> state(Minecraft mc,boolean allEntities){
        long beginMs=System.currentTimeMillis(),beginNs=System.nanoTime();
        Map<String,Object> out=map("self",mc.field_71439_g==null?null:entity(mc.field_71439_g),"ray",ray(mc),
            "screen",mc.field_71462_r==null?null:mc.field_71462_r.getClass().getSimpleName(),
            "left_click_counter",field(mc,"leftClickCounter"),"right_click_delay",field(mc,"rightClickDelayTimer"));
        Map<String,Object> settings=new LinkedHashMap<>();
        for(String key:new String[]{"fovSetting","mouseSensitivity","thirdPersonView","viewBobbing","smoothCamera","renderDistanceChunks"})settings.put(key,field(mc.field_71474_y,key));
        out.put("camera_settings",settings);
        if(mc.field_71441_e!=null && allEntities){List<Object> entities=new ArrayList<>();
            for(Object object:mc.field_71441_e.field_72996_f){if(entities.size()==128){out.put("entities_truncated",true);break;}entities.add(entity((Entity)object));}out.put("entities",entities);}
        out.put("sample_begin_ms",beginMs);out.put("sample_begin_ns",beginNs);out.put("sample_end_ms",System.currentTimeMillis());out.put("sample_end_ns",System.nanoTime());
        return out;
    }
    // Small live messages contain the duel participants. Full local recordings
    // retain every loaded entity up to the explicitly recorded snapshot limit.
    static Map<String,Object> telemetry(Minecraft mc,Object names){
        Map<String,Object> out=state(mc,false);
        if(names instanceof List && mc.field_71441_e!=null)for(Object object:mc.field_71441_e.field_73010_i){
            EntityPlayer p=(EntityPlayer)object;
            if(p!=mc.field_71439_g&&!p.field_70128_L&&mc.field_71441_e.func_73045_a(p.func_145782_y())==p&&((List<?>)names).contains(p.func_70005_c_())){out.put("opponent",entity(p));break;}
        }
        out.put("sample_end_ms",System.currentTimeMillis());out.put("sample_end_ns",System.nanoTime());return out;
    }
    static Map<String,Object> replayView(Minecraft mc,Object names,float partial){
        Map<String,Object> out=telemetry(mc,names);out.put("partial_ticks",partial);
        if(mc.field_71439_g!=null)renderPose(out.get("self"),mc.field_71439_g,partial);
        if(names instanceof List&&mc.field_71441_e!=null)for(Object o:mc.field_71441_e.field_73010_i){EntityPlayer p=(EntityPlayer)o;if(p!=mc.field_71439_g&&!p.field_70128_L&&mc.field_71441_e.func_73045_a(p.func_145782_y())==p&&((List<?>)names).contains(p.func_70005_c_())){renderPose(out.get("opponent"),p,partial);break;}}
        return out;
    }
    /** Own local entities only, no lab identity/link needed. Target identity stays
     * explicit because the crosshair/nearest target can change during a session. */
    static Map<String,Object> remoteReplayView(Minecraft mc,float partial){
        java.util.List<String> names=new java.util.ArrayList<>();
        if(mc.field_71476_x!=null&&mc.field_71476_x.field_72308_g instanceof EntityPlayer){EntityPlayer p=(EntityPlayer)mc.field_71476_x.field_72308_g;if(p!=mc.field_71439_g)names.add(p.func_70005_c_());}
        if(names.isEmpty()&&mc.field_71441_e!=null&&mc.field_71439_g!=null){EntityPlayer target=null;double best=36;
            for(Object o:mc.field_71441_e.field_73010_i){EntityPlayer p=(EntityPlayer)o;if(p==mc.field_71439_g||p.field_70128_L)continue;double dx=p.field_70165_t-mc.field_71439_g.field_70165_t,dy=p.field_70163_u-mc.field_71439_g.field_70163_u,dz=p.field_70161_v-mc.field_71439_g.field_70161_v,d=dx*dx+dy*dy+dz*dz;if(d<best){best=d;target=p;}}
            if(target!=null)names.add(target.func_70005_c_());}
        Map<String,Object> out=replayView(mc,names,partial);out.put("target_names",names);out.put("source_scope","own-client; no server or opponent POV");return out;
    }
    @SuppressWarnings("unchecked") private static void renderPose(Object value,Entity e,float t){if(!(value instanceof Map))return;Map<String,Object> m=(Map<String,Object>)value;
        m.put("render",Arrays.asList(e.field_70142_S+(e.field_70165_t-e.field_70142_S)*t,e.field_70137_T+(e.field_70163_u-e.field_70137_T)*t,e.field_70136_U+(e.field_70161_v-e.field_70136_U)*t));
        m.put("render_yaw",e.field_70126_B+(e.field_70177_z-e.field_70126_B)*t);m.put("render_pitch",e.field_70127_C+(e.field_70125_A-e.field_70127_C)*t);
    }
}
