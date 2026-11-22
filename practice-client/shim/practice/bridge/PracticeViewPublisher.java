package practice.bridge;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import practice.transport.LocalViewStream;
import static practice.core.JsonData.map;

/** Common human/bot publisher; diagnostic data only, with no link to policy inputs. */
final class PracticeViewPublisher implements AutoCloseable {
    private LocalViewStream.Publisher publisher;
    private String match="";
    private long sequence,lastPublish,tickNs;
    void tick(){tickNs=System.nanoTime();}
    synchronized Map<String,Object> descriptor(){return publisher==null?map():map("port",publisher.port(),"token",publisher.token,"match",match);}
    synchronized void update(Minecraft mc,Map<String,Object> state,float partial){
        String next=String.valueOf(state.getOrDefault("match",""));
        if(mc.field_71439_g==null||mc.field_71441_e==null||next.isEmpty()||!(state.get("players") instanceof List)){close();return;}
        List<?> names=(List<?>)state.get("players");
        if(names.size()!=2||!names.contains(mc.field_71439_g.func_70005_c_())){close();return;}
        if(!match.equals(next)){close();match=next;try{publisher=new LocalViewStream.Publisher();}catch(java.io.IOException e){throw new IllegalStateException("Local view publisher unavailable",e);}}
        long ns=System.nanoTime();if(ns-lastPublish<1000000)return;lastPublish=ns;
        EntityPlayer other=null;
        for(Object o:mc.field_71441_e.field_73010_i){EntityPlayer p=(EntityPlayer)o;
            if(p!=mc.field_71439_g&&names.contains(p.func_70005_c_())&&mc.field_71441_e.func_73045_a(p.func_145782_y())==p&&!p.field_70128_L){other=p;break;}}
        if(other==null)return;
        double[] values=new double[36];entity(mc.field_71439_g,values,0,partial);entity(other,values,18,partial);
        publisher.publish(new LocalViewStream.Sample(++sequence,ns,System.currentTimeMillis(),tickNs,values));
    }
    private static void entity(EntityPlayer p,double[] v,int i,float t){
        v[i]=p.field_70165_t;v[i+1]=p.field_70163_u;v[i+2]=p.field_70161_v;
        v[i+3]=p.field_70142_S+(p.field_70165_t-p.field_70142_S)*t;
        v[i+4]=p.field_70137_T+(p.field_70163_u-p.field_70137_T)*t;
        v[i+5]=p.field_70136_U+(p.field_70161_v-p.field_70136_U)*t;
        v[i+6]=p.field_70177_z;v[i+7]=p.field_70125_A;v[i+8]=p.func_70047_e();
        AxisAlignedBB b=p.func_174813_aQ();v[i+9]=b.field_72340_a;v[i+10]=b.field_72338_b;v[i+11]=b.field_72339_c;
        v[i+12]=b.field_72336_d;v[i+13]=b.field_72337_e;v[i+14]=b.field_72334_f;v[i+15]=p.func_70111_Y();
        v[i+16]=p.func_70093_af()?1:0;v[i+17]=p.field_70173_aa;
    }
    public synchronized void close(){if(publisher!=null)publisher.close();publisher=null;match="";}
}
