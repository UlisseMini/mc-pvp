package lab;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Copies full PLAY wire bytes into bounded asynchronous archives, never login/authentication. */
public final class ArchiveWire {
    private record Raw(PovArchives.Route route,byte[] bytes,long ms,long ns){}
    private static final ThreadLocal<Raw> incoming=new ThreadLocal<>();private static final AtomicLong sequence=new AtomicLong();
    private static long errors;
    private static Raw copy(Object ctx,Object buffer){
        Object channel=R.call(ctx,"channel"),state=R.call(R.call(channel,"attr",R.get(R.cls("net.minecraft.network.NetworkManager"),"attrKeyConnectionState")),"get");
        if(state==null||!state.toString().equals("PLAY"))return null;
        PovArchives.Route route;
        if("server".equals(System.getProperty("lab.role")))route=PovArchives.routes.get(channel);
        else{if(BotArchive.archive.match().isEmpty())return null;route=new PovArchives.Route(BotArchive.archive,BotArchive.archive.match(),"LabSlot"+Client.SLOT);}
        if(route==null)return null;int n=(int)R.call(buffer,"readableBytes");if(n>2097152||n<1){route.archive().event(Json.encode(Map.of("type","packet_omitted","time_ms",System.currentTimeMillis(),"bytes",n)));return null;}
        byte[] bytes=new byte[n];long ns=System.nanoTime(),ms=System.currentTimeMillis();R.call(buffer,"getBytes",(int)R.call(buffer,"readerIndex"),bytes);return new Raw(route,bytes,ms,ns);
    }
    public static void before(Object ctx,Object buffer){try{incoming.set(copy(ctx,buffer));}catch(Throwable e){incoming.remove();error(e);}}
    public static void after(Object packets){try{Raw r=incoming.get();incoming.remove();List<?> p=(List<?>)packets;if(r!=null&&!p.isEmpty())write(r,"in",p.get(p.size()-1));}catch(Throwable e){error(e);}}
    public static void out(Object ctx,Object packet,Object buffer){try{Raw r=copy(ctx,buffer);if(r!=null)write(r,"out",packet);}catch(Throwable e){error(e);}}
    private static void write(Raw r,String direction,Object packet){
        Map<String,Object> data=new LinkedHashMap<>();data.put("direction",direction);data.put("kind",packet.getClass().getName());
        data.put("bytes",r.bytes.length);data.put("base64",Base64.getEncoder().encodeToString(r.bytes));
        if("server".equals(System.getProperty("lab.role")))data.put("packet_id",ServerReplicationTrace.packetId(packet));
        r.route.archive().event(Json.encode(Map.of("type","packet_wire","time_ms",r.ms,"monotonic_ns",r.ns,"sequence",sequence.incrementAndGet(),"match",r.route.match(),"player",r.route.player(),"data",data)));
    }
    private static void error(Throwable e){String line=Json.encode(Map.of("type","observer_error","time_ms",System.currentTimeMillis(),"observer","archive_wire","reason",e.toString()));if("server".equals(System.getProperty("lab.role")))for(var a:PovArchives.active.values())a.event(line);else BotArchive.archive.event(line);if(errors++<3)System.err.println("[PvP Lab] Archive wire error: "+e);}
}
