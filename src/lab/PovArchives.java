package lab;
import practice.core.MatchArchive;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class PovArchives {
    record Route(MatchArchive archive,String match,String player){}
    static final Map<Object,Route> routes=new ConcurrentHashMap<>();
    static final Map<String,MatchArchive> active=new ConcurrentHashMap<>();
    static{Runtime.getRuntime().addShutdownHook(new Thread(()->{for(MatchArchive a:active.values())a.close();},"server-pov-close"));}
    static void begin(Referee.Match m)throws Exception{
        MatchArchive a=new MatchArchive();a.root(Referee.DATA.resolve("pov"));a.begin(m.id,"server",Map.of("players",List.of(m.a,m.b),"minecraft","1.8.9","protocol",47,"wire_scope","uncompressed PLAY packet id and body; login excluded"));active.put(m.id,a);
        for(String name:List.of(m.a,m.b)){Object manager=R.get(R.get(Referee.player(name),"playerNetServerHandler"),"netManager");routes.put(R.get(manager,"channel"),new Route(a,m.id,name));}
        manifest(m,false);
    }
    static void finished(Referee.Match m){
        routes.entrySet().removeIf(e->e.getValue().match.equals(m.id));MatchArchive a=active.remove(m.id);if(a!=null)a.end();
        try{manifest(m,true);}catch(Exception e){System.err.println("[PvP Lab] Archive manifest: "+e);}
    }
    static void manifest(Referee.Match m,boolean finished)throws Exception{
        Path dir=Referee.DATA.resolve("pov/"+m.id);Files.createDirectories(dir);
        Map<String,Object> info=new LinkedHashMap<>();info.put("schema",1);info.put("match",m.id);info.put("start_ms",m.start);info.put("finish_ms",finished?m.finish:null);
        info.put("players",Map.of("a",Map.of("name",m.a,"policy",m.pa,"role",Referee.isBot(m.a)?"bot":"human"),"b",Map.of("name",m.b,"policy",m.pb,"role",Referee.isBot(m.b)?"bot":"human")));
        info.put("client_runtime",Map.of("a",Referee.clientRuntime(m.a),"b",Referee.clientRuntime(m.b)));
        info.put("server_replay","../../replays/"+m.id+".jsonl");info.put("clock","same-host UNIX milliseconds; monotonic values process-local");
        info.put("files","*-poses.jsonl.gz: timestamped two-player POV; *-events.jsonl.gz: full recorded event/packet stream; segmented files linked by capture_end");
        info.put("integrity","Inspect every stream's capture_end. Missing source or footer is not complete; raw samples, rates and gaps remain explicit.");
        Path tmp=dir.resolve("manifest.tmp");Files.writeString(tmp,Json.encode(info));Files.move(tmp,dir.resolve("manifest.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
