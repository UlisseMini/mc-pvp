package lab;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real server referee. Vanilla by default; explicit match options may freeze a
 * different, recorded CombatProfile for both players. */
public final class Referee {
    static Object server;
    static final Path DATA=R.ROOT.resolve("runtime");
    static final Map<String,Match> matches=new LinkedHashMap<>();
    static final Map<String,Properties> policies=new LinkedHashMap<>();
    static final Set<String> greeted=new HashSet<>();
    static final Map<String,String> lastDuels=new HashMap<>();
    static final Map<String,double[]> lastPings=new HashMap<>();
    static final Map<String,Properties> lastOverrides=new HashMap<>();
    static final Map<String,String> preferredModes=new HashMap<>(),lastModes=new HashMap<>();
    static final Set<String> mutedBlockSounds=new HashSet<>(),pendingLobby=new HashSet<>();
    static final Set<String> mutedBlockTiming=new HashSet<>();
    static final Map<String,String> watching=new HashMap<>();
    static final Map<String,Long> pendingTracking=new LinkedHashMap<>();
    static final Set<Integer> preparedArenas=new HashSet<>();
    static final Set<Integer> preparedLongArenas=new HashSet<>();
    static final List<String> MODES=List.of("boxing","diamond","diamond_box","rod_diamond","sumo");
    static long ticks, errors;
    static int counter;

    static final class Match {
        String id,a,b,pa,pb,winner="",request="",mode="boxing",finishReason="100_hits"; int ha,hb,ba,bb,goal=100,arena,pingChanges; long start,finish,durationMs=180_000; boolean scenario,setupReseated; double pingA,pingB,initialPingA,initialPingB;
        boolean boxing(){return mode.equals("boxing");}
        CombatProfile combat=CombatProfile.VANILLA;
        SeededStartServer.Session seed;
        boolean sumo(){return mode.equals("sumo");}
        boolean box(){return mode.equals("diamond_box");}
        boolean rods(){return mode.equals("rod_diamond");}
        boolean jumpReset(){return EntryDrills.JUMP_RESET.equals(drillPreset);}
        JumpResetFeedback jumpFeedback;
        MmcKnockback mmcKnockback;
        final CombatStats sa=new CombatStats(),sb=new CombatStats();
        CombatStats stats(String name){return name.equals(a)?sa:name.equals(b)?sb:null;}
        String label(String side){return side.equals("a")?MatchLabels.label(a,pa,pb,"a"):MatchLabels.label(b,pb,pa,"b");}
        final Properties configA=new Properties(),configB=new Properties();
        Object world;
        Path replay;
        double startGap=6;int drillRound,drillCountdown=-1;String drillPreset="";long drillFirstHit;
        Map<String,Object> state(){
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",id);m.put("a",a);m.put("b",b);m.put("policy_a",pa);m.put("policy_b",pb);
            m.put("client_a",clientRuntime(a));m.put("client_b",clientRuntime(b));
            m.put("display_a",label("a"));m.put("display_b",label("b"));
            m.put("hits_a",ha);m.put("hits_b",hb);m.put("goal",goal);m.put("ping_a",pingA);m.put("ping_b",pingB);
            m.put("damage_hits_a",sa.breakdown.damageHitsLanded);m.put("damage_hits_b",sb.breakdown.damageHitsLanded);
            m.put("mode",mode);m.put("blocks_a",ba);m.put("blocks_b",bb);
            m.put("combat_profile",combat.state());
            if(seed!=null)m.put("seed",seed.state());
            String practicePreset=configB.getProperty(PracticePresets.LABEL,"");
            if(!practicePreset.isEmpty())m.put("practice_preset",practicePreset);
            long now=finish>0?finish:System.currentTimeMillis(),duration=Math.max(0,now-start);
            m.put("stats_a",sa.state(hb-sb.rodHits,now,duration));m.put("stats_b",sb.state(ha-sa.rodHits,now,duration));
            m.put("food_policy","full_food_saturation_every_20_ticks");m.put("fishing_rods",rods());
            m.put("rod_knockback",rods()?"native_zero_damage_contacts_between_duel_opponents":"vanilla_zero_damage_rejection");
            if(drillRound>0)m.put("drill",Map.of("round",drillRound,"preset",drillPreset,"gap",startGap,"first_hit_ms",drillFirstHit));
            m.put("start",start);m.put("finish",finish);m.put("winner",winner);m.put("arena",arena);m.put("request",request);m.put("scenario",scenario);m.put("duration_limit_ms",durationMs);
            m.put("reaction_ms_a",(int)Double.parseDouble(configA.getProperty("reaction_ms","150")));
            m.put("reaction_ms_b",(int)Double.parseDouble(configB.getProperty("reaction_ms","150")));
            m.put("click_cps_a",pa.equals("human")?null:ClickRate.cps(configA));
            m.put("click_cps_b",pb.equals("human")?null:ClickRate.cps(configB));
            m.put("attack_reach_a",DuelSettings.reach(configA));m.put("attack_reach_b",DuelSettings.reach(configB));
            m.put("modified_reach",DuelSettings.reach(configA)!=3||DuelSettings.reach(configB)!=3);
            if(sumo())m.put("sumo_arena",SumoArena.geometry());
            m.put("armor_protection",0);m.put("weapon",sumo()?"fist":boxing()?"diamond_sword":"wooden_sword");
            m.put("arena_surface",sumo()?"raised_circular_sumo_platform":box()?"bedrock_1x1_interior_y4_5_barrier_ceiling_y6":preparedLongArenas.contains(arena)?"bedrock_y3_air_y4_19_half_x1024_z512_verified_v5":"bedrock_y3_air_y4_19_radius256_verified_v4");return m;
        }
    }

    static Map<String,Object> clientRuntime(String name){
        Map<String,Object> out=new LinkedHashMap<>();out.put("role",isBot(name)?"bot":"human");
        if(isBot(name)){Properties p=R.properties(DATA.resolve("bots/"+name.substring(7)+"/runtime.properties"));
            for(String key:List.of("backend","slot","minecraft","forge","bridge_sha256","module_sha256","headless_sha256","java_sha256","agent_sha256"))
                if(p.containsKey(key))out.put(key,p.getProperty(key));}
        return out;
    }

    public static void tick(Object instance) {
        if(!"server".equals(System.getProperty("lab.role")))return;
        server=instance;ticks++;
        try {
            if(ticks==1)for(Object world:(Object[])R.get(server,"worldServers"))if(world!=null){
                Object rules=R.call(world,"getGameRules");
                R.call(rules,"setOrCreateGameRule","sendCommandFeedback","false");
                R.call(rules,"setOrCreateGameRule","commandBlockOutput","false");
            }
            if(policies.isEmpty())loadPolicies();
            List<?> people=players();
            greeted.retainAll(people.stream().map(R::name).toList());
            if(ticks%20==0)for(Object p:people)fullFood(p);
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
                    fullFood(p);
                    if(isBot(name)){botLabel(p,"");vanilla("gamemode 3 "+name);teleport(p,-40-Integer.parseInt(name.substring(7))*6,4,0);disable(name);}
                    else {double ping=preferredPing(name);rememberPing(name,ping);configureProxy(name,ping);
                        teleport(p,0,4,0);message(p,"PvP Lab: /bots; /mode diamond or rod_diamond for health duels; /mode diamond_box for 1x1 blockhitting; /mode boxing for 100 hits; /mode sumo for ring-outs. Successful blocks play a chime (/blocksound). /help lab for controls.");
                        message(p,"§bYour saved added RTT: "+(int)ping+" ms. §7Change it anytime with §b/ping me <ms>§7.");}
                }
            }
            for(Match m:new ArrayList<>(matches.values())){
                SeededStartServer.tick(m);if(!matches.containsKey(m.id))continue;
                Object a=player(m.a),b=player(m.b);
                if(a==null||b==null){m.winner=a==null?m.b:m.a;finish(m,"disconnect");continue;}
                long setupNow=System.currentTimeMillis();
                if((m.drillRound>0||m.sumo())&&setupNow<m.start)EntryDrills.holdStart(m,a,b);
                EntryDrills.countdown(m,setupNow);
                if(setupNow>=m.start){CombatStats.usingItemChanged(a);CombatStats.usingItemChanged(b);}
                if(!m.setupReseated&&setupNow<m.start&&setupNow>=m.start-(m.drillRound>0?500:1500)){
                    // Initial destination chunks may arrive after the teleport,
                    // allowing a client to fall into the floor during countdown.
                    // Re-seat once after terrain delivery, strictly before combat.
                    seatPlayers(m,a,b);
                    m.setupReseated=true;
                    append(m.replay,Map.of("type","setup_reseat","time",setupNow,"combat_start",m.start));
                }
                if(m.sumo()&&setupNow>=m.start){
                    int outcome=SumoArena.outcome(R.num(a,"posY"),R.num(b,"posY"));
                    if(outcome>=0){m.winner=outcome==0?"":outcome==1?m.a:m.b;finish(m,outcome==0?"double_ring_out":"ring_out");continue;}
                }
                if(!m.sumo()&&System.currentTimeMillis()>m.start&&(R.num(a,"posY")<4-1e-7||R.num(b,"posY")<4-1e-7)){
                    finish(m,"invalid_arena_floor");continue;
                }
                if(m.boxing()){R.call(R.call(a,"getFoodStats"),"setFoodLevel",20);R.call(R.call(b,"getFoodStats"),"setFoodLevel",20);}
                if(!m.boxing()&&!m.sumo()&&(((Number)R.call(a,"getHealth")).floatValue()<=0||((Number)R.call(b,"getHealth")).floatValue()<=0)){
                    boolean aliveA=(Boolean)R.call(a,"isEntityAlive"),aliveB=(Boolean)R.call(b,"isEntityAlive");
                    m.winner=aliveA==aliveB?"":aliveA?m.a:m.b;finish(m,"death");continue;
                }
                if(!m.winner.isEmpty()){finish(m,m.finishReason);continue;}
                if(m.drillRound>0&&!m.jumpReset()&&m.drillFirstHit>0&&setupNow>=m.drillFirstHit+5000){finish(m,"drill_completed");continue;}
                if(ticks%10==0&&System.currentTimeMillis()>m.start+2000&&!validBotViews(m)){
                    finish(m,"invalid_client_view");continue;
                }
                if(m.durationMs>0&&System.currentTimeMillis()>m.start+m.durationMs){finish(m,m.drillRound>0?"drill_completed":m.scenario?"scenario_completed":"timeout_draw");continue;}
                Map<String,Object> trace=new LinkedHashMap<>();trace.put("type","tick");trace.put("time",System.currentTimeMillis());
                trace.put("a",replayPosition(a));trace.put("b",replayPosition(b));trace.put("hits_a",m.ha);trace.put("hits_b",m.hb);
                trace.put("health_a",R.call(a,"getHealth"));trace.put("health_b",R.call(b,"getHealth"));
                append(m.replay,trace);
                if(ticks%10==0){
                    long left=m.start-System.currentTimeMillis();
                    String score=left>0?"Starting "+m.mode+" in "+((left+999)/1000)+"...":
                        (m.sumo()?"Sumo: "+m.label("a")+" "+m.ha+"–"+m.hb+" "+m.label("b"):m.boxing()?m.label("a")+" "+m.ha+(m.scenario?" hits  |  ":" / 100  |  ")+m.hb+" "+m.label("b"):
                        m.label("a")+" "+hearts(a)+" hearts  |  "+hearts(b)+" "+m.label("b"));
                    String ba=m.sa.blockRatioText(m.hb-m.sb.rodHits),bb=m.sb.blockRatioText(m.ha-m.sa.rodHits);
                    String ping=" §8| §7RTT "+(int)m.pingA+"/"+(int)m.pingB+" ms";
                    String humanBar=m.jumpFeedback!=null&&left<=0?m.jumpFeedback.text(System.currentTimeMillis()):
                        timedBar(m,m.a,score+(m.sumo()?"":" §8| "+m.sa.blockBarText(m.hb-m.sb.rodHits))+ping,System.currentTimeMillis());
                    overlay(a,humanBar);
                    overlay(b,timedBar(m,m.b,score+(m.sumo()?"":" §8| "+m.sb.blockBarText(m.ha-m.sa.rodHits))+ping,System.currentTimeMillis()));
                    for(var watcher:watching.entrySet())if(watcher.getValue().equals(m.id)&&player(watcher.getKey())!=null)
                        overlay(player(watcher.getKey()),score+(m.sumo()?"":" §8| §bBlocks: §f"+ba+" dmg "+m.sa.damageBlockedText()+" §7vs §f"+bb+" dmg "+m.sb.damageBlockedText())+ping);
                }
            }
            ServerReplay.tick();
            PovLink.serverTick();
            EntryDrills.tick();
            if(ticks%10==0){
                Map<String,Object> status=new LinkedHashMap<>();status.put("version","1.8.9");status.put("time",System.currentTimeMillis());
                status.put("players",people.stream().map(R::name).toList());status.put("policies",new ArrayList<>(policies.keySet()));
                status.put("matches",matches.values().stream().map(Match::state).toList());
                status.put("next_scenario_combat",CombatTuning.nextScenario.state());
                status.put("replays",ServerReplay.state());
                List<Map<String,Object>> details=new ArrayList<>();
                for(Object p:people){Map<String,Object> detail=position(p);detail.put("name",R.name(p));detail.put("health",R.call(p,"getHealth"));detail.put("alive",R.call(p,"isEntityAlive"));
                    detail.put("armor_points",R.call(p,"getTotalArmorValue"));detail.put("food",R.call(R.call(p,"getFoodStats"),"getFoodLevel"));
                    detail.put("saturation",R.num(R.call(p,"getFoodStats"),"foodSaturationLevel"));
                    detail.put("blocking",R.call(p,"isBlocking"));details.add(detail);}
                status.put("player_details",details);
                writeJson(DATA.resolve("status.json"),status);
            }
        }catch(Throwable e){if(errors++<10)e.printStackTrace();}
    }

    public static void restoreHealth(Object target){
        if(server==null || !"server".equals(System.getProperty("lab.role")))return;
        try{Match m=find(R.name(target));if(m!=null&&(m.boxing()||m.sumo()||System.currentTimeMillis()<m.start))R.call(target,"setHealth",20.0f);}catch(Throwable e){if(errors++<10)e.printStackTrace();}
    }
    /** Narrow, server-only exception to vanilla's player zero-damage rejection. */
    public static boolean allowRodContact(Object target,Object source){
        if(server==null||!"server".equals(System.getProperty("lab.role")))return false;
        try{
            Object direct=R.call(source,"getSourceOfDamage");
            if(direct==null||!R.cls("net.minecraft.entity.projectile.EntityFishHook").isInstance(direct))return false;
            Object attacker=R.call(source,"getEntity");if(attacker==null)return false;
            String victim=R.name(target),who=R.name(attacker);Match m=find(victim);
            return m!=null&&m.rods()&&m.finish==0&&System.currentTimeMillis()>=m.start&&m.winner.isEmpty()&&
                (who.equals(m.a)&&victim.equals(m.b)||who.equals(m.b)&&victim.equals(m.a));
        }catch(Throwable e){if(errors++<10)e.printStackTrace();return false;}
    }
    /** Called only when vanilla executed its (damage+1)/2 sword-block branch. */
    public static void blockedDamage(Object target,Object source,float before,float after){
        if("server".equals(System.getProperty("lab.role")))DamageBreakdown.blockApplied(target,source);
        if(server==null||!"server".equals(System.getProperty("lab.role"))||!(after<before))return;
        try{
            Object attacker=R.call(source,"getEntity");if(attacker==null)return;
            String victim=R.name(target),who=R.name(attacker);Match m=find(victim);
            if(m==null||System.currentTimeMillis()<m.start||!m.winner.isEmpty()||
                    !(who.equals(m.a)&&victim.equals(m.b)||who.equals(m.b)&&victim.equals(m.a)))return;
            if(victim.equals(m.a))m.ba++;else m.bb++;
            m.stats(victim).blockAccuracy.damageReduced();
            DamageBreakdown.blocked(target,source);
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
            "random.anvil_land",R.num(p,"posX"),R.num(p,"posY"),R.num(p,"posZ"),.25f,1.6f));
    }
    static String timedBar(Match m,String who,String normal,long now){
        if(mutedBlockTiming.contains(who))return normal;
        CombatStats stats=m.stats(who);String feedback=stats.blockTiming.text(now);
        if(feedback.isEmpty())return normal;
        int incoming=who.equals(m.a)?m.hb-m.sb.rodHits:m.ha-m.sa.rodHits;
        Object p=player(who);
        return feedback+" §8| "+stats.blockBarText(incoming)+(p==null?"":" §8| §cHP: "+hearts(p));
    }
    static void timingFeedback(Match m,String who,long now){
        append(m.replay,Map.of("type","block_timing","time",now,"player",who,"feedback",m.stats(who).blockTiming.state()));
        Object p=player(who);
        if(p!=null&&!isBot(who)&&!mutedBlockTiming.contains(who))overlay(p,timedBar(m,who,"",now));
    }
    static String hearts(Object p){return String.format(Locale.ROOT,"%.1f",((Number)R.call(p,"getHealth")).doubleValue()/2);}
    public static void hit(boolean accepted,Object target,Object source,float damage,boolean immuneBefore){
        if(!accepted || server==null || !"server".equals(System.getProperty("lab.role")))return;
        try{
            Object attacker=R.call(source,"getEntity");if(attacker==null)return;
            String victim=R.name(target),who=R.name(attacker); Match m=find(victim);
            if(m==null||System.currentTimeMillis()<m.start||!m.winner.isEmpty())return;
            if(who.equals(m.a)&&victim.equals(m.b))m.ha++;
            else if(who.equals(m.b)&&victim.equals(m.a))m.hb++;else return;
            Object direct=R.call(source,"getSourceOfDamage");
            boolean rod=direct!=null&&R.cls("net.minecraft.entity.projectile.EntityFishHook").isInstance(direct);
            if(!rod&&m.drillRound>0&&m.drillFirstHit==0&&(!m.jumpReset()||victim.equals(m.a)&&!immuneBefore))
                m.drillFirstHit=System.currentTimeMillis();
            CombatStats stats=m.stats(who);if(rod)stats.rodHits++;if(immuneBefore)stats.upgrades++;
            if(rod)stats.rodTricks.rod(m.ha+m.hb);
            if(!rod)DamageBreakdown.accepted(target,source,m.ha+m.hb);
            Map<String,Object> hit=new LinkedHashMap<>();hit.put("type","hit");hit.put("time",System.currentTimeMillis());
            hit.put("attacker",who);hit.put("target",victim);hit.put("damage",damage);hit.put("hits_a",m.ha);hit.put("hits_b",m.hb);
            // This hook runs inside attackEntityFrom's return, before the outer
            // player attack clears its sprint flag. Pure observation only.
            hit.put("attacker_sprint",R.call(attacker,"isSprinting"));hit.put("attacker_state",position(attacker));hit.put("target_state",position(target));
            hit.put("health_after",R.call(target,"getHealth"));hit.put("blocking",R.call(target,"isBlocking"));
            hit.put("rod",rod);hit.put("damage_upgrade",immuneBefore);
            hit.put("hit_index",m.ha+m.hb);hit.put("server_tick",ticks);
            append(m.replay,hit);
            if(m.boxing()){if(!m.scenario){if(m.ha>=m.goal)m.winner=m.a;else if(m.hb>=m.goal)m.winner=m.b;}}
            else if(!m.sumo()&&((Number)R.call(target,"getHealth")).floatValue()<=0){m.winner=who;m.finishReason="death";}
        }catch(Throwable e){if(errors++<10)e.printStackTrace();}
    }

    public static boolean command(Object sender,String raw){
        String text=raw.startsWith("/")?raw.substring(1):raw;
        String[] args=text.trim().split("\\s+");if(args.length==0)return false;
        String cmd=args[0].toLowerCase(Locale.ROOT);
        if(cmd.equals("stop")&&!R.cls("net.minecraft.entity.player.EntityPlayerMP").isInstance(sender))return false;
        boolean ours=List.of("bots","duel","drill","mode","blocksound","blocktiming","ping","matches","watch","replay","stop","rematch","lab","help","?").contains(cmd);
        if(!ours)return false;
        try{
            if(server==null){message(sender,"Lab is still starting.");return true;}
            if(List.of("duel","rematch","watch","replay").contains(cmd)&&find(R.name(sender))==null)EntryDrills.cancel(R.name(sender));
            if(cmd.equals("help")||cmd.equals("?")){
                LabHelp.show(sender,args.length>1?args[1].toLowerCase(Locale.ROOT):"1");
            }else if(cmd.equals("drill")){
                EntryDrills.command(sender,args);
            }else if(cmd.equals("replay")){
                ServerReplay.command(sender,args);
            }else if(cmd.equals("mode")){
                String name=safeName(sender);
                if(args.length==2){
                    if(find(name)!=null)throw new IllegalArgumentException("Use /stop before changing duel mode.");
                    preferredModes.put(name,mode(args[1]));
                }else if(args.length!=1)throw new IllegalArgumentException("Usage: /mode boxing, diamond, diamond_box, rod_diamond, or sumo");
                message(sender,"Duel mode: "+preferredModes.getOrDefault(name,"boxing")+". Choose a bot with /bots.");
                button(sender,"Boxing: first to 100 hits","/mode boxing");button(sender,"Diamond: full armor, fight to death","/mode diamond");
                button(sender,"Diamond box: 1x1, wood swords, unenchanted armor","/mode diamond_box");
                button(sender,"Rod diamond: wood swords and fishing rods","/mode rod_diamond");
                button(sender,"Sumo: fists, knock your opponent off","/mode sumo");
            }else if(cmd.equals("blocksound")){
                String name=safeName(sender);
                if(args.length==2&&args[1].equals("off"))mutedBlockSounds.add(name);
                else if(args.length==2&&args[1].equals("on"))mutedBlockSounds.remove(name);
                else if(args.length==2&&args[1].equals("test")){blockSound(sender);message(sender,"Test block clang. In duels it sounds only when vanilla actually reduces an opponent's damage.");return true;}
                else if(args.length!=1)throw new IllegalArgumentException("Usage: /blocksound on, off, or test");
                message(sender,"Successful-block sound: "+(mutedBlockSounds.contains(name)?"OFF":"ON")+". /blocksound test previews it.");
            }else if(cmd.equals("blocktiming")){
                String name=safeName(sender);
                if(args.length==2&&args[1].equals("off"))mutedBlockTiming.add(name);
                else if(args.length==2&&args[1].equals("on"))mutedBlockTiming.remove(name);
                else if(args.length!=1)throw new IllegalArgumentException("Usage: /blocktiming on or off");
                message(sender,"Block timing feedback: "+(mutedBlockTiming.contains(name)?"OFF":"ON")+". Uses server timing; early/late only within 250 ms of a hit.");
            }else if(cmd.equals("bots")){
                loadPolicies();message(sender,"Choose a bot with /duel <name>. Mode: "+preferredModes.getOrDefault(R.name(sender),"boxing")+". Options: reach=3.5 cps=15 ping=100. Your ping is saved; /ping me <ms>.");
                button(sender,"Switch to boxing","/mode boxing");button(sender,"Switch to full diamond","/mode diamond");
                button(sender,"Switch to 1x1 diamond box","/mode diamond_box");
                button(sender,"Switch to rod diamond","/mode rod_diamond");
                button(sender,"Switch to sumo","/mode sumo");
                boolean all=args.length>1&&args[1].equals("all");
                for(var entry:policies.entrySet()){
                    if(!all && (entry.getKey().matches("zest-(tune|model|long|select|pulse)-[0-9]+") || entry.getKey().startsWith("evolved-g")))continue;
                    button(sender,entry.getKey()+" — "+entry.getValue().getProperty("description",entry.getValue().getProperty("kind")),"/duel "+entry.getKey()+(entry.getKey().startsWith("lock8")?" preset=lock8":""));
                }
                if(!all)button(sender,"Show every search candidate","/bots all");
            }else if(cmd.equals("matches")){
                if(matches.isEmpty())message(sender,"No active matches. /lab match hold-w wtap starts one.");
                for(Match m:matches.values())button(sender,m.id+" ["+m.mode+"]: "+m.label("a")+" vs "+m.label("b")+" ("+m.ha+"–"+m.hb+" hits)","/watch "+m.id);
            }else if(cmd.equals("watch")){
                Match m=args.length>1?matches.get(args[1]):matches.values().stream().findFirst().orElse(null);
                if(m==null){message(sender,"No such active match. Use /matches.");return true;}
                if(find(R.name(sender))!=null){message(sender,"Use /stop before spectating.");return true;}
                ServerReplay.close(safeName(sender),false);
                vanilla("gamemode 3 "+safeName(sender));
                R.call(sender,"setSpectatingEntity",sender);
                Object a=player(m.a),b=player(m.b);
                teleport(sender,(R.num(a,"posX")+R.num(b,"posX"))/2,m.sumo()?SumoArena.TOP_Y+6:10,(R.num(a,"posZ")+R.num(b,"posZ"))/2+5);
                watching.put(safeName(sender),m.id);
                if(args.length>2)R.call(sender,"setSpectatingEntity",args[2].equals("b")?b:a);
                message(sender,"Spectating "+m.id+". Fly freely; click below to follow a bot. Sneak exits its camera.");
                button(sender,"Follow "+m.label("a"),"/watch "+m.id+" a");button(sender,"Follow "+m.label("b"),"/watch "+m.id+" b");
            }else if(cmd.equals("duel")||cmd.equals("rematch")){
                String name=safeName(sender);if(player(name)==null){message(sender,"Use /lab match <bot-a> <bot-b> from the console.");return true;}
                if(find(name)!=null){message(sender,"You are already in a duel. /stop ends it.");return true;}
                if(!(Boolean)R.call(sender,"isEntityAlive"))throw new IllegalArgumentException("Respawn first, then use /rematch or /duel.");
                String policy=cmd.equals("rematch")?lastDuels.get(name):(args.length>1?args[1]:null);
                if(policy==null){message(sender,"Usage: /duel <bot>. Use /bots for the list.");return true;}
                boolean rematch=cmd.equals("rematch");double[] prior=lastPings.getOrDefault(name,new double[]{0,0});
                String selectedMode=rematch?lastModes.getOrDefault(name,"boxing"):preferredModes.getOrDefault(name,"boxing");
                boolean presetRematch=rematch&&lastOverrides.getOrDefault(name,new Properties()).containsKey(PracticePresets.LABEL);
                DuelSettings setup=DuelSettings.parse(args,rematch?1:2,selectedMode,rematch?prior[0]:0,presetRematch?prior[1]:preferredPing(name),
                    rematch?lastOverrides.getOrDefault(name,new Properties()):new Properties());
                String bot=freeBot(Set.of());if(bot==null){message(sender,"All bot slots are busy. Try again shortly.");return true;}
                loadPolicies();ServerReplay.close(name,false);watching.remove(name);
                start(name,bot,"human",policy,setup.humanPing,setup.botPing,"human-"+System.nanoTime(),0,setup.mode,setup.overrides);
                if(setup.humanPingExplicit)rememberPing(name,setup.humanPing);
                lastDuels.put(name,policy);lastPings.put(name,new double[]{setup.botPing,setup.humanPing});
                lastModes.put(name,setup.mode);lastOverrides.put(name,setup.overrides);
            }else if(cmd.equals("ping")){
                String name=safeName(sender);Match m=find(name);
                if(m==null){
                    if(args.length==3&&(args[1].equals("me")||args[1].equals("player"))){
                        double ping=ping(args[2]);rememberPing(name,ping);configureProxy(name,ping);
                    }else if(args.length!=1)throw new IllegalArgumentException("In the lobby, use /ping me <ms>. Set a bot's ping in /duel <bot> ping=<ms>.");
                    message(sender,"§bYour saved added RTT: "+preferredPing(name)+" ms. §7Applies to future matches. /ping me <ms> changes it; /ping me 0 resets it.");return true;
                }
                if(!m.pa.equals("human")){message(sender,"Use /lab ping <match> <RTT-a> <RTT-b> for bot matches.");return true;}
                if(args.length==3 && (args[1].equals("me")||args[1].equals("player")))changePings(m,ping(args[2]),m.pingB);
                else if(args.length==3 && args[1].equals("bot"))changePings(m,m.pingA,ping(args[2]));
                else if(args.length==3)changePings(m,ping(args[1]),ping(args[2]));
                else if(args.length!=1){message(sender,"/ping; /ping me 20; /ping bot 100; /ping 20 100 (your RTT, bot RTT).");return true;}
                lastPings.put(safeName(sender),new double[]{m.pingB,m.pingA});
                message(sender,"Added simulated RTT: you "+m.pingA+" ms; bot "+m.pingB+" ms. Each direction is delayed by half. Your real ping is additional.");
            }else if(cmd.equals("stop")){
                EntryDrills.cancel(R.name(sender));
                if(ServerReplay.close(safeName(sender),true))return true;
                watching.remove(safeName(sender));R.call(sender,"setSpectatingEntity",sender);
                Match m=find(R.name(sender));if(m!=null)finish(m,"stopped");else{vanilla("gamemode 0 "+safeName(sender));teleport(sender,0,4,0);}
            }else if(cmd.equals("lab")&&args.length>=2&&args[1].equals("combat")){
                CombatTuning.command(sender,args);
            }else if(cmd.equals("lab")&&args.length==3&&args[1].equals("prepare")&&args[2].equals("long")){
                if(!matches.isEmpty())throw new IllegalArgumentException("Arena preparation requires no active matches.");
                String bot=freeBot(Set.of());if(bot==null)throw new IllegalArgumentException("Wait for a bot to connect first.");
                Object world=R.get(player(bot),"worldObj");
                for(int i=0;i<4;i++)prepareArena(world,i);
                prepareLongArena(world,0);
                message(sender,"Long arena0 ready: verified flat bedrock, half-length1024, half-width512.");
            }else if(cmd.equals("lab")&&args.length>=2&&args[1].equals("demo")){
                if(args.length!=3)throw new IllegalArgumentException("Usage: /lab demo lock8 — 4s setup, then a 60s lock demonstration");
                String preset=PracticePresets.normalize(args[2]);
                if(!preset.equals(PracticePresets.LOCK8))throw new IllegalArgumentException("Only lock8 has a demo controller; hypixel is a provisional combat preset, not a demo.");
                if(find(safeName(sender))!=null)throw new IllegalArgumentException("Use /stop before starting a demo.");
                String a=freeBot(Set.of()),b=a==null?null:freeBot(Set.of(a));
                if(a==null||b==null){message(sender,"Need two free bot slots.");return true;}
                loadPolicies();
                start(a,b,"lock8","lock8-target",PracticePresets.defaultControllerRtt(preset),
                    PracticePresets.defaultOpponentRtt(preset),"demo-"+System.nanoTime(),64_000,"boxing",
                    PracticePresets.defaults(preset),PracticePresets.defaults(preset),6,0,"");
                Match demo=find(a);
                message(sender,"§bLock8 demo §f"+demo.id+"§7: 4s moving/no-click opponent setup, then 60s active hold-W. Reach3, cap20CPS, RTT150/0, KB1.1/1, hit8.");
                if(player(safeName(sender))!=null)command(sender,"watch "+demo.id);
                else message(sender,"Watch with /watch "+demo.id+"; afterwards /replay "+demo.id);
            }else if(cmd.equals("lab")&&args.length>=4&&MODES.contains(args[1])&&!args[1].equals("boxing")){
                String a=freeBot(Set.of());String b=a==null?null:freeBot(Set.of(a));
                if(a==null||b==null){message(sender,"Need two free bot slots.");return true;}
                BotMatchSettings setup=BotMatchSettings.parse(args,4,args[1]+"-"+System.nanoTime());
                loadPolicies();start(a,b,args[2],args[3],setup.pingA,setup.pingB,setup.request,0,args[1],setup.a,setup.b,6,0,"");
            }else if(cmd.equals("lab")&&args.length>=2&&args[1].equals("seed")){
                if(args.length==3&&args[2].equals("list")){
                    Path seeds=R.ROOT.resolve("seeds");if(!Files.exists(seeds)){message(sender,"No saved seeds.");return true;}
                    try(var paths=Files.list(seeds)){message(sender,"Seeds: "+paths.filter(p->p.toString().endsWith(".properties")).map(p->p.getFileName().toString().replace(".properties","")).sorted().toList());}return true;
                }
                if(args.length!=5&&args.length!=9)throw new IllegalArgumentException("/lab seed <seed> <bot-a> <bot-b> [rtt-a rtt-b duration-ms request]");
                SeededStartServer.named(args[2]);
                long duration=args.length==9?Long.parseLong(args[7]):60_000;
                if(duration<1000||duration>304_000)throw new IllegalArgumentException("Seed duration must be1000–304000ms");
                String a=freeBot(Set.of()),b=a==null?null:freeBot(Set.of(a));if(b==null)throw new IllegalArgumentException("Need two free bot slots");
                Properties config=new Properties();config.setProperty("seed_name",args[2]);loadPolicies();
                start(a,b,args[3],args[4],args.length==9?ping(args[5]):120,args.length==9?ping(args[6]):120,
                    args.length==9?args[8]:"seed-"+System.nanoTime(),duration,"boxing",config,config,6,0,"");
                message(sender,"Seeded research "+find(a).id+": waiting for both clients, then a2.5s quiescence barrier. /watch "+find(a).id);
            }else if(cmd.equals("lab")&&args.length==8&&args[1].equals("scenario")){
                long duration=Long.parseLong(args[6]);if(duration<1000||duration>304_000)throw new IllegalArgumentException("Scenario duration must be1000–304000ms");
                String a=freeBot(Set.of());String b=a==null?null:freeBot(Set.of(a));
                if(a==null||b==null){message(sender,"Need two free bot slots.");return true;}
                loadPolicies();start(a,b,args[2],args[3],ping(args[4]),ping(args[5]),args[7],duration);
            }else if(cmd.equals("lab")&&args.length>=4&&args[1].equals("match")){
                String a=freeBot(Set.of());String b=a==null?null:freeBot(Set.of(a));
                if(a==null||b==null){message(sender,"Need two free bot slots.");return true;}
                BotMatchSettings setup=BotMatchSettings.parse(args,4,"manual-"+System.nanoTime());
                loadPolicies();start(a,b,args[2],args[3],setup.pingA,setup.pingB,setup.request,0,"boxing",setup.a,setup.b,6,0,"");
            }else if(cmd.equals("lab")&&args.length==5&&args[1].equals("ping")){
                Match m=matches.get(args[2]);if(m==null)throw new IllegalArgumentException("Unknown active match");
                changePings(m,ping(args[3]),ping(args[4]));message(sender,"Updated "+m.id+" added RTT to "+m.pingA+"/"+m.pingB+" ms.");
            }else if(cmd.equals("lab")&&args.length>=3&&args[1].equals("goal")){
                message(sender,"Ladder matches always run to 100 accepted hits.");
            }else{
                LabHelp.show(sender,args.length>2&&args[1].equals("help")?args[2]:"1");
            }
        }catch(Throwable e){e.printStackTrace();message(sender,"§cLab: "+e.getMessage());}
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
    static String mode(String value){if(!MODES.contains(value))throw new IllegalArgumentException("Mode must be boxing, diamond, diamond_box, rod_diamond, or sumo");return value;}
    static void start(String a,String b,String pa,String pb,double pingA,double pingB,String request,long duration,String selectedMode)throws Exception{
        start(a,b,pa,pb,pingA,pingB,request,duration,selectedMode,new Properties());
    }
    static void start(String a,String b,String pa,String pb,double pingA,double pingB,String request,long duration,String selectedMode,Properties overridesB)throws Exception{
        start(a,b,pa,pb,pingA,pingB,request,duration,selectedMode,overridesB,6,0,"");
    }
    static void start(String a,String b,String pa,String pb,double pingA,double pingB,String request,long duration,String selectedMode,Properties overridesB,double startGap,int round,String preset)throws Exception{
        start(a,b,pa,pb,pingA,pingB,request,duration,selectedMode,new Properties(),overridesB,startGap,round,preset);
    }
    static void start(String a,String b,String pa,String pb,double pingA,double pingB,String request,long duration,String selectedMode,Properties overridesA,Properties overridesB,double startGap,int round,String preset)throws Exception{
        if(a.equals(b)||find(a)!=null||find(b)!=null)throw new IllegalArgumentException("A player cannot be assigned to two matches");
        if(!pa.equals("human")&&!policies.containsKey(pa)||!policies.containsKey(pb))throw new IllegalArgumentException("Unknown bot; use /bots.");
        Match m=new Match();m.mode=mode(selectedMode);m.goal=m.boxing()?100:0;m.request=request;if(duration>0){m.scenario=true;m.durationMs=duration;}
        if(m.scenario&&isBot(a)&&isBot(b)){m.combat=CombatTuning.nextScenario;m.goal=0;}
        m.combat=CombatProfile.fromOverrides(overridesB,m.combat);
        m.startGap=m.sumo()&&round==0?SumoArena.START_GAP:startGap;m.drillRound=round;m.drillPreset=preset;
        if(m.jumpReset())m.durationMs=0;
        else if(m.sumo()&&duration==0)m.durationMs=SumoArena.DURATION_MS;
        do{m.id=String.format("m%04d",++counter);}while(Files.exists(DATA.resolve("results/"+m.id+".json"))||Files.exists(DATA.resolve("replays/"+m.id+".jsonl")));
        m.a=a;m.b=b;m.pa=pa;m.pb=pb;m.pingA=m.initialPingA=pingA;m.pingB=m.initialPingB=pingB;m.start=System.currentTimeMillis()+3000;
        if(!pa.equals("human"))m.configA.putAll(policies.get(pa));else m.configA.setProperty("kind","human");
        if(!pa.equals("human"))m.configA.putAll(overridesA);
        m.configB.putAll(policies.get(pb));
        m.configB.putAll(overridesB);
        SeededStartServer.configure(m);
        ClickRate.cps(m.configA);ClickRate.cps(m.configB);DuelSettings.reach(m.configA);DuelSettings.reach(m.configB);
        Set<Integer> used=new HashSet<>();for(Match current:matches.values())used.add(current.arena);
        if(round>0)m.arena=EntryDrills.arena(a);
        while(used.contains(m.arena))m.arena++;
        Object arenaWorld=R.get(player(a),"worldObj");
        m.world=arenaWorld;
        // Warm all normal slots before the first countdown: arena maintenance
        // must not stall another match's movement or network clocks.
        if(preparedArenas.isEmpty())for(int i=0;i<4;i++)prepareArena(arenaWorld,i);
        prepareArena(arenaWorld,m.arena);
        if(duration>64_000)prepareLongArena(arenaWorld,m.arena);
        if(m.box())prepareBox(arenaWorld,m.arena);
        boolean continuousDrill=EntryDrills.sameArena(m);
        if(m.sumo()&&!continuousDrill)SumoArena.build(arenaWorld,m.arena);
        m.start=System.currentTimeMillis()+3000;
        m.replay=DATA.resolve("replays/"+m.id+".jsonl");matches.put(m.id,m);
        if(m.jumpReset())m.jumpFeedback=new JumpResetFeedback(m,player(a),player(b));
        if(m.combat.mmc())m.mmcKnockback=new MmcKnockback(m,player(a),player(b));
        PovArchives.begin(m);
        configureProxy(a,pingA);configureProxy(b,pingB);
        // At normal speed even a full 180-second pursuit cannot reach another arena.
        if(continuousDrill){
            // These entities already exist in both clients. A normal teleport
            // keeps them visible; destroying/retracking would create a blank gap.
            EntryDrills.resetPlayers(m,player(a),player(b));m.setupReseated=true;
        }else{
            resetTracking(player(a));resetTracking(player(b));
            seatPlayers(m,player(a),player(b));
        }
        if(round>0||m.sumo()){m.start=System.currentTimeMillis()+(m.sumo()?SumoArena.COUNTDOWN_MS:EntryDrills.COUNTDOWN_MS);PovArchives.manifest(m,false);}
        SeededStartServer.prepare(m);
        botLabel(player(a),m.label("a"));botLabel(player(b),m.label("b"));
        CombatTuning.configurePlayer(player(a),m.combat);CombatTuning.configurePlayer(player(b),m.combat);
        control(a,pa,b,m,pingA,pingB);control(b,pb,a,m,pingB,pingA);
        Map<String,Object> initial=m.state();initial.put("parameters_a",m.configA);initial.put("parameters_b",m.configB);
        if(round>0)initial.put("drill_reset",continuousDrill?"teleport_heal":"initial_setup");
        initial.put("equipment_a",equipment(player(a)));initial.put("equipment_b",equipment(player(b)));
        initial.put("bot_reaction_ms",Map.of("a",initial.get("reaction_ms_a"),"b",initial.get("reaction_ms_b")));
        Map<String,Object> caps=new LinkedHashMap<>();caps.put("a",initial.get("click_cps_a"));caps.put("b",initial.get("click_cps_b"));
        initial.put("bot_click_cap_cps",caps);
        append(m.replay,Map.of("type","start","match",initial));
        EntryDrills.countdown(m,System.currentTimeMillis());
        String rules=m.jumpReset()?"Jump Reset round "+round+". Knock your opponent off to win the round.":
            m.sumo()?"Sumo: bare fists, full health; knock your opponent off the platform. Two-minute limit.":
            m.drillRound>0?"Entry drill #"+round+", "+startGap+" block gap. Rod diamond; automatic repeat.":
            m.boxing()?(m.scenario?"Timed research: "+duration/1000+" seconds, no hit-score limit.":"Boxing: first to 100 hits."):
            (m.box()?"Diamond box: 1x1, wooden swords":m.rods()?"Rod diamond: wooden swords and fishing rods with PvP knockback":"Diamond: wooden swords")+", unbreakable swords, unenchanted diamond armor, fight to death. Respawn, then /rematch.";
        message(player(a),"§aStarting "+m.id+": §b"+m.label("a")+" §7vs §6"+m.label("b")+"§7. "+rules+" §b/stop §7to exit.");
        message(player(b),"§aStarting "+m.id+": §b"+m.label("a")+" §7vs §6"+m.label("b")+"§7. "+rules);
        if(m.combat.mmc())for(Object who:List.of(player(a),player(b)))
            message(who,"§dMMC-like melee knockback §7(community first-hit fit, local landing prediction).");
        else if(!m.combat.vanilla())for(Object who:List.of(player(a),player(b)))
            message(who,"§dModified combat for both players: §fKB ×"+m.combat.horizontal()+" horizontal, ×"+m.combat.vertical()+" vertical; sprint extra Y "+m.combat.sprintVertical()+"; fresh hit delay "+m.combat.freshTicks()+" ticks. §7Not an exact Hypixel profile.");
        if(pa.equals("human")){
            message(player(a),"§6Bot setup: §e"+ClickRate.cps(m.configB)+" CPS cap §8| §d"+DuelSettings.reach(m.configB)+" block reach §8| §bRTT: bot "+(int)pingB+", you "+(int)pingA+" ms");
            message(player(a),"§7Bot aim: "+m.configB.getProperty("aim_delay_ms","0")+" ms delay, "+m.configB.getProperty("aim_noise_deg","0")+"° noise, "+m.configB.getProperty("aim_turn_rate_dps","0")+"°/s cap (0=unlimited). W-tap delay: "+m.configB.getProperty("reaction_ms","150")+" ms.");
            if(DuelSettings.reach(m.configB)!=3)message(player(a),"§dModified-reach practice. Your own reach is unchanged.");
        }
        System.out.println("[PvP Lab] START "+Json.encode(m.state()));
    }
    static void seatPlayers(Match m,Object a,Object b){
        double center=4096.0*(m.arena+1);
        // Two normal 0.6-wide player bodies fit in the same 1x1 cell by
        // overlapping each other, never the walls. Native collision handles KB.
        preparePlayer(a,center+(m.box()?.32:-m.startGap/2),m.sumo()?SumoArena.TOP_Y:4,m.box()?.5:0,270,m.mode);
        preparePlayer(b,center+(m.box()?.68:m.startGap/2),m.sumo()?SumoArena.TOP_Y:4,m.box()?.5:0,90,m.mode);
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
        prepareFloor(world,arena,256,256);preparedArenas.add(arena);
    }
    static void prepareLongArena(Object world,int arena){
        if(preparedLongArenas.contains(arena))return;
        if(!matches.isEmpty())throw new IllegalArgumentException("Prepare the long arena while all matches are idle: /lab prepare long");
        prepareFloor(world,arena,1024,512);preparedLongArenas.add(arena);
    }
    static void prepareFloor(Object world,int arena,int halfX,int halfZ){
        int center=4096*(arena+1);
        // Ordinary unbreakable full blocks: same .6 slipperiness as grass/dirt.
        // Repair old holes before play, without altering movement or combat.
        // Vanilla /fill checks one extra chunk at the positive X/Z boundary
        // (its loop ends at max+16). Load that border before issuing each fill.
        for(int x=center-halfX;x<=center+halfX;x+=16)for(int z=-halfZ;z<=halfZ;z+=16)
            R.call(world,"getChunkFromChunkCoords",x>>4,z>>4);
        for(int x=center-halfX;x<center+halfX;x+=128)for(int z=-halfZ;z<halfZ;z+=128)
            vanilla("fill "+x+" 3 "+z+" "+(x+127)+" 3 "+(z+127)+" minecraft:bedrock");
        // Old test/player blocks above the floor also invalidate flat-ground
        // comparisons. Each ordinary fill stays within vanilla's 32768 cap.
        for(int x=center-halfX;x<center+halfX;x+=64)for(int z=-halfZ;z<halfZ;z+=64)
            for(int y=4;y<20;y+=8)
                vanilla("fill "+x+" "+y+" "+z+" "+(x+63)+" "+(y+7)+" "+(z+63)+" minecraft:air");
        Object bedrock=R.get(R.cls("net.minecraft.init.Blocks"),"bedrock");
        Object air=R.get(R.cls("net.minecraft.init.Blocks"),"air");
        for(int x=center-halfX;x<center+halfX;x++)for(int z=-halfZ;z<halfZ;z++){
            Object pos=R.construct("net.minecraft.util.BlockPos",x,3,z);
            if(R.call(R.call(world,"getBlockState",pos),"getBlock")!=bedrock)
                throw new IllegalStateException("Arena floor verification failed at "+x+",3,"+z);
            for(int y=4;y<20;y++){
                Object above=R.construct("net.minecraft.util.BlockPos",x,y,z);
                if(R.call(R.call(world,"getBlockState",above),"getBlock")!=air)
                    throw new IllegalStateException("Arena air verification failed at "+x+","+y+","+z);
            }
        }
        System.out.println("[PvP Lab] Verified "+(4L*halfX*halfZ)+" bedrock floor blocks and "+(64L*halfX*halfZ)+" air blocks in arena "+arena);
    }
    static void preparePlayer(Object p,double x,double y,double z,int yaw,String mode){
        watching.remove(R.name(p));
        String name=safeName(p);vanilla("gamemode 0 "+name);vanilla("clear "+name);vanilla("effect "+name+" clear");
        R.call(p,"setSpectatingEntity",p);
        R.call(p,"clearItemInUse");R.set(R.get(p,"inventory"),"currentItem",0);
        boolean sumo=mode.equals("sumo");
        String weapon=sumo?"fist":mode.equals("boxing")?"diamond_sword":"wooden_sword";
        if(!sumo)vanilla("give "+name+" minecraft:"+weapon+" 1 0 {Unbreakable:1b}");
        if(mode.equals("rod_diamond"))vanilla("replaceitem entity "+name+" slot.hotbar.1 minecraft:fishing_rod 1 0 {Unbreakable:1b}");
        if(!sumo&&!mode.equals("boxing")){
            String[][] armor={{"head","helmet"},{"chest","chestplate"},{"legs","leggings"},{"feet","boots"}};
            for(String[] piece:armor)vanilla("replaceitem entity "+name+" slot.armor."+piece[0]+" minecraft:diamond_"+piece[1]+" 1 0 {Unbreakable:1b}");
        }
        Map<String,Object> gear=equipment(p);
        if(!gear.get("weapon").equals(weapon)||!sumo&&!gear.get("weapon_unbreakable").equals(true)||
                !gear.get("protection_levels").equals(List.of(0,0,0,0))||
                !gear.get("armor_points").equals(sumo||mode.equals("boxing")?0:20)||
                !sumo&&!mode.equals("boxing")&&!gear.get("armor_unbreakable").equals(List.of(true,true,true,true))||
                mode.equals("rod_diamond")&&(!gear.get("fishing_rod_slot_2").equals(true)||!gear.get("fishing_rod_unbreakable").equals(true)))
            throw new IllegalStateException("Practice kit verification failed: "+gear);
        resetRoundPosition(p,x,y,z,yaw);
    }
    static void resetRoundPosition(Object p,double x,double y,double z,int yaw){
        R.call(p,"clearItemInUse");
        Object hook=R.get(p,"fishEntity");if(hook!=null){R.call(hook,"setDead");R.set(p,"fishEntity",null);}
        vanilla("tp "+safeName(p)+" "+x+" "+y+" "+z+" "+yaw+" 0");
        settleTeleport(p);
        R.call(p,"setHealth",20.0f);R.call(R.call(p,"getFoodStats"),"setFoodLevel",20);
        fullFood(p);R.set(R.call(p,"getFoodStats"),"foodTimer",0);
        R.set(p,"fallDistance",0.0f);
        R.set(p,"motionX",0.0);R.set(p,"motionY",0.0);R.set(p,"motionZ",0.0);
        R.call(R.get(p,"playerNetServerHandler"),"sendPacket",R.construct("net.minecraft.network.play.server.S12PacketEntityVelocity",p));
    }
    static Map<String,Object> equipment(Object p){
        List<Integer> protection=new ArrayList<>();
        List<Boolean> armorUnbreakable=new ArrayList<>();
        for(Object stack:(Object[])R.get(R.get(p,"inventory"),"armorInventory")){
            protection.add(stack==null?0:(int)R.call(R.cls("net.minecraft.enchantment.EnchantmentHelper"),"getEnchantmentLevel",0,stack));
            armorUnbreakable.add(unbreakable(stack));
        }
        Object held=R.call(p,"getHeldItem"),item=held==null?null:R.call(held,"getItem");
        String weapon=item==null?"fist":item==R.get(R.cls("net.minecraft.init.Items"),"wooden_sword")?"wooden_sword":
            item==R.get(R.cls("net.minecraft.init.Items"),"diamond_sword")?"diamond_sword":"other";
        Object second=((Object[])R.get(R.get(p,"inventory"),"mainInventory"))[1];
        boolean rod=second!=null&&R.call(second,"getItem")==R.get(R.cls("net.minecraft.init.Items"),"fishing_rod");
        return Map.of("weapon",weapon,"weapon_unbreakable",unbreakable(held),
            "armor_points",R.call(p,"getTotalArmorValue"),"protection_levels",protection,"armor_unbreakable",armorUnbreakable,
            "fishing_rod_slot_2",rod,"fishing_rod_unbreakable",rod&&unbreakable(second));
    }
    static boolean unbreakable(Object stack){
        Object tag=stack==null?null:R.call(stack,"getTagCompound");
        return tag!=null&&(Boolean)R.call(tag,"getBoolean","Unbreakable");
    }
    static void fullFood(Object p){
        Object food=R.call(p,"getFoodStats");R.call(food,"setFoodLevel",20);
        R.set(food,"foodSaturationLevel",20.0f);R.set(food,"foodExhaustionLevel",0.0f);
        // Do not reset foodTimer: vanilla regeneration timing still runs.
    }
    static void control(String who,String policy,String opponent,Match m,double ping,double oppPing){
        if(!isBot(who))return;
        if(!isBot(opponent)&&PovLink.subscribed(opponent)){
            Properties config=who.equals(m.a)?m.configA:m.configB;
            config.setProperty("science_trace","1");config.setProperty("science_network_trace","1");
            config.setProperty("fast_view","1");
        }
        Properties p=new Properties();p.putAll(who.equals(m.a)?m.configA:m.configB);p.setProperty("enabled","true");p.setProperty("opponent",opponent);
        p.setProperty("archive_side",who.equals(m.a)?"a":"b");
        p.setProperty("match",m.id);p.setProperty("start_at",Long.toString(m.start));p.setProperty("ping",Double.toString(ping));p.setProperty("opponent_ping",Double.toString(oppPing));
        R.writeProperties(DATA.resolve("bots/"+who.substring(7)+"/control.properties"),p);
    }
    static void disable(String who){if(isBot(who)){Properties p=new Properties();p.setProperty("enabled","false");R.writeProperties(DATA.resolve("bots/"+who.substring(7)+"/control.properties"),p);}}
    static Path proxyPath(String who){return DATA.resolve("proxy/"+(isBot(who)?"slot"+who.substring(7):who.equals("LabObserver")?"observer":"human")+".properties");}
    static Path preferencePath(String name){
        if(!name.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid player name");
        return DATA.resolve("preferences/"+name.toLowerCase(Locale.ROOT)+".properties");
    }
    static double preferredPing(String name){
        Properties saved=R.properties(preferencePath(name));
        // Migrate the existing single-human proxy setting on the first login.
        return ping(saved.getProperty("human_ping",R.properties(proxyPath(name)).getProperty("rtt_ms","0")));
    }
    static void rememberPing(String name,double ping){
        Properties saved=R.properties(preferencePath(name));saved.setProperty("human_ping",Double.toString(ping));R.writeProperties(preferencePath(name),saved);
    }
    static void configureProxy(String who,double ping){Properties p=new Properties();p.setProperty("rtt_ms",Double.toString(ping));R.writeProperties(proxyPath(who),p);}
    static void changePings(Match m,double a,double b){
        m.pingA=a;m.pingB=b;m.pingChanges++;configureProxy(m.a,a);configureProxy(m.b,b);
        updateBotPing(m.a,a,b);updateBotPing(m.b,b,a);
        if(m.pa.equals("human")){rememberPing(m.a,a);lastPings.put(m.a,new double[]{b,a});}
        append(m.replay,Map.of("type","ping_change","time",System.currentTimeMillis(),"ping_a",a,"ping_b",b));
    }
    static void updateBotPing(String name,double own,double other){
        if(!isBot(name))return;
        Path path=DATA.resolve("bots/"+name.substring(7)+"/control.properties");Properties p=R.properties(path);
        p.setProperty("ping",Double.toString(own));p.setProperty("opponent_ping",Double.toString(other));R.writeProperties(path,p);
    }
    static void finish(Match m,String reason){
        m.finish=System.currentTimeMillis();Map<String,Object> result=m.state();result.put("reason",reason);
        Object finalA=player(m.a),finalB=player(m.b);
        if(m.jumpFeedback!=null){
            m.jumpFeedback.close();
            if(finalA!=null)overlay(finalA,"");
        }
        if(m.mmcKnockback!=null)m.mmcKnockback.close();
        if(finalA!=null&&finalB!=null)append(m.replay,Map.of("type","tick","time",m.finish,"a",replayPosition(finalA),"b",replayPosition(finalB),
            "hits_a",m.ha,"hits_b",m.hb,"health_a",R.call(finalA,"getHealth"),"health_b",R.call(finalB,"getHealth")));
        for(String side:List.of("a","b")){Object p=player(side.equals("a")?m.a:m.b);result.put("health_"+side,p==null?null:R.call(p,"getHealth"));}
        result.put("initial_ping_a",m.initialPingA);result.put("initial_ping_b",m.initialPingB);result.put("ping_changes",m.pingChanges);
        result.put("parameters_a",m.configA);result.put("parameters_b",m.configB);
        result.put("duration_seconds",Math.max(0,(m.finish-m.start)/1000.0));
        writeJson(DATA.resolve("results/"+m.id+".json"),result);append(m.replay,Map.of("type","finish","result",result));
        matches.remove(m.id);
        if(finalA!=null)CombatTuning.configurePlayer(finalA,CombatProfile.VANILLA);
        if(finalB!=null)CombatTuning.configurePlayer(finalB,CombatProfile.VANILLA);
        Set<String> recipients=new LinkedHashSet<>(List.of(m.a,m.b));
        for(var watcher:watching.entrySet())if(watcher.getValue().equals(m.id))recipients.add(watcher.getKey());
        for(String who:recipients){Object p=player(who);if(p!=null){
            String outcome=m.winner.isEmpty()?"§e"+reason.replace('_',' '):
                (m.winner.equals(who)?"§aYOU WON":(who.equals(m.a)||who.equals(m.b)?"§cYOU LOST":"§aWinner"))+
                " §7• §f"+(isBot(m.winner)?m.label(m.winner.equals(m.a)?"a":"b"):m.winner);
            if(m.drillRound>0){
                String stats=m.jumpReset()?"§bJump Reset #"+m.drillRound+" §8| "+outcome+" §8| §eHits taken: "+m.hb:
                    "§bDrill #"+m.drillRound+" §7("+m.startGap+" blocks) §8| §eSword hits: "+m.sa.breakdown.damageHitsLanded+"–"+m.sb.breakdown.damageHitsLanded;
                message(p,stats+" §8| §b/stop §7exits; §b/replay "+m.id);
                continue;
            }
            message(p,"§6§lFinished "+m.id+" §r§8["+m.mode+"] §7— "+outcome);
            if(DuelSettings.reach(m.configA)!=3||DuelSettings.reach(m.configB)!=3)message(p,"§dModified reach: "+DuelSettings.reach(m.configA)+" / "+DuelSettings.reach(m.configB)+" blocks (first / second player).");
            if(m.sumo()){
                message(p,"§b"+summaryName(m.a,m.label("a"),who,"§b")+" §e"+m.ha+" hits §8| §6"+summaryName(m.b,m.label("b"),who,"§6")+" §e"+m.hb+" hits");
                message(p,"§7"+reason.replace('_',' ')+" · "+String.format(Locale.ROOT,"%.1f",Math.max(0,m.finish-m.start)/1000.0)+" seconds");
                message(p,"§b/rematch §8| §b/bots §8| §b/replay "+m.id);
                if(!isBot(who))button(p,"Watch server replay "+m.id,"/replay "+m.id);
                continue;
            }
            m.sa.summary(p,summaryName(m.a,m.label("a"),who,"§b"),m.ha,m.ba,m.rods(),(Number)result.get("health_a"),m.hb-m.sb.rodHits,m.finish,Math.max(0,m.finish-m.start));
            m.sb.summary(p,summaryName(m.b,m.label("b"),who,"§6"),m.hb,m.bb,m.rods(),(Number)result.get("health_b"),m.ha-m.sa.rodHits,m.finish,Math.max(0,m.finish-m.start));
            message(p,"§8Crits are included in hits. Rod contacts are separate. Health/damage in hearts.");
            message(p,"§8Per-hit damage uses actual HP lost to melee, including crits/blocks; excludes rods and other damage.");
            message(p,"§8Hits count HP loss; rod contacts are separate. Saved/extra damage keeps the same armor and Protection roll.");
            message(p,((Boolean)R.call(p,"isEntityAlive")?"":"§eRespawn first. ")+"§b/rematch §8| §b/bots §8| §b/replay "+m.id);
            if(!isBot(who))button(p,"Watch server replay "+m.id,"/replay "+m.id);
        }}
        boolean repeatInArena=EntryDrills.finished(m,reason);
        for(String who:List.of(m.a,m.b)){
            disable(who);Object p=player(who);if(p==null)continue;
            botLabel(p,"");
            if(reason.equals("invalid_client_view"))message(p,"§cStopped: a bot's native opponent view was invalid. This game is not a valid result. /rematch starts fresh.");
            if(repeatInArena)continue;
            if((Boolean)R.call(p,"isEntityAlive"))returnToLobby(p);else pendingLobby.add(who);
        }
        if(m.box())clearBox(m.world,m.arena);
        if(m.sumo()&&!repeatInArena)SumoArena.clear(m.world,m.arena);
        PovArchives.finished(m);
        System.out.println("[PvP Lab] RESULT "+Json.encode(result));
    }
    static String summaryName(String name,String policy,String recipient,String color){
        return color+(name.equals(recipient)?"You ("+name+")":isBot(name)?policy+" (bot)":name);
    }
    static void botLabel(Object player,String label){
        if(player!=null&&isBot(R.name(player)))R.call(player,"setCustomNameTag",label);
    }
    static void returnToLobby(Object p){
        String who=safeName(p);R.call(p,"clearItemInUse");R.call(p,"setHealth",20.0f);
        fullFood(p);
        if(isBot(who)){vanilla("gamemode 3 "+who);teleport(p,-40-Integer.parseInt(who.substring(7))*6,4,0);}else{configureProxy(who,preferredPing(who));vanilla("gamemode 0 "+who);teleport(p,0,4,0);}
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
    static Map<String,Object> replayPosition(Object p){
        Map<String,Object> pos=ScienceTrace.state(p);
        pos.put("eye_height",R.call(p,"getEyeHeight"));pos.put("border",R.call(p,"getCollisionBorderSize"));pos.put("health",R.call(p,"getHealth"));
        for(String key:List.of("rotationYaw","rotationPitch","rotationYawHead","swingProgressInt","swingProgress","limbSwing","limbSwingAmount","renderYawOffset","hurtTime"))pos.put(key,R.num(p,key));
        pos.put("onGround",R.get(p,"onGround"));pos.put("isSwingInProgress",R.get(p,"isSwingInProgress"));
        pos.put("blocking",R.call(p,"isBlocking"));pos.put("sneaking",R.call(p,"isSneaking"));
        Object held=R.call(p,"getHeldItem");pos.put("held_item_id",held==null?0:R.call(R.cls("net.minecraft.item.Item"),"getIdFromItem",R.call(held,"getItem")));
        Object hook=R.get(p,"fishEntity");
        if(hook!=null)pos.put("fish_hook",Map.of("id",R.call(hook,"getEntityId"),"x",R.num(hook,"posX"),"y",R.num(hook,"posY"),"z",R.num(hook,"posZ")));
        return pos;
    }
    static double ping(String s){return DuelSettings.ping(s);}
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
    static void message(Object sender,String text){if(sender==null)return;R.call(sender,"addChatMessage",R.construct("net.minecraft.util.ChatComponentText","§7"+text+"§r"));}
    static void button(Object sender,String label,String command){
        if(!R.cls("net.minecraft.entity.player.EntityPlayerMP").isInstance(sender)){message(sender,label);return;}
        vanilla("tellraw "+safeName(sender)+" "+Json.encode(Map.of("text","[ "+label+" ]","color","aqua","clickEvent",Map.of("action","run_command","value",command))));
    }
    static void overlay(Object player,String text){R.call(R.get(player,"playerNetServerHandler"),"sendPacket",R.construct("net.minecraft.network.play.server.S02PacketChat",R.construct("net.minecraft.util.ChatComponentText",text),(byte)2));}
    static void writeJson(Path path,Object value){try{Files.createDirectories(path.getParent());Path tmp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(tmp,Json.encode(value));Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){throw new RuntimeException(e);}}
    static void append(Path path,Object value){try{Files.createDirectories(path.getParent());Files.writeString(path,Json.encode(value)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException e){throw new RuntimeException(e);}}
}
