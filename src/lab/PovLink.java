package lab;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Versioned diagnostic messages over Minecraft's existing localhost connection.
 * No commands, remote code, input control, extra listeners or policy information. */
public final class PovLink {
    public static final String CHANNEL="PvpLab|v1";
    private record Incoming(Object handler,String json,long received){}
    private static final ArrayBlockingQueue<Incoming> inbox=new ArrayBlockingQueue<>(256);
    private static final Map<Object,Boolean> seen=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String,Peer> peers=new HashMap<>();
    private static long helloAt,telemetryAt,errors,dropped;
    private static final class Peer {String session="";long seen,clientMs,received,window;int count;Map<String,Object> view=Map.of(),fast=Map.of();}

    public static void receive(Object handler,Object packet){
        if(!"server".equals(System.getProperty("lab.role")))return;
        try{
            if(!CHANNEL.equals(R.call(packet,"getChannelName")))return;
            Object network=R.get(handler,"netManager");Object address=R.call(network,"getRemoteAddress");
            if(!(address instanceof InetSocketAddress remote)||!remote.getAddress().isLoopbackAddress())return;
            synchronized(seen){if(seen.put(packet,true)!=null)return;}
            Object buffer=R.call(packet,"getBufferData");int size=(int)R.call(buffer,"readableBytes");if(size<2||size>30000)return;
            byte[] bytes=new byte[size];R.call(buffer,"getBytes",(int)R.call(buffer,"readerIndex"),bytes);
            String json=new String(bytes,StandardCharsets.UTF_8);if(!bounded(json))return;
            if(!inbox.offer(new Incoming(handler,json,System.currentTimeMillis())))dropped++;
        }catch(Throwable e){if(errors++<4)e.printStackTrace();}
    }
    public static boolean subscribed(String player){Peer p=peers.get(player);return p!=null&&System.currentTimeMillis()-p.seen<6000;}
    @SuppressWarnings("unchecked") public static void serverTick(){
        try{
            long now=System.currentTimeMillis();Incoming incoming;int limit=0;
            while(limit++<64&&(incoming=inbox.poll())!=null){
                Object player=R.get(incoming.handler,"playerEntity");String name=R.name(player);
                if(Referee.player(name)!=player)continue;
                Peer peer=peers.computeIfAbsent(name,k->new Peer());
                if(now-peer.window>=1000){peer.window=now;peer.count=0;}if(++peer.count>20)continue;
                Map<String,Object> data;
                try{data=(Map<String,Object>)R.call(R.construct("com.google.gson.Gson"),"fromJson",incoming.json,Map.class);}catch(RuntimeException e){continue;}
                if(data==null||!(data.get("version") instanceof Number version)||version.intValue()!=1)continue;
                String type=String.valueOf(data.get("type"));
                if(!Set.of("hello","telemetry","marker","ping").contains(type))continue;
                peer.seen=now;
                String session=String.valueOf(data.getOrDefault("session",""));if(session.length()>100)continue;
                if(!session.equals(peer.session)){peer.view=Map.of();peer.clientMs=0;peer.received=0;}peer.session=session;
                long sent=data.get("client_ms") instanceof Number n?n.longValue():0;
                Referee.Match m=Referee.find(name);String match=m==null?"":m.id;
                if(type.equals("hello")||type.equals("ping")){
                    if(Referee.isBot(name)&&data.get("fast_view") instanceof Map fast)peer.fast=fast;
                    Map<String,Object> reply=new LinkedHashMap<>();reply.put("type",type.equals("hello")?"welcome":"pong");reply.put("version",1);
                    reply.put("match",match);reply.put("client_send_ms",sent);reply.put("server_receive_ms",incoming.received);reply.put("server_send_ms",System.currentTimeMillis());
                    reply.put("server_tick",Referee.ticks);reply.put("clock","same-host UNIX ms; delivery includes configured ping");send(player,reply);
                }else if(type.equals("telemetry")&&data.get("view") instanceof Map view){
                    // A delayed packet from the preceding duel must not become
                    // an observation of the new opponent/match.
                    if(m!=null&&!m.id.equals(data.get("match")))continue;
                    peer.view=Collections.unmodifiableMap(view);peer.clientMs=sent;peer.received=incoming.received;
                    if(m!=null)Referee.append(m.replay,event("pov_client",Map.of("player",name,"role",Referee.isBot(name)?"bot":"human","session",session,"client_ms",sent,"received_ms",incoming.received,"view",view)));
                }else if(type.equals("marker")){
                    String label=String.valueOf(data.getOrDefault("value",""));if(label.length()>120)label=label.substring(0,120);
                    Map<String,Object> marker=event("practice_marker",Map.of("player",name,"session",session,"label",label,"client_ms",sent));
                    if(m!=null)Referee.append(m.replay,marker);
                    send(player,Map.of("type","marker_ack","version",1,"match",match,"label",label,"server_ms",now));
                }
            }
            peers.entrySet().removeIf(e->now-e.getValue().seen>10000||Referee.player(e.getKey())==null);
            if(Referee.ticks%4!=0)return;
            for(var entry:peers.entrySet()){
                String name=entry.getKey();if(Referee.isBot(name))continue;
                Object player=Referee.player(name);Referee.Match m=Referee.find(name);
                Map<String,Object> reply=new LinkedHashMap<>();reply.put("type","state");reply.put("version",1);reply.put("server_ms",now);reply.put("server_tick",Referee.ticks);
                reply.put("match",m==null?"":m.id);reply.put("observer_only",true);
                if(m!=null){
                    Object a=Referee.player(m.a),b=Referee.player(m.b);if(a==null||b==null)continue;
                    Map<String,Object> server=Map.of("a",state(a),"b",state(b));reply.put("server",server);
                    reply.put("mode",m.mode);reply.put("players",List.of(m.a,m.b));reply.put("policies",List.of(m.pa,m.pb));
                    reply.put("rtt_ms",List.of(m.pingA,m.pingB));reply.put("hits",List.of(m.ha,m.hb));reply.put("combat_start",m.start);
                    reply.put("reach",List.of(DuelSettings.reach(m.configA),DuelSettings.reach(m.configB)));
                    if(m.drillRound>0)reply.put("drill",Map.of("round",m.drillRound,"preset",m.drillPreset,"gap",m.startGap));
                    Peer botPeer=peers.get(name.equals(m.a)?m.b:m.a);
                    if(botPeer!=null&&m.id.equals(botPeer.fast.get("match")))reply.put("fast_view",botPeer.fast);
                    Map<String,Object> views=new LinkedHashMap<>();
                    for(String who:List.of(m.a,m.b)){Peer other=peers.get(who);if(other!=null&&!other.view.isEmpty()&&other.received>=m.start)views.put(who,Map.of("client_ms",other.clientMs,"received_ms",other.received,"age_ms",now-other.received,"session",other.session,"view",other.view));}
                    reply.put("clients",views);
                    Referee.append(m.replay,event("pov_server",Map.of("match",m.id,"server",server)));
                }
                send(player,reply);
            }
        }catch(Throwable e){if(errors++<4)e.printStackTrace();}
    }
    private static Map<String,Object> event(String type,Map<String,Object> data){
        Map<String,Object> e=new LinkedHashMap<>(data);e.put("type",type);e.put("time",System.currentTimeMillis());e.put("monotonic_ns",System.nanoTime());e.put("server_tick",Referee.ticks);return e;
    }
    private static Map<String,Object> state(Object player){
        Map<String,Object> out=ScienceTrace.state(player);out.put("id",R.call(player,"getEntityId"));out.put("name",R.name(player));
        out.put("health",R.call(player,"getHealth"));out.put("blocking",R.call(player,"isBlocking"));out.put("hurt_time",R.num(player,"hurtTime"));return out;
    }
    private static void send(Object player,Map<String,Object> data){
        byte[] bytes=Json.encode(data).getBytes(StandardCharsets.UTF_8);if(bytes.length>30000){dropped++;return;}
        Object buffer=R.construct("net.minecraft.network.PacketBuffer",R.call(R.cls("io.netty.buffer.Unpooled"),"wrappedBuffer",bytes));
        R.call(R.get(player,"playerNetServerHandler"),"sendPacket",R.construct("net.minecraft.network.play.server.S3FPacketCustomPayload",CHANNEL,buffer));
    }
    public static void clientTick(){
        try{
            if(Client.player==null||Client.mc==null)return;long now=System.currentTimeMillis();
            if(now-helloAt>=500){helloAt=now;clientSend(Map.of("type","hello","version",1,"session","bot-"+Client.SLOT,"client_ms",now,"fast_view",FastPov.descriptor()));}
            if(!Client.active()||Client.opponent==null||now-telemetryAt<200)return;
            if(!Client.match.equals(Client.controls.getProperty("match",""))||!R.name(Client.opponent).equals(Client.controls.getProperty("opponent","")))return;
            telemetryAt=now;
            Map<String,Object> view=new LinkedHashMap<>();view.put("self",state(Client.player));view.put("opponent",state(Client.opponent));
            Object ray=R.get(Client.mc,"objectMouseOver"),selected=ray==null?null:R.get(ray,"entityHit");
            view.put("ray_target",selected==null?null:R.call(selected,"getEntityId"));view.put("ray_matches_opponent",selected==Client.opponent);
            Map<String,Object> data=new LinkedHashMap<>();data.put("type","telemetry");data.put("version",1);data.put("session","bot-"+Client.SLOT);data.put("client_ms",now);data.put("client_tick",Client.ticks);data.put("view",view);
            data.put("match",Client.match);
            clientSend(data);
        }catch(Throwable e){if(errors++<4)e.printStackTrace();}
    }
    private static void clientSend(Map<String,Object> data){
        byte[] bytes=Json.encode(data).getBytes(StandardCharsets.UTF_8);if(bytes.length>30000)return;
        Object buffer=R.construct("net.minecraft.network.PacketBuffer",R.call(R.cls("io.netty.buffer.Unpooled"),"wrappedBuffer",bytes));
        R.call(R.get(Client.player,"sendQueue"),"addToSendQueue",R.construct("net.minecraft.network.play.client.C17PacketCustomPayload",CHANNEL,buffer));
    }
    static boolean bounded(String s){
        boolean quoted=false,escape=false;int depth=0;
        for(char c:s.toCharArray()){if(escape){escape=false;continue;}if(quoted&&c=='\\'){escape=true;continue;}if(c=='"'){quoted=!quoted;continue;}
            if(!quoted){if(c=='{'||c=='['){if(++depth>24)return false;}if(c=='}'||c==']')if(--depth<0)return false;}}
        return !quoted&&depth==0;
    }
}
