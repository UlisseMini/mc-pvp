package lab;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Runs on the real server thread. No damage cancellation, knockback edits, or immunity resets. */
public final class Referee {
    static Object server;
    static final Path DATA=R.ROOT.resolve("runtime");
    static final Map<String,Match> matches=new LinkedHashMap<>();
    static final Map<String,Properties> policies=new LinkedHashMap<>();
    static final Set<String> greeted=new HashSet<>();
    static final Map<String,String> lastDuels=new HashMap<>();
    static final Map<String,double[]> lastPings=new HashMap<>();
    static final Map<String,String> watching=new HashMap<>();
    static long ticks, errors;
    static int counter;

    static final class Match {
        String id,a,b,pa,pb,winner="",request=""; int ha,hb,goal=100,arena; long start,finish; double pingA,pingB;
        Path replay;
        Map<String,Object> state(){
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",id);m.put("a",a);m.put("b",b);m.put("policy_a",pa);m.put("policy_b",pb);
            m.put("hits_a",ha);m.put("hits_b",hb);m.put("goal",goal);m.put("ping_a",pingA);m.put("ping_b",pingB);
            m.put("start",start);m.put("finish",finish);m.put("winner",winner);m.put("arena",arena);m.put("request",request);return m;
        }
    }

    public static void tick(Object instance) {
        if(!"server".equals(System.getProperty("lab.role")))return;
        server=instance;ticks++;
        try {
            if(policies.isEmpty())loadPolicies();
            List<?> people=players();
            for(Object p:people){
                String name=R.name(p);
                if(greeted.add(name)){
                    if(isBot(name)){vanilla("gamemode 3 "+name);teleport(p,-40-Integer.parseInt(name.substring(7))*6,4,0);disable(name);}
                    else {teleport(p,0,4,0);message(p,"PvP Lab: /bots to challenge a bot; /matches and /watch to spectate. /help lab for controls.");}
                }
            }
            for(Match m:new ArrayList<>(matches.values())){
                Object a=player(m.a),b=player(m.b);
                if(a==null||b==null){m.winner=a==null?m.b:m.a;finish(m,"disconnect");continue;}
                R.call(R.call(a,"getFoodStats"),"setFoodLevel",20);R.call(R.call(b,"getFoodStats"),"setFoodLevel",20);
                if(!m.winner.isEmpty()){finish(m,"100_hits");continue;}
                if(System.currentTimeMillis()>m.start+180_000){finish(m,"timeout_draw");continue;}
                Map<String,Object> trace=new LinkedHashMap<>();trace.put("type","tick");trace.put("time",System.currentTimeMillis());
                trace.put("a",position(a));trace.put("b",position(b));trace.put("hits_a",m.ha);trace.put("hits_b",m.hb);
                append(m.replay,trace);
                if(ticks%10==0){
                    long left=m.start-System.currentTimeMillis();
                    String score=left>0?"Starting in "+((left+999)/1000)+"...":m.pa+" "+m.ha+" / 100  |  "+m.hb+" "+m.pb;
                    overlay(a,score);overlay(b,score);
                    for(var watcher:watching.entrySet())if(watcher.getValue().equals(m.id)&&player(watcher.getKey())!=null)overlay(player(watcher.getKey()),score);
                }
            }
            if(ticks%10==0){
                Map<String,Object> status=new LinkedHashMap<>();status.put("version","1.8.9");status.put("time",System.currentTimeMillis());
                status.put("players",people.stream().map(R::name).toList());status.put("policies",new ArrayList<>(policies.keySet()));
                status.put("matches",matches.values().stream().map(Match::state).toList());
                List<Map<String,Object>> details=new ArrayList<>();
                for(Object p:people){Map<String,Object> detail=position(p);detail.put("name",R.name(p));detail.put("health",R.call(p,"getHealth"));detail.put("alive",R.call(p,"isEntityAlive"));details.add(detail);}
                status.put("player_details",details);
                writeJson(DATA.resolve("status.json"),status);
            }
        }catch(Throwable e){if(errors++<10)e.printStackTrace();}
    }

    public static void restoreHealth(Object target){
        if(server==null || !"server".equals(System.getProperty("lab.role")))return;
        try{if(find(R.name(target))!=null)R.call(target,"setHealth",20.0f);}catch(Throwable e){if(errors++<10)e.printStackTrace();}
    }
    public static void hit(boolean accepted,Object target,Object source,float damage){
        if(!accepted || server==null || !"server".equals(System.getProperty("lab.role")))return;
        try{
            Object attacker=R.call(source,"getEntity");if(attacker==null)return;
            String victim=R.name(target),who=R.name(attacker); Match m=find(victim);
            if(m==null||System.currentTimeMillis()<m.start||!m.winner.isEmpty())return;
            if(who.equals(m.a)&&victim.equals(m.b))m.ha++;
            else if(who.equals(m.b)&&victim.equals(m.a))m.hb++;else return;
            append(m.replay,Map.of("type","hit","time",System.currentTimeMillis(),"attacker",who,"target",victim,"damage",damage,"hits_a",m.ha,"hits_b",m.hb));
            if(m.ha>=m.goal)m.winner=m.a;else if(m.hb>=m.goal)m.winner=m.b;
        }catch(Throwable e){if(errors++<10)e.printStackTrace();}
    }

    public static boolean command(Object sender,String raw){
        String text=raw.startsWith("/")?raw.substring(1):raw;
        String[] args=text.trim().split("\\s+");if(args.length==0)return false;
        String cmd=args[0].toLowerCase(Locale.ROOT);
        if(cmd.equals("stop")&&!R.cls("net.minecraft.entity.player.EntityPlayerMP").isInstance(sender))return false;
        boolean ours=List.of("bots","duel","matches","watch","stop","rematch","lab").contains(cmd)||(cmd.equals("help")&&args.length>1&&args[1].equals("lab"));
        if(!ours)return false;
        try{
            if(server==null){message(sender,"Lab is still starting.");return true;}
            if(cmd.equals("bots")){
                loadPolicies();message(sender,"Choose a bot with /duel <name>. Optional: /duel <name> <bot ping> <your simulated ping>.");
                for(var entry:policies.entrySet())button(sender,entry.getKey()+" — "+entry.getValue().getProperty("description",entry.getValue().getProperty("kind")),"/duel "+entry.getKey());
            }else if(cmd.equals("matches")){
                if(matches.isEmpty())message(sender,"No active matches. /lab match hold-w wtap starts one.");
                for(Match m:matches.values())button(sender,m.id+": "+m.pa+" vs "+m.pb+" ("+m.ha+"–"+m.hb+")","/watch "+m.id);
            }else if(cmd.equals("watch")){
                Match m=args.length>1?matches.get(args[1]):matches.values().stream().findFirst().orElse(null);
                if(m==null){message(sender,"No such active match. Use /matches.");return true;}
                if(find(R.name(sender))!=null){message(sender,"Use /stop before spectating.");return true;}
                vanilla("gamemode 3 "+safeName(sender));
                Object a=player(m.a),b=player(m.b);
                teleport(sender,(R.num(a,"posX")+R.num(b,"posX"))/2,10,(R.num(a,"posZ")+R.num(b,"posZ"))/2+5);
                watching.put(safeName(sender),m.id);
                if(args.length>2)R.call(sender,"setSpectatingEntity",args[2].equals("b")?b:a);
                message(sender,"Spectating "+m.id+". Fly freely; click below to follow a bot. Sneak exits its camera.");
                button(sender,"Follow "+m.pa,"/watch "+m.id+" a");button(sender,"Follow "+m.pb,"/watch "+m.id+" b");
            }else if(cmd.equals("duel")||cmd.equals("rematch")){
                String name=safeName(sender);if(player(name)==null){message(sender,"Use /lab match <bot-a> <bot-b> from the console.");return true;}
                if(find(name)!=null){message(sender,"You are already in a duel. /stop ends it.");return true;}
                String policy=cmd.equals("rematch")?lastDuels.get(name):(args.length>1?args[1]:null);
                if(policy==null){message(sender,"Usage: /duel <bot>. Use /bots for the list.");return true;}
                double[] prior=lastPings.getOrDefault(name,new double[]{0,0});
                double botPing=cmd.equals("rematch")?prior[0]:(args.length>2?ping(args[2]):0);
                double humanPing=cmd.equals("rematch")?prior[1]:(args.length>3?ping(args[3]):0);
                String bot=freeBot(Set.of());if(bot==null){message(sender,"All bot slots are busy. Try again shortly.");return true;}
                loadPolicies();start(name,bot,"human",policy,humanPing,botPing,"human-"+System.nanoTime());lastDuels.put(name,policy);lastPings.put(name,new double[]{botPing,humanPing});
            }else if(cmd.equals("stop")){
                Match m=find(R.name(sender));if(m!=null)finish(m,"stopped");else{vanilla("gamemode 0 "+safeName(sender));teleport(sender,0,4,0);}
            }else if(cmd.equals("lab")&&args.length>=4&&args[1].equals("match")){
                String a=freeBot(Set.of());String b=a==null?null:freeBot(Set.of(a));
                if(a==null||b==null){message(sender,"Need two free bot slots.");return true;}
                loadPolicies();start(a,b,args[2],args[3],args.length>4?ping(args[4]):0,args.length>5?ping(args[5]):0,args.length>6?args[6]:"manual-"+System.nanoTime());
            }else if(cmd.equals("lab")&&args.length>=3&&args[1].equals("goal")){
                message(sender,"Ladder matches always run to 100 accepted hits.");
            }else{
                message(sender,"/bots — roster; /duel <bot> [bot RTT] [your simulated RTT]; /rematch; /stop; /matches; /watch [match]; /lab match <bot-a> <bot-b> [RTT-a] [RTT-b]. All RTTs in milliseconds.");
            }
        }catch(Throwable e){e.printStackTrace();message(sender,"Lab: "+e.getMessage());}
        return true;
    }

    static void loadPolicies() throws IOException {
        policies.clear();
        try(var paths=Files.list(R.ROOT.resolve("policies"))){
            for(Path p:paths.filter(p->p.toString().endsWith(".properties")).sorted().toList()){
                String id=p.getFileName().toString().replace(".properties","");
                if(id.matches("[a-z0-9_-]{1,64}"))policies.put(id,R.properties(p));
            }
        }
    }
    static void start(String a,String b,String pa,String pb,double pingA,double pingB,String request)throws Exception{
        if(!pa.equals("human")&&!policies.containsKey(pa)||!policies.containsKey(pb))throw new IllegalArgumentException("Unknown bot; use /bots.");
        Match m=new Match();m.request=request;
        do{m.id=String.format("m%04d",++counter);}while(Files.exists(DATA.resolve("results/"+m.id+".json"))||Files.exists(DATA.resolve("replays/"+m.id+".jsonl")));
        m.a=a;m.b=b;m.pa=pa;m.pb=pb;m.pingA=pingA;m.pingB=pingB;m.start=System.currentTimeMillis()+3000;
        Set<Integer> used=new HashSet<>();for(Match current:matches.values())used.add(current.arena);
        while(used.contains(m.arena))m.arena++;
        m.replay=DATA.resolve("replays/"+m.id+".jsonl");matches.put(m.id,m);
        configureProxy(a,pingA);configureProxy(b,pingB);
        // At normal speed even a full 180-second pursuit cannot reach another arena.
        double center=4096.0*(m.arena+1);
        preparePlayer(player(a),center-3,4,0,270);
        preparePlayer(player(b),center+3,4,0,90);
        control(a,pa,b,m,pingA,pingB);control(b,pb,a,m,pingB,pingA);
        append(m.replay,Map.of("type","start","match",m.state()));
        message(player(a),"Starting "+m.id+": "+pa+" vs "+pb+". First to 100 hits. /stop to exit.");
        message(player(b),"Starting "+m.id+": "+pa+" vs "+pb+". First to 100 hits.");
        System.out.println("[PvP Lab] START "+Json.encode(m.state()));
    }
    static void preparePlayer(Object p,double x,double y,double z,int yaw){
        watching.remove(R.name(p));
        String name=safeName(p);vanilla("gamemode 0 "+name);vanilla("clear "+name);vanilla("effect "+name+" clear");
        vanilla("give "+name+" minecraft:diamond_sword 1 0 {Unbreakable:1b}");
        vanilla("tp "+name+" "+x+" "+y+" "+z+" "+yaw+" 0");
        settleTeleport(p);
        R.call(p,"setHealth",20.0f);R.call(R.call(p,"getFoodStats"),"setFoodLevel",20);
        R.set(p,"motionX",0.0);R.set(p,"motionY",0.0);R.set(p,"motionZ",0.0);
        R.call(R.get(p,"playerNetServerHandler"),"sendPacket",R.construct("net.minecraft.network.play.server.S12PacketEntityVelocity",p));
    }
    static void control(String who,String policy,String opponent,Match m,double ping,double oppPing){
        if(!isBot(who))return;
        Properties p=new Properties();p.putAll(policies.get(policy));p.setProperty("enabled","true");p.setProperty("opponent",opponent);
        p.setProperty("match",m.id);p.setProperty("start_at",Long.toString(m.start));p.setProperty("ping",Double.toString(ping));p.setProperty("opponent_ping",Double.toString(oppPing));
        R.writeProperties(DATA.resolve("bots/"+who.substring(7)+"/control.properties"),p);
    }
    static void disable(String who){if(isBot(who)){Properties p=new Properties();p.setProperty("enabled","false");R.writeProperties(DATA.resolve("bots/"+who.substring(7)+"/control.properties"),p);}}
    static void configureProxy(String who,double ping){Properties p=new Properties();p.setProperty("rtt_ms",Double.toString(ping));R.writeProperties(DATA.resolve("proxy/"+(isBot(who)?"slot"+who.substring(7):"human")+".properties"),p);}
    static void finish(Match m,String reason){
        m.finish=System.currentTimeMillis();Map<String,Object> result=m.state();result.put("reason",reason);
        result.put("duration_seconds",Math.max(0,(m.finish-m.start)/1000.0));
        writeJson(DATA.resolve("results/"+m.id+".json"),result);append(m.replay,Map.of("type","finish","result",result));
        matches.remove(m.id);
        for(String who:List.of(m.a,m.b)){
            disable(who);Object p=player(who);if(p==null)continue;
            message(p,"Finished "+m.id+": "+m.ha+"–"+m.hb+"; "+(m.winner.isEmpty()?reason:"winner "+m.winner)+". /rematch or /bots.");
            if(isBot(who)){vanilla("gamemode 3 "+who);teleport(p,-40-Integer.parseInt(who.substring(7))*6,4,0);}else teleport(p,0,4,0);
        }
        System.out.println("[PvP Lab] RESULT "+Json.encode(result));
    }
    static Match find(String name){for(Match m:matches.values())if(m.a.equals(name)||m.b.equals(name))return m;return null;}
    static boolean isBot(String name){return name.matches("LabSlot[0-9]+");}
    static List<?> players(){return (List<?>)R.get(R.call(server,"getConfigurationManager"),"playerEntityList");}
    static Object player(String name){for(Object p:players())if(R.name(p).equals(name))return p;return null;}
    static String freeBot(Set<String> excluded){for(Object p:players()){String n=R.name(p);if(isBot(n)&&find(n)==null&&!excluded.contains(n)&&(Boolean)R.call(p,"isEntityAlive"))return n;}return null;}
    static Map<String,Object> position(Object p){Map<String,Object> pos=new LinkedHashMap<>();for(String key:List.of("posX","posY","posZ","motionX","motionY","motionZ","hurtResistantTime"))pos.put(key,R.num(p,key));pos.put("sprint",R.call(p,"isSprinting"));return pos;}
    static double ping(String s){double p=Double.parseDouble(s);if(!Double.isFinite(p)||p<0||p>500)throw new IllegalArgumentException("Ping must be 0–500 ms.");return p;}
    static String safeName(Object sender){String n=R.name(sender);if(!n.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid player name");return n;}
    static void teleport(Object p,double x,double y,double z){R.call(p,"setPositionAndUpdate",x,y,z);settleTeleport(p);}
    static void settleTeleport(Object p){
        // Complete vanilla chunk subscriptions and entity membership during round setup.
        // Inactive clients may otherwise wait for chunks before sending movement, while
        // the server waits for movement before updating their destination subscriptions.
        R.call(R.call(server,"getConfigurationManager"),"serverUpdateMountedMovingPlayer",p);
        R.call(R.get(p,"worldObj"),"updateEntityWithOptionalForce",p,false);
    }
    static void vanilla(String command){R.call(R.call(server,"getCommandManager"),"executeCommand",server,command);}
    static void message(Object sender,String text){if(sender==null)return;R.call(sender,"addChatMessage",R.construct("net.minecraft.util.ChatComponentText",text));}
    static void button(Object sender,String label,String command){
        if(!R.cls("net.minecraft.entity.player.EntityPlayerMP").isInstance(sender)){message(sender,label);return;}
        vanilla("tellraw "+safeName(sender)+" "+Json.encode(Map.of("text","[ "+label+" ]","color","aqua","clickEvent",Map.of("action","run_command","value",command))));
    }
    static void overlay(Object player,String text){R.call(R.get(player,"playerNetServerHandler"),"sendPacket",R.construct("net.minecraft.network.play.server.S02PacketChat",R.construct("net.minecraft.util.ChatComponentText",text),(byte)2));}
    static void writeJson(Path path,Object value){try{Files.createDirectories(path.getParent());Path tmp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(tmp,Json.encode(value));Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){throw new RuntimeException(e);}}
    static void append(Path path,Object value){try{Files.createDirectories(path.getParent());Files.writeString(path,Json.encode(value)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException e){throw new RuntimeException(e);}}
}
