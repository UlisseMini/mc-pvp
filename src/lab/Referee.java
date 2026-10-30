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
    static final Map<String,String> preferredModes=new HashMap<>(),lastModes=new HashMap<>();
    static final Set<String> mutedBlockSounds=new HashSet<>(),pendingLobby=new HashSet<>();
    static final Map<String,String> watching=new HashMap<>();
    static final Map<String,Long> pendingTracking=new LinkedHashMap<>();
    static final Set<Integer> preparedArenas=new HashSet<>();
    static long ticks, errors;
    static int counter;

    static final class Match {
        String id,a,b,pa,pb,winner="",request="",mode="boxing",finishReason="100_hits"; int ha,hb,ba,bb,goal=100,arena,pingChanges; long start,finish,durationMs=180_000; boolean scenario,setupReseated; double pingA,pingB,initialPingA,initialPingB;
        boolean boxing(){return mode.equals("boxing");}
        boolean box(){return mode.equals("diamond_box");}
        final Properties configA=new Properties(),configB=new Properties();
        Object world;
        Path replay;
        Map<String,Object> state(){
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",id);m.put("a",a);m.put("b",b);m.put("policy_a",pa);m.put("policy_b",pb);
            m.put("hits_a",ha);m.put("hits_b",hb);m.put("goal",goal);m.put("ping_a",pingA);m.put("ping_b",pingB);
            m.put("mode",mode);m.put("blocks_a",ba);m.put("blocks_b",bb);
            m.put("start",start);m.put("finish",finish);m.put("winner",winner);m.put("arena",arena);m.put("request",request);m.put("scenario",scenario);m.put("duration_limit_ms",durationMs);
            m.put("reaction_ms_a",Integer.parseInt(configA.getProperty("reaction_ms","150")));
            m.put("reaction_ms_b",Integer.parseInt(configB.getProperty("reaction_ms","150")));
            m.put("click_cps_a",pa.equals("human")?null:ClickRate.cps(configA));
            m.put("click_cps_b",pb.equals("human")?null:ClickRate.cps(configB));
            m.put("armor_protection",boxing()?0:4);m.put("weapon",box()?"wooden_sword":"diamond_sword");
            m.put("arena_surface",box()?"bedrock_1x1_interior_y4_5_barrier_ceiling_y6":"bedrock_y3_air_y4_19_radius128_verified_v3");return m;
        }
    }

    public static void tick(Object instance) {
        if(!"server".equals(System.getProperty("lab.role")))return;
        server=instance;ticks++;
        try {
            if(policies.isEmpty())loadPolicies();
            List<?> people=players();
            for(String name:new ArrayList<>(pendingLobby)){
                Object p=player(name);
                if(p==null)continue;
                if((Boolean)R.call(p,"isEntityAlive")){returnToLobby(p);pendingLobby.remove(name);}
            }
            for(var entry:new ArrayList<>(pendingTracking.entrySet())){
                if(System.currentTimeMillis()<entry.getValue())continue;
                Object p=player(entry.getKey());
                if(p!=null){
                    Object tracker=R.call(R.get(p,"worldObj"),"getEntityTracker");
                    Object existing=R.call(R.get(tracker,"trackedEntityHashTable"),"lookup",(int)R.call(p,"getEntityId"));
                    if(existing==null)R.call(tracker,"trackEntity",p);
                }
                pendingTracking.remove(entry.getKey());
            }
            for(Object p:people){
                String name=R.name(p);
                if(greeted.add(name)){
                    if(isBot(name)){vanilla("gamemode 3 "+name);teleport(p,-40-Integer.parseInt(name.substring(7))*6,4,0);disable(name);}
                    else {teleport(p,0,4,0);message(p,"PvP Lab: /bots; /mode diamond for health duels; /mode diamond_box for 1x1 blockhitting; /mode boxing for 100 hits. Successful blocks play a chime (/blocksound). /help lab for controls.");}
                }
            }
            for(Match m:new ArrayList<>(matches.values())){
                Object a=player(m.a),b=player(m.b);
                if(a==null||b==null){m.winner=a==null?m.b:m.a;finish(m,"disconnect");continue;}
                long setupNow=System.currentTimeMillis();
                if(!m.setupReseated&&setupNow<m.start&&setupNow>=m.start-1500){
                    // Initial destination chunks may arrive after the teleport,
                    // allowing a client to fall into the floor during countdown.
                    // Re-seat once after terrain delivery, strictly before combat.
                    seatPlayers(m,a,b);
                    m.setupReseated=true;
                    append(m.replay,Map.of("type","setup_reseat","time",setupNow,"combat_start",m.start));
                }
                if(System.currentTimeMillis()>m.start&&(R.num(a,"posY")<4-1e-7||R.num(b,"posY")<4-1e-7)){
                    finish(m,"invalid_arena_floor");continue;
                }
                if(m.boxing()){R.call(R.call(a,"getFoodStats"),"setFoodLevel",20);R.call(R.call(b,"getFoodStats"),"setFoodLevel",20);}
                if(!m.boxing()&&(((Number)R.call(a,"getHealth")).floatValue()<=0||((Number)R.call(b,"getHealth")).floatValue()<=0)){
                    boolean aliveA=(Boolean)R.call(a,"isEntityAlive"),aliveB=(Boolean)R.call(b,"isEntityAlive");
                    m.winner=aliveA==aliveB?"":aliveA?m.a:m.b;finish(m,"death");continue;
                }
                if(!m.winner.isEmpty()){finish(m,m.finishReason);continue;}
                if(ticks%10==0&&System.currentTimeMillis()>m.start+2000&&!validBotViews(m)){
                    finish(m,"invalid_client_view");continue;
                }
                if(System.currentTimeMillis()>m.start+m.durationMs){finish(m,m.scenario?"scenario_completed":"timeout_draw");continue;}
                Map<String,Object> trace=new LinkedHashMap<>();trace.put("type","tick");trace.put("time",System.currentTimeMillis());
                trace.put("a",position(a));trace.put("b",position(b));trace.put("hits_a",m.ha);trace.put("hits_b",m.hb);
                trace.put("health_a",R.call(a,"getHealth"));trace.put("health_b",R.call(b,"getHealth"));
                append(m.replay,trace);
                if(ticks%10==0){
                    long left=m.start-System.currentTimeMillis();
                    String score=left>0?"Starting "+m.mode+" in "+((left+999)/1000)+"...":
                        (m.boxing()?m.pa+" "+m.ha+" / 100  |  "+m.hb+" "+m.pb:
                        m.pa+" "+hearts(a)+" hearts  |  "+hearts(b)+" "+m.pb)+"  |  Blocks "+m.ba+"/"+m.bb+"  |  RTT "+(int)m.pingA+"/"+(int)m.pingB+" ms";
                    overlay(a,score);overlay(b,score);
                    for(var watcher:watching.entrySet())if(watcher.getValue().equals(m.id)&&player(watcher.getKey())!=null)overlay(player(watcher.getKey()),score);
                }
            }
            if(ticks%10==0){
                Map<String,Object> status=new LinkedHashMap<>();status.put("version","1.8.9");status.put("time",System.currentTimeMillis());
                status.put("players",people.stream().map(R::name).toList());status.put("policies",new ArrayList<>(policies.keySet()));
                status.put("matches",matches.values().stream().map(Match::state).toList());
                List<Map<String,Object>> details=new ArrayList<>();
                for(Object p:people){Map<String,Object> detail=position(p);detail.put("name",R.name(p));detail.put("health",R.call(p,"getHealth"));detail.put("alive",R.call(p,"isEntityAlive"));
                    detail.put("armor_points",R.call(p,"getTotalArmorValue"));detail.put("food",R.call(R.call(p,"getFoodStats"),"getFoodLevel"));detail.put("blocking",R.call(p,"isBlocking"));details.add(detail);}
                status.put("player_details",details);
                writeJson(DATA.resolve("status.json"),status);
            }
        }catch(Throwable e){if(errors++<10)e.printStackTrace();}
    }

    public static void restoreHealth(Object target){
        if(server==null || !"server".equals(System.getProperty("lab.role")))return;
        try{Match m=find(R.name(target));if(m!=null&&(m.boxing()||System.currentTimeMillis()<m.start))R.call(target,"setHealth",20.0f);}catch(Throwable e){if(errors++<10)e.printStackTrace();}
    }
    /** Called only when vanilla executed its (damage+1)/2 sword-block branch. */
    public static void blockedDamage(Object target,Object source,float before,float after){
        if(server==null||!"server".equals(System.getProperty("lab.role"))||!(after<before))return;
        try{
            Object attacker=R.call(source,"getEntity");if(attacker==null)return;
            String victim=R.name(target),who=R.name(attacker);Match m=find(victim);
            if(m==null||System.currentTimeMillis()<m.start||!m.winner.isEmpty()||
                    !(who.equals(m.a)&&victim.equals(m.b)||who.equals(m.b)&&victim.equals(m.a)))return;
            if(victim.equals(m.a))m.ba++;else m.bb++;
            boolean sounded=!isBot(victim)&&!mutedBlockSounds.contains(victim);
            if(sounded)blockSound(target);
            Map<String,Object> event=new LinkedHashMap<>();event.put("type","blocked_damage");event.put("time",System.currentTimeMillis());
            event.put("attacker",who);event.put("target",victim);event.put("damage_before_block",before);event.put("damage_after_block",after);
            event.put("health_before",R.call(target,"getHealth"));event.put("armor_points",R.call(target,"getTotalArmorValue"));
            event.put("sound_sent",sounded);
            append(m.replay,event);
        }catch(Throwable e){if(errors++<10)e.printStackTrace();}
    }
    static void blockSound(Object p){
        R.call(R.get(p,"playerNetServerHandler"),"sendPacket",R.construct("net.minecraft.network.play.server.S29PacketSoundEffect",
            "note.pling",R.num(p,"posX"),R.num(p,"posY"),R.num(p,"posZ"),.65f,1.4f));
    }
    static String hearts(Object p){return String.format(Locale.ROOT,"%.1f",((Number)R.call(p,"getHealth")).doubleValue()/2);}
    public static void hit(boolean accepted,Object target,Object source,float damage){
        if(!accepted || server==null || !"server".equals(System.getProperty("lab.role")))return;
        try{
            Object attacker=R.call(source,"getEntity");if(attacker==null)return;
            String victim=R.name(target),who=R.name(attacker); Match m=find(victim);
            if(m==null||System.currentTimeMillis()<m.start||!m.winner.isEmpty())return;
            if(who.equals(m.a)&&victim.equals(m.b))m.ha++;
            else if(who.equals(m.b)&&victim.equals(m.a))m.hb++;else return;
            Map<String,Object> hit=new LinkedHashMap<>();hit.put("type","hit");hit.put("time",System.currentTimeMillis());
            hit.put("attacker",who);hit.put("target",victim);hit.put("damage",damage);hit.put("hits_a",m.ha);hit.put("hits_b",m.hb);
            // This hook runs inside attackEntityFrom's return, before the outer
            // player attack clears its sprint flag. Pure observation only.
            hit.put("attacker_sprint",R.call(attacker,"isSprinting"));hit.put("attacker_state",position(attacker));hit.put("target_state",position(target));
            hit.put("health_after",R.call(target,"getHealth"));hit.put("blocking",R.call(target,"isBlocking"));
            append(m.replay,hit);
            if(m.boxing()){if(m.ha>=m.goal)m.winner=m.a;else if(m.hb>=m.goal)m.winner=m.b;}
            else if(((Number)R.call(target,"getHealth")).floatValue()<=0){m.winner=who;m.finishReason="death";}
        }catch(Throwable e){if(errors++<10)e.printStackTrace();}
    }

    public static boolean command(Object sender,String raw){
        String text=raw.startsWith("/")?raw.substring(1):raw;
        String[] args=text.trim().split("\\s+");if(args.length==0)return false;
        String cmd=args[0].toLowerCase(Locale.ROOT);
        if(cmd.equals("stop")&&!R.cls("net.minecraft.entity.player.EntityPlayerMP").isInstance(sender))return false;
        boolean ours=List.of("bots","duel","mode","blocksound","ping","matches","watch","stop","rematch","lab").contains(cmd)||(cmd.equals("help")&&args.length>1&&args[1].equals("lab"));
        if(!ours)return false;
        try{
            if(server==null){message(sender,"Lab is still starting.");return true;}
            if(cmd.equals("mode")){
                String name=safeName(sender);
                if(args.length==2){
                    if(find(name)!=null)throw new IllegalArgumentException("Use /stop before changing duel mode.");
                    preferredModes.put(name,mode(args[1]));
                }else if(args.length!=1)throw new IllegalArgumentException("Usage: /mode boxing, diamond, or diamond_box");
                message(sender,"Duel mode: "+preferredModes.getOrDefault(name,"boxing")+". Choose a bot with /bots.");
                button(sender,"Boxing: first to 100 hits","/mode boxing");button(sender,"Diamond: full armor, fight to death","/mode diamond");
                button(sender,"Diamond box: 1x1, wood swords, Protection IV","/mode diamond_box");
            }else if(cmd.equals("blocksound")){
                String name=safeName(sender);
                if(args.length==2&&args[1].equals("off"))mutedBlockSounds.add(name);
                else if(args.length==2&&args[1].equals("on"))mutedBlockSounds.remove(name);
                else if(args.length==2&&args[1].equals("test")){blockSound(sender);message(sender,"Test chime. In duels it sounds only when vanilla actually reduces an opponent's damage.");return true;}
                else if(args.length!=1)throw new IllegalArgumentException("Usage: /blocksound on, off, or test");
                message(sender,"Successful-block sound: "+(mutedBlockSounds.contains(name)?"OFF":"ON")+". /blocksound test previews it.");
            }else if(cmd.equals("bots")){
                loadPolicies();message(sender,"Choose a bot with /duel <name>. Mode: "+preferredModes.getOrDefault(R.name(sender),"boxing")+". Optional: /duel <name> [boxing|diamond|diamond_box] [bot RTT] [your RTT].");
                button(sender,"Switch to boxing","/mode boxing");button(sender,"Switch to full diamond","/mode diamond");
                button(sender,"Switch to 1x1 diamond box","/mode diamond_box");
                boolean all=args.length>1&&args[1].equals("all");
                for(var entry:policies.entrySet()){
                    if(!all && (entry.getKey().matches("zest-(tune|model|long|select|pulse)-[0-9]+") || entry.getKey().startsWith("evolved-g")))continue;
                    button(sender,entry.getKey()+" — "+entry.getValue().getProperty("description",entry.getValue().getProperty("kind")),"/duel "+entry.getKey());
                }
                if(!all)button(sender,"Show every search candidate","/bots all");
            }else if(cmd.equals("matches")){
                if(matches.isEmpty())message(sender,"No active matches. /lab match hold-w wtap starts one.");
                for(Match m:matches.values())button(sender,m.id+" ["+m.mode+"]: "+m.pa+" vs "+m.pb+" ("+m.ha+"–"+m.hb+" hits)","/watch "+m.id);
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
                if(!(Boolean)R.call(sender,"isEntityAlive"))throw new IllegalArgumentException("Respawn first, then use /rematch or /duel.");
                String policy=cmd.equals("rematch")?lastDuels.get(name):(args.length>1?args[1]:null);
                if(policy==null){message(sender,"Usage: /duel <bot>. Use /bots for the list.");return true;}
                double[] prior=lastPings.getOrDefault(name,new double[]{0,0});
                int offset=2;String selectedMode=cmd.equals("rematch")?lastModes.getOrDefault(name,"boxing"):preferredModes.getOrDefault(name,"boxing");
                if(cmd.equals("duel")&&args.length>2&&List.of("boxing","diamond","diamond_box").contains(args[2])){selectedMode=mode(args[2]);offset++;}
                if(cmd.equals("duel")&&args.length>offset+2)throw new IllegalArgumentException("Usage: /duel <bot> [boxing|diamond|diamond_box] [bot RTT] [your RTT]");
                double botPing=cmd.equals("rematch")?prior[0]:(args.length>offset?ping(args[offset]):0);
                double humanPing=cmd.equals("rematch")?prior[1]:(args.length>offset+1?ping(args[offset+1]):0);
                String bot=freeBot(Set.of());if(bot==null){message(sender,"All bot slots are busy. Try again shortly.");return true;}
                loadPolicies();start(name,bot,"human",policy,humanPing,botPing,"human-"+System.nanoTime(),0,selectedMode);
                lastDuels.put(name,policy);lastPings.put(name,new double[]{botPing,humanPing});lastModes.put(name,selectedMode);
            }else if(cmd.equals("ping")){
                Match m=find(R.name(sender));
                if(m==null){message(sender,"Start a duel first. /duel <bot> [bot RTT] [your RTT]. In a duel: /ping me 20; /ping bot 100; /ping 20 100.");return true;}
                if(!m.pa.equals("human")){message(sender,"Use /lab ping <match> <RTT-a> <RTT-b> for bot matches.");return true;}
                if(args.length==3 && (args[1].equals("me")||args[1].equals("player")))changePings(m,ping(args[2]),m.pingB);
                else if(args.length==3 && args[1].equals("bot"))changePings(m,m.pingA,ping(args[2]));
                else if(args.length==3)changePings(m,ping(args[1]),ping(args[2]));
                else if(args.length!=1){message(sender,"/ping; /ping me 20; /ping bot 100; /ping 20 100 (your RTT, bot RTT).");return true;}
                lastPings.put(safeName(sender),new double[]{m.pingB,m.pingA});
                message(sender,"Added simulated RTT: you "+m.pingA+" ms; bot "+m.pingB+" ms. Each direction is delayed by half. Your real ping is additional.");
            }else if(cmd.equals("stop")){
                Match m=find(R.name(sender));if(m!=null)finish(m,"stopped");else{vanilla("gamemode 0 "+safeName(sender));teleport(sender,0,4,0);}
            }else if(cmd.equals("lab")&&args.length>=4&&(args[1].equals("diamond")||args[1].equals("diamond_box"))){
                String a=freeBot(Set.of());String b=a==null?null:freeBot(Set.of(a));
                if(a==null||b==null){message(sender,"Need two free bot slots.");return true;}
                loadPolicies();start(a,b,args[2],args[3],args.length>4?ping(args[4]):0,args.length>5?ping(args[5]):0,args.length>6?args[6]:args[1]+"-"+System.nanoTime(),0,args[1]);
            }else if(cmd.equals("lab")&&args.length==8&&args[1].equals("scenario")){
                long duration=Long.parseLong(args[6]);if(duration<1000||duration>180_000)throw new IllegalArgumentException("Scenario duration must be1000–180000ms");
                String a=freeBot(Set.of());String b=a==null?null:freeBot(Set.of(a));
                if(a==null||b==null){message(sender,"Need two free bot slots.");return true;}
                loadPolicies();start(a,b,args[2],args[3],ping(args[4]),ping(args[5]),args[7],duration);
            }else if(cmd.equals("lab")&&args.length>=4&&args[1].equals("match")){
                String a=freeBot(Set.of());String b=a==null?null:freeBot(Set.of(a));
                if(a==null||b==null){message(sender,"Need two free bot slots.");return true;}
                loadPolicies();start(a,b,args[2],args[3],args.length>4?ping(args[4]):0,args.length>5?ping(args[5]):0,args.length>6?args[6]:"manual-"+System.nanoTime());
            }else if(cmd.equals("lab")&&args.length==5&&args[1].equals("ping")){
                Match m=matches.get(args[2]);if(m==null)throw new IllegalArgumentException("Unknown active match");
                changePings(m,ping(args[3]),ping(args[4]));message(sender,"Updated "+m.id+" added RTT to "+m.pingA+"/"+m.pingB+" ms.");
            }else if(cmd.equals("lab")&&args.length>=3&&args[1].equals("goal")){
                message(sender,"Ladder matches always run to 100 accepted hits.");
            }else{
                message(sender,"/bots; /mode boxing|diamond|diamond_box; /duel <bot> [boxing|diamond|diamond_box] [bot RTT] [your RTT]; /blocksound on|off|test; /ping me <RTT>; /ping bot <RTT>; /rematch; /stop; /matches; /watch [match]. All RTTs in milliseconds.");
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
        start(a,b,pa,pb,pingA,pingB,request,0);
    }
    static void start(String a,String b,String pa,String pb,double pingA,double pingB,String request,long duration)throws Exception{
        start(a,b,pa,pb,pingA,pingB,request,duration,"boxing");
    }
    static String mode(String value){if(!List.of("boxing","diamond","diamond_box").contains(value))throw new IllegalArgumentException("Mode must be boxing, diamond, or diamond_box");return value;}
    static void start(String a,String b,String pa,String pb,double pingA,double pingB,String request,long duration,String selectedMode)throws Exception{
        if(a.equals(b)||find(a)!=null||find(b)!=null)throw new IllegalArgumentException("A player cannot be assigned to two matches");
        if(!pa.equals("human")&&!policies.containsKey(pa)||!policies.containsKey(pb))throw new IllegalArgumentException("Unknown bot; use /bots.");
        Match m=new Match();m.mode=mode(selectedMode);m.goal=m.boxing()?100:0;m.request=request;if(duration>0){m.scenario=true;m.durationMs=duration;}
        do{m.id=String.format("m%04d",++counter);}while(Files.exists(DATA.resolve("results/"+m.id+".json"))||Files.exists(DATA.resolve("replays/"+m.id+".jsonl")));
        m.a=a;m.b=b;m.pa=pa;m.pb=pb;m.pingA=m.initialPingA=pingA;m.pingB=m.initialPingB=pingB;m.start=System.currentTimeMillis()+3000;
        if(!pa.equals("human"))m.configA.putAll(policies.get(pa));else m.configA.setProperty("kind","human");
        m.configB.putAll(policies.get(pb));
        Set<Integer> used=new HashSet<>();for(Match current:matches.values())used.add(current.arena);
        while(used.contains(m.arena))m.arena++;
        Object arenaWorld=R.get(player(a),"worldObj");
        m.world=arenaWorld;
        // Warm all normal slots before the first countdown: arena maintenance
        // must not stall another match's movement or network clocks.
        if(preparedArenas.isEmpty())for(int i=0;i<4;i++)prepareArena(arenaWorld,i);
        prepareArena(arenaWorld,m.arena);
        if(m.box())prepareBox(arenaWorld,m.arena);
        m.start=System.currentTimeMillis()+3000;
        m.replay=DATA.resolve("replays/"+m.id+".jsonl");matches.put(m.id,m);
        configureProxy(a,pingA);configureProxy(b,pingB);
        // At normal speed even a full 180-second pursuit cannot reach another arena.
        resetTracking(player(a));resetTracking(player(b));
        seatPlayers(m,player(a),player(b));
        control(a,pa,b,m,pingA,pingB);control(b,pb,a,m,pingB,pingA);
        Map<String,Object> initial=m.state();initial.put("parameters_a",m.configA);initial.put("parameters_b",m.configB);
        initial.put("equipment_a",equipment(player(a)));initial.put("equipment_b",equipment(player(b)));
        initial.put("bot_reaction_ms",Map.of("a",initial.get("reaction_ms_a"),"b",initial.get("reaction_ms_b")));
        Map<String,Object> caps=new LinkedHashMap<>();caps.put("a",initial.get("click_cps_a"));caps.put("b",initial.get("click_cps_b"));
        initial.put("bot_click_cap_cps",caps);
        append(m.replay,Map.of("type","start","match",initial));
        String rules=m.boxing()?"Boxing: first to 100 hits.":(m.box()?"Diamond box: 1x1, unbreakable wooden swords":"Diamond: diamond swords")+", Protection IV diamond armor, fight to death. Respawn, then /rematch.";
        message(player(a),"Starting "+m.id+": "+pa+" vs "+pb+". "+rules+" /stop to exit.");
        message(player(b),"Starting "+m.id+": "+pa+" vs "+pb+". "+rules);
        System.out.println("[PvP Lab] START "+Json.encode(m.state()));
    }
    static void seatPlayers(Match m,Object a,Object b){
        double center=4096.0*(m.arena+1);
        // Two normal 0.6-wide player bodies fit in the same 1x1 cell by
        // overlapping each other, never the walls. Native collision handles KB.
        preparePlayer(a,center+(m.box()?.32:-3),4,m.box()?.5:0,270,m.mode);
        preparePlayer(b,center+(m.box()?.68:3),4,m.box()?.5:0,90,m.mode);
    }
    static void prepareBox(Object world,int arena){
        int center=4096*(arena+1);
        loadBoxChunks(world,center);
        vanilla("fill "+(center-1)+" 4 -1 "+(center+1)+" 6 1 minecraft:bedrock");
        vanilla("fill "+center+" 4 0 "+center+" 5 0 minecraft:air");
        // A transparent, unbreakable ceiling admits daylight and prevents escape.
        vanilla("setblock "+center+" 6 0 minecraft:barrier");
        for(int x=center-1;x<=center+1;x++)for(int y=4;y<=6;y++)for(int z=-1;z<=1;z++){
            String block=x==center&&z==0?(y==6?"barrier":"air"):"bedrock";
            Object pos=R.construct("net.minecraft.util.BlockPos",x,y,z);
            if(R.call(R.call(world,"getBlockState",pos),"getBlock")!=R.get(R.cls("net.minecraft.init.Blocks"),block))
                throw new IllegalStateException("Box verification failed at "+x+","+y+","+z);
        }
    }
    static void loadBoxChunks(Object world,int center){
        // Empty arena chunks can unload between /stop and /rematch. /fill also
        // checks a positive border chunk. Load it before both build and cleanup.
        for(int x=(center-1)>>4;x<=(center+17)>>4;x++)for(int z=-1;z<=1;z++)
            R.call(world,"getChunkFromChunkCoords",x,z);
    }
    static void clearBox(Object world,int arena){
        int center=4096*(arena+1);
        loadBoxChunks(world,center);
        vanilla("fill "+(center-1)+" 4 -1 "+(center+1)+" 6 1 minecraft:air");
        for(int x=center-1;x<=center+1;x++)for(int y=4;y<=6;y++)for(int z=-1;z<=1;z++)
            if(R.call(R.call(world,"getBlockState",R.construct("net.minecraft.util.BlockPos",x,y,z)),"getBlock")!=R.get(R.cls("net.minecraft.init.Blocks"),"air"))
                throw new IllegalStateException("Box cleanup verification failed");
    }
    static void prepareArena(Object world,int arena){
        if(preparedArenas.contains(arena))return;
        int center=4096*(arena+1),radius=128;
        // Ordinary unbreakable full blocks: same .6 slipperiness as grass/dirt.
        // Repair old holes before play, without altering movement or combat.
        // Vanilla /fill checks one extra chunk at the positive X/Z boundary
        // (its loop ends at max+16). Load that border before issuing each fill.
        for(int x=center-radius;x<=center+radius;x+=16)for(int z=-radius;z<=radius;z+=16)
            R.call(world,"getChunkFromChunkCoords",x>>4,z>>4);
        for(int x=center-radius;x<center+radius;x+=128)for(int z=-radius;z<radius;z+=128)
            vanilla("fill "+x+" 3 "+z+" "+(x+127)+" 3 "+(z+127)+" minecraft:bedrock");
        // Old test/player blocks above the floor also invalidate flat-ground
        // comparisons. Each ordinary fill stays within vanilla's 32768 cap.
        for(int x=center-radius;x<center+radius;x+=64)for(int z=-radius;z<radius;z+=64)
            for(int y=4;y<20;y+=8)
                vanilla("fill "+x+" "+y+" "+z+" "+(x+63)+" "+(y+7)+" "+(z+63)+" minecraft:air");
        Object bedrock=R.get(R.cls("net.minecraft.init.Blocks"),"bedrock");
        Object air=R.get(R.cls("net.minecraft.init.Blocks"),"air");
        for(int x=center-radius;x<center+radius;x++)for(int z=-radius;z<radius;z++){
            Object pos=R.construct("net.minecraft.util.BlockPos",x,3,z);
            if(R.call(R.call(world,"getBlockState",pos),"getBlock")!=bedrock)
                throw new IllegalStateException("Arena floor verification failed at "+x+",3,"+z);
            for(int y=4;y<20;y++){
                Object above=R.construct("net.minecraft.util.BlockPos",x,y,z);
                if(R.call(R.call(world,"getBlockState",above),"getBlock")!=air)
                    throw new IllegalStateException("Arena air verification failed at "+x+","+y+","+z);
            }
        }
        System.out.println("[PvP Lab] Verified 65536 bedrock floor blocks and 1048576 air blocks in arena "+arena);
        preparedArenas.add(arena);
    }
    static void preparePlayer(Object p,double x,double y,double z,int yaw,String mode){
        watching.remove(R.name(p));
        String name=safeName(p);vanilla("gamemode 0 "+name);vanilla("clear "+name);vanilla("effect "+name+" clear");
        R.call(p,"setSpectatingEntity",p);
        R.call(p,"clearItemInUse");R.set(R.get(p,"inventory"),"currentItem",0);
        boolean box=mode.equals("diamond_box");
        vanilla("give "+name+" minecraft:"+(box?"wooden_sword":"diamond_sword")+" 1 0"+(!mode.equals("diamond")?" {Unbreakable:1b}":""));
        if(!mode.equals("boxing")){
            String[][] armor={{"head","helmet"},{"chest","chestplate"},{"legs","leggings"},{"feet","boots"}};
            for(String[] piece:armor)vanilla("replaceitem entity "+name+" slot.armor."+piece[0]+" minecraft:diamond_"+piece[1]+" 1 0 {ench:[{id:0s,lvl:4s}]}");
        }
        Map<String,Object> gear=equipment(p);
        if(!gear.get("weapon").equals(box?"wooden_sword":"diamond_sword")||
                !mode.equals("boxing")&&(!gear.get("protection_levels").equals(List.of(4,4,4,4))||!gear.get("armor_points").equals(20)))
            throw new IllegalStateException("Practice kit verification failed: "+gear);
        vanilla("tp "+name+" "+x+" "+y+" "+z+" "+yaw+" 0");
        settleTeleport(p);
        R.call(p,"setHealth",20.0f);R.call(R.call(p,"getFoodStats"),"setFoodLevel",20);
        Object food=R.call(p,"getFoodStats");R.set(food,"foodSaturationLevel",5.0f);R.set(food,"foodExhaustionLevel",0.0f);R.set(food,"foodTimer",0);
        R.set(p,"motionX",0.0);R.set(p,"motionY",0.0);R.set(p,"motionZ",0.0);
        R.call(R.get(p,"playerNetServerHandler"),"sendPacket",R.construct("net.minecraft.network.play.server.S12PacketEntityVelocity",p));
    }
    static Map<String,Object> equipment(Object p){
        List<Integer> protection=new ArrayList<>();
        for(Object stack:(Object[])R.get(R.get(p,"inventory"),"armorInventory"))
            protection.add(stack==null?0:(int)R.call(R.cls("net.minecraft.enchantment.EnchantmentHelper"),"getEnchantmentLevel",0,stack));
        Object held=R.call(p,"getHeldItem"),item=held==null?null:R.call(held,"getItem");
        String weapon=item==R.get(R.cls("net.minecraft.init.Items"),"wooden_sword")?"wooden_sword":
            item==R.get(R.cls("net.minecraft.init.Items"),"diamond_sword")?"diamond_sword":"other";
        Object tag=held==null?null:R.call(held,"getTagCompound");
        return Map.of("weapon",weapon,"weapon_unbreakable",tag!=null&&(Boolean)R.call(tag,"getBoolean","Unbreakable"),
            "armor_points",R.call(p,"getTotalArmorValue"),"protection_levels",protection);
    }
    static void control(String who,String policy,String opponent,Match m,double ping,double oppPing){
        if(!isBot(who))return;
        Properties p=new Properties();p.putAll(policies.get(policy));p.setProperty("enabled","true");p.setProperty("opponent",opponent);
        p.setProperty("match",m.id);p.setProperty("start_at",Long.toString(m.start));p.setProperty("ping",Double.toString(ping));p.setProperty("opponent_ping",Double.toString(oppPing));
        R.writeProperties(DATA.resolve("bots/"+who.substring(7)+"/control.properties"),p);
    }
    static void disable(String who){if(isBot(who)){Properties p=new Properties();p.setProperty("enabled","false");R.writeProperties(DATA.resolve("bots/"+who.substring(7)+"/control.properties"),p);}}
    static void configureProxy(String who,double ping){Properties p=new Properties();p.setProperty("rtt_ms",Double.toString(ping));R.writeProperties(DATA.resolve("proxy/"+(isBot(who)?"slot"+who.substring(7):who.equals("LabObserver")?"observer":"human")+".properties"),p);}
    static void changePings(Match m,double a,double b){
        m.pingA=a;m.pingB=b;m.pingChanges++;configureProxy(m.a,a);configureProxy(m.b,b);
        updateBotPing(m.a,a,b);updateBotPing(m.b,b,a);
        append(m.replay,Map.of("type","ping_change","time",System.currentTimeMillis(),"ping_a",a,"ping_b",b));
    }
    static void updateBotPing(String name,double own,double other){
        if(!isBot(name))return;
        Path path=DATA.resolve("bots/"+name.substring(7)+"/control.properties");Properties p=R.properties(path);
        p.setProperty("ping",Double.toString(own));p.setProperty("opponent_ping",Double.toString(other));R.writeProperties(path,p);
    }
    static void finish(Match m,String reason){
        m.finish=System.currentTimeMillis();Map<String,Object> result=m.state();result.put("reason",reason);
        for(String side:List.of("a","b")){Object p=player(side.equals("a")?m.a:m.b);result.put("health_"+side,p==null?null:R.call(p,"getHealth"));}
        result.put("initial_ping_a",m.initialPingA);result.put("initial_ping_b",m.initialPingB);result.put("ping_changes",m.pingChanges);
        result.put("parameters_a",m.configA);result.put("parameters_b",m.configB);
        result.put("duration_seconds",Math.max(0,(m.finish-m.start)/1000.0));
        writeJson(DATA.resolve("results/"+m.id+".json"),result);append(m.replay,Map.of("type","finish","result",result));
        matches.remove(m.id);
        for(String who:List.of(m.a,m.b)){
            disable(who);Object p=player(who);if(p==null)continue;
            message(p,"Finished "+m.id+" ["+m.mode+"]: "+m.ha+"–"+m.hb+" hits; "+(m.winner.isEmpty()?reason:"winner "+m.winner)+". "+((Boolean)R.call(p,"isEntityAlive")?"":"Respawn, then ")+"/rematch or /bots.");
            if(reason.equals("invalid_client_view"))message(p,"Stopped: a bot's native opponent view was invalid. This game is not a valid result. /rematch starts fresh.");
            if((Boolean)R.call(p,"isEntityAlive"))returnToLobby(p);else pendingLobby.add(who);
        }
        if(m.box())clearBox(m.world,m.arena);
        System.out.println("[PvP Lab] RESULT "+Json.encode(result));
    }
    static void returnToLobby(Object p){
        String who=safeName(p);R.call(p,"clearItemInUse");R.call(p,"setHealth",20.0f);
        R.call(R.call(p,"getFoodStats"),"setFoodLevel",20);R.set(R.call(p,"getFoodStats"),"foodSaturationLevel",5.0f);
        if(isBot(who)){vanilla("gamemode 3 "+who);teleport(p,-40-Integer.parseInt(who.substring(7))*6,4,0);}else{vanilla("gamemode 0 "+who);teleport(p,0,4,0);}
    }
    static Match find(String name){for(Match m:matches.values())if(m.a.equals(name)||m.b.equals(name))return m;return null;}
    static boolean validBotViews(Match m){
        for(String name:List.of(m.a,m.b))if(isBot(name)){
            Properties p=R.properties(DATA.resolve("bots/"+name.substring(7)+"/view.properties"));
            if(!m.id.equals(p.getProperty("match"))||System.currentTimeMillis()-Long.parseLong(p.getProperty("time","0"))>3000||!Boolean.parseBoolean(p.getProperty("target_ready","false")))return false;
        }
        return true;
    }
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
    static void resetTracking(Object p){
        // Round setup only. A destroy and same-ID respawn in one client tick can
        // leave the replacement out of vanilla's ticking list after chunk unload.
        // Use ordinary server tracking, separated by time, before combat begins.
        Object tracker=R.call(R.get(p,"worldObj"),"getEntityTracker");
        R.call(tracker,"untrackEntity",p);
        pendingTracking.put(R.name(p),System.currentTimeMillis()+500);
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
