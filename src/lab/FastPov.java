package lab;

import java.util.*;
import practice.transport.LocalViewStream;

/** Diagnostic-only publisher; never supplies information to a bot policy. */
public final class FastPov {
    private static LocalViewStream.Publisher publisher;
    private static String match="";
    private static long sequence,lastPublish,tickNs;
    static void tick(){
        tickNs=System.nanoTime();
        boolean wanted=Client.controls.getProperty("enabled","false").equals("true")&&Client.controls.getProperty("fast_view","0").equals("1");
        String next=wanted?Client.controls.getProperty("match",""):"";
        if(!next.equals(match)){if(publisher!=null)publisher.close();publisher=null;match=next;
            if(wanted)try{publisher=new LocalViewStream.Publisher();}catch(Exception e){System.err.println("[PvP Lab] Local view unavailable: "+e.getClass().getSimpleName());}}
    }
    static Map<String,Object> descriptor(){return publisher==null?Map.of():Map.of("port",publisher.port(),"token",publisher.token,"match",match);}
    static void frame(){
        if(publisher==null||Client.player==null||Client.opponent==null||!Client.targetReady||!match.equals(Client.match))return;
        Object world=R.get(Client.mc,"theWorld");if(world==null||R.call(world,"getEntityByID",(int)R.call(Client.opponent,"getEntityId"))!=Client.opponent)return;
        long ns=System.nanoTime();if(ns-lastPublish<1_000_000)return;lastPublish=ns;
        double[] values=new double[36];float partial=((Number)R.get(R.get(Client.mc,"timer"),"renderPartialTicks")).floatValue();
        entity(Client.player,values,0,partial);entity(Client.opponent,values,18,partial);
        publisher.publish(new LocalViewStream.Sample(++sequence,ns,System.currentTimeMillis(),tickNs,values));
    }
    private static void entity(Object p,double[] v,int i,float partial){
        String[] xyz={"posX","posY","posZ"},previous={"lastTickPosX","lastTickPosY","lastTickPosZ"};
        for(int n=0;n<3;n++){v[i+n]=R.num(p,xyz[n]);double last=R.num(p,previous[n]);v[i+3+n]=last+(v[i+n]-last)*partial;}
        v[i+6]=R.num(p,"rotationYaw");v[i+7]=R.num(p,"rotationPitch");v[i+8]=((Number)R.call(p,"getEyeHeight")).doubleValue();
        Object box=R.call(p,"getEntityBoundingBox");String[] keys={"minX","minY","minZ","maxX","maxY","maxZ"};
        for(int n=0;n<6;n++)v[i+9+n]=R.num(box,keys[n]);v[i+15]=((Number)R.call(p,"getCollisionBorderSize")).doubleValue();
        v[i+16]=(Boolean)R.call(p,"isSneaking")?1:0;v[i+17]=R.num(p,"ticksExisted");
    }
}
