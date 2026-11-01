package lab;
import practice.core.MatchArchive;
import java.util.*;

/** Independent, bounded replay archive; recording never supplies bot decisions. */
public final class BotArchive {
    static final MatchArchive archive=new MatchArchive();private static long sampled;
    static{archive.root(R.ROOT.resolve("runtime/pov"));Runtime.getRuntime().addShutdownHook(new Thread(archive::close,"bot-archive-close"));}
    static void tick(){String match=Client.controls.getProperty("enabled","false").equals("true")?Client.controls.getProperty("match",""):"";
        if(!match.matches("m[0-9]{4,9}")){archive.end();return;}
        archive.begin(match,Client.controls.getProperty("archive_side","b")+"-client",Map.of("player","LabSlot"+Client.SLOT,"role","bot","pose_hz",60,"minecraft","1.8.9","agent_sha256",System.getProperty("lab.agent_sha256","unknown")));
    }
    static void frame(){
        if(Client.player==null||Client.opponent==null||!Client.targetReady||!archive.match().equals(Client.match)||archive.match().isEmpty())return;
        long ns=System.nanoTime();if(ns-sampled<16666666)return;sampled=ns;
        float partial=((Number)R.get(R.get(Client.mc,"timer"),"renderPartialTicks")).floatValue();
        archive.pose(System.currentTimeMillis(),ns,Map.of("self",entity(Client.player,partial),"opponent",entity(Client.opponent,partial),"partial_ticks",partial,"client_tick",Client.ticks));
    }
    static Map<String,Object> entity(Object e,float partial){Map<String,Object> p=ScienceTrace.state(e);p.putAll(Referee.replayPosition(e));
        p.put("name",R.name(e));p.put("id",R.call(e,"getEntityId"));p.put("health",R.call(e,"getHealth"));p.put("eye_height",R.call(e,"getEyeHeight"));
        List<Double> render=new ArrayList<>();for(String axis:List.of("X","Y","Z")){double a=R.num(e,"lastTickPos"+axis);render.add(a+(R.num(e,"pos"+axis)-a)*partial);}p.put("render",render);
        p.put("render_yaw",R.num(e,"prevRotationYaw")+(R.num(e,"rotationYaw")-R.num(e,"prevRotationYaw"))*partial);p.put("render_pitch",R.num(e,"prevRotationPitch")+(R.num(e,"rotationPitch")-R.num(e,"prevRotationPitch"))*partial);
        for(String k:List.of("swingProgress","prevSwingProgress","limbSwing","limbSwingAmount","renderYawOffset","prevRenderYawOffset"))p.put(k,R.num(e,k));return p;
    }
}
