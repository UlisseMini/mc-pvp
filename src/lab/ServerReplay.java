package lab;

import java.util.*;

/** Private packet-only actors: never registered as live players/world entities and never run combat. */
public final class ServerReplay {
    static final Map<String,Session> sessions=new LinkedHashMap<>();
    static final Set<String> floorChunks=new HashSet<>();
    static final String USAGE="/replay [match-id] | controls | list [all] | pause | play | back/forward [seconds] | seek <seconds or +/-seconds> | speed <positive multiplier> | a | b | free | stop";
    static final class Session {
        final String viewer;
        final ReplayData data;
        final ReplayTimeline clock;
        final Object[] actors=new Object[2],hooks=new Object[2];
        final int[] hookRecordedId={-1,-1},held={-1,-1},swing={-1,-1};
        final int arena;
        final double center;
        final long readyAt;
        int eventIndex;
        String camera="free",lastEvent="";
        long eventUntil;
        boolean spawned,ended,startPlaying=true;
        Session(String viewer,ReplayData data,int arena,long now){
            this.viewer=viewer;this.data=data;this.arena=arena;center=4096.0*(arena+1);
            readyAt=now+2000;clock=new ReplayTimeline(data.duration,readyAt);clock.pause(true,readyAt);
        }
    }
    static void send(Object viewer,Object packet){R.call(R.get(viewer,"playerNetServerHandler"),"sendPacket",packet);}
    static int id(Object entity){return ((Number)R.call(entity,"getEntityId")).intValue();}
    static Object item(int itemId){
        if(itemId<=0)return null;
        Object nativeItem=R.call(R.cls("net.minecraft.item.Item"),"getItemById",itemId);
        return nativeItem==null?null:R.construct("net.minecraft.item.ItemStack",nativeItem);
    }
    static Object namedItem(String name){return R.construct("net.minecraft.item.ItemStack",R.get(R.cls("net.minecraft.init.Items"),name));}
    static int namedItemId(String name){return ((Number)R.call(R.cls("net.minecraft.item.Item"),"getIdFromItem",R.get(R.cls("net.minecraft.init.Items"),name))).intValue();}
    static Object listAction(String name){return R.get(R.cls("net.minecraft.network.play.server.S38PacketPlayerListItem$Action"),name);}
    static void listPacket(Object viewer,Object actor,String action){
        send(viewer,R.construct("net.minecraft.network.play.server.S38PacketPlayerListItem",listAction(action),List.of(actor)));
    }
    static Object actor(Object viewer,String label,UUID uuid){
        Object world=R.get(viewer,"worldObj");
        String name=label.replaceAll("[^A-Za-z0-9_]","_");if(name.length()>16)name=name.substring(0,16);
        return R.construct("net.minecraft.entity.player.EntityPlayerMP",Referee.server,world,
            R.construct("com.mojang.authlib.GameProfile",uuid,name),R.construct("net.minecraft.server.management.ItemInWorldManager",world));
    }
    static void start(Object viewer,String match)throws Exception {
        String who=Referee.safeName(viewer);
        if(Referee.find(who)!=null)throw new IllegalArgumentException("Finish your duel or use /stop before watching a replay.");
        if(!(Boolean)R.call(viewer,"isEntityAlive"))throw new IllegalArgumentException("Respawn first, then use /replay.");
        ReplayData data=new ReplayData(match); // Validate before replacing an existing replay.
        close(who,false);Referee.watching.remove(who);
        int arena=-2;while(inUse(arena))arena--;
        Object world=R.get(viewer,"worldObj");
        prepareStage(world,data,4096.0*(arena+1));Referee.clearBox(world,arena);
        if("diamond_box".equals(data.result.get("mode")))Referee.prepareBox(world,arena);
        Session s=new Session(who,data,arena,System.currentTimeMillis());sessions.put(who,s);
        for(int i=0;i<2;i++)s.actors[i]=actor(viewer,data.label(i==0?"a":"b"),UUID.randomUUID());
        Referee.vanilla("gamemode 3 "+who);R.call(viewer,"setSpectatingEntity",viewer);
        Map<String,Object> first=data.frame(0),a=ReplayData.map(first.get("a")),b=ReplayData.map(first.get("b"));
        Referee.teleport(viewer,s.center-data.originalCenter+(ReplayData.number(a,"posX",data.originalCenter)+ReplayData.number(b,"posX",data.originalCenter))/2,9,
            (ReplayData.number(a,"posZ",0)+ReplayData.number(b,"posZ",0))/2+6);
        Referee.message(viewer,"§6Server replay "+match+" §7— "+data.label("a")+" vs "+data.label("b")+". Recorded server positions/events; not either client's original POV.");
        if(!data.posesRecorded)Referee.message(viewer,"§7Older recording: look direction/equipment are approximated; rod flight and precise block poses were not recorded.");
        controls(viewer);
    }
    static boolean inUse(int arena){for(Session s:sessions.values())if(s.arena==arena)return true;return false;}
    static void prepareStage(Object world,ReplayData data,double center){
        // Render only the recorded path's flat floor. Ghosts need no physics arena
        // or million-block validation, which would stall unrelated live duels.
        Set<String> needed=new LinkedHashSet<>();
        for(Map<String,Object> f:data.frames)for(String side:List.of("a","b")){
            Map<String,Object> p=ReplayData.map(f.get(side));int x=(int)Math.floor(ReplayData.number(p,"posX",data.originalCenter)+center-data.originalCenter)>>4;
            int z=(int)Math.floor(ReplayData.number(p,"posZ",0))>>4;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)needed.add((x+dx)+":"+(z+dz));
        }
        if(needed.size()>1024)throw new IllegalArgumentException("Recording covers too much ground for this replay viewer.");
        for(String key:needed)if(!floorChunks.contains(key)){
            String[] pair=key.split(":");int x=Integer.parseInt(pair[0])*16,z=Integer.parseInt(pair[1])*16;
            // /fill checks the extra positive border chunks as well.
            for(int dx=0;dx<=1;dx++)for(int dz=0;dz<=1;dz++)R.call(world,"getChunkFromChunkCoords",(x>>4)+dx,(z>>4)+dz);
            Referee.vanilla("fill "+x+" 3 "+z+" "+(x+15)+" 3 "+(z+15)+" minecraft:bedrock");floorChunks.add(key);
        }
    }
    static void controls(Object p){
        Referee.message(p,"§6Replay controls §7— open chat (T) and click. /replay controls shows these again.");
        controlRow(p,"Time",new String[][]{{"-5s","back 5"},{"-1s","back 1"},{"Pause","pause"},{"Play","play"},{"+1s","forward 1"},{"+5s","forward 5"},{"Go to...","seek ","suggest_command"}});
        controlRow(p,"Speed",new String[][]{{"0.1x","speed 0.1"},{"0.25x","speed 0.25"},{"0.5x","speed 0.5"},{"1x","speed 1"},{"2x","speed 2"},{"Custom...","speed ","suggest_command"}});
        controlRow(p,"View",new String[][]{{"Follow A","a"},{"Follow B","b"},{"Fly freely","free"},{"Exit replay","stop"}});
        Referee.message(p,"§7Any positive speed works. Seek: 12.5 = timestamp; -5 / +5 = relative seconds.");
    }
    static void controlRow(Object p,String label,String[][] buttons){
        List<Map<String,Object>> extra=new ArrayList<>();
        for(String[] b:buttons){String command="/replay "+b[1],action=b.length>2?b[2]:"run_command";
            extra.add(Map.of("text","["+b[0]+"] ","color","aqua",
                "clickEvent",Map.of("action",action,"value",command),
                "hoverEvent",Map.of("action","show_text","value",action.equals("suggest_command")?"Type a number after "+command:command)));
        }
        Referee.vanilla("tellraw "+Referee.safeName(p)+" "+Json.encode(Map.of("text",label+": ","color","gray","extra",extra)));
    }
    public static void command(Object viewer,String[] args)throws Exception {
        String who=Referee.safeName(viewer);
        if(Referee.player(who)==null)throw new IllegalArgumentException("Use /replay from your Minecraft client.");
        String action=args.length>1?args[1]:"latest";
        if(action.equals("list")){
            boolean all=args.length==3&&args[2].equals("all");
            if(args.length>3||args.length==3&&!all)throw new IllegalArgumentException("Usage: /replay list [all]");
            List<Map<String,Object>> rows=ReplayData.recent(who,all);
            Referee.message(viewer,all?"§6Recent server recordings:":"§6Your recent recordings:");
            for(Map<String,Object> row:rows){String match=ReplayData.string(row,"id","");
                Referee.button(viewer,match+" ["+row.get("mode")+"] "+row.get("a")+" vs "+row.get("policy_b"),"/replay "+match);}
            if(rows.isEmpty())Referee.message(viewer,"No saved matches yet. /replay list all shows bot matches too.");return;
        }
        if(action.equals("latest")||action.matches("m[0-9]{4,9}")){
            if(args.length>2)throw new IllegalArgumentException(USAGE);
            if(action.equals("latest")){
                List<Map<String,Object>> rows=ReplayData.recent(who,false);
                if(rows.isEmpty())throw new IllegalArgumentException("No saved matches of yours yet. Use /replay list all.");
                action=ReplayData.string(rows.get(0),"id","");
            }
            start(viewer,action);return;
        }
        if(action.equals("stop")&&args.length==2){close(who,true);return;}
        Session s=sessions.get(who);if(s==null)throw new IllegalArgumentException("Start with /replay or /replay <match-id>.");
        long now=System.currentTimeMillis();
        if(action.equals("pause")&&args.length==2){s.startPlaying=false;s.clock.pause(true,now);}
        else if(action.equals("play")&&args.length==2){s.startPlaying=true;if(s.clock.position()>=s.data.duration)seek(s,0,false,now);s.clock.pause(false,now);}
        else if(action.equals("controls")&&args.length==2)controls(viewer);
        else if(action.equals("speed")&&args.length==3)s.clock.speed(Double.parseDouble(args[2]),now);
        else if(action.equals("seek")&&args.length==3){
            seek(s,Double.parseDouble(args[2]),args[2].startsWith("+")||args[2].startsWith("-"),now);
        }else if(Set.of("back","forward").contains(action)&&(args.length==2||args.length==3)){
            double seconds=args.length==3?Double.parseDouble(args[2]):5;
            if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Use /replay "+action+" [seconds], with a nonnegative number (default: 5).");
            seek(s,action.equals("back")?-seconds:seconds,true,now);
        }else if(Set.of("a","b","free").contains(action)&&args.length==2){
            s.camera=action;if(s.spawned)camera(viewer,s);
        }else throw new IllegalArgumentException(USAGE);
    }
    static void seek(Session s,double seconds,boolean relative,long now){
        s.clock.seekSeconds(seconds,relative,now);s.eventIndex=0;s.ended=false;s.lastEvent="";
        while(s.eventIndex<s.data.events.size()&&ReplayData.number(s.data.events.get(s.eventIndex),"time",0)<=s.data.start+s.clock.position())s.eventIndex++;
        Arrays.fill(s.swing,-1);
    }
    static void camera(Object viewer,Session s){R.call(viewer,"setSpectatingEntity",s.camera.equals("free")?viewer:s.actors[s.camera.equals("a")?0:1]);}
    static void pose(Session s,Object viewer,int side,Map<String,Object> frame,boolean spawn){
        String key=side==0?"a":"b";Object actor=s.actors[side];Map<String,Object> p=ReplayData.map(frame.get(key));
        Map<String,Object> other=ReplayData.map(frame.get(side==0?"b":"a"));
        double x=ReplayData.number(p,"posX",s.data.originalCenter),y=ReplayData.number(p,"posY",4),z=ReplayData.number(p,"posZ",0);
        double fallbackYaw=Math.toDegrees(Math.atan2(-(ReplayData.number(other,"posX",x)-x),ReplayData.number(other,"posZ",z)-z));
        float yaw=(float)ReplayData.number(p,"rotationYaw",fallbackYaw),pitch=(float)ReplayData.number(p,"rotationPitch",0);
        R.call(actor,"setPositionAndRotation",x+s.center-s.data.originalCenter,y,z,yaw,pitch);
        R.set(actor,"rotationYawHead",(float)ReplayData.number(p,"rotationYawHead",yaw));
        R.set(actor,"onGround",p.containsKey("onGround")?ReplayData.flag(p,"onGround"):y<=4.0001);
        R.call(actor,"setSneaking",ReplayData.flag(p,"sneaking"));R.call(actor,"setSprinting",ReplayData.flag(p,"sprint"));
        R.call(actor,"setFlag",4,ReplayData.flag(p,"blocking"));
        R.call(actor,"setHealth",(float)Math.max(.01,ReplayData.number(frame,"health_"+key,20)));
        if(spawn){
            listPacket(viewer,actor,"ADD_PLAYER");send(viewer,R.construct("net.minecraft.network.play.server.S0CPacketSpawnPlayer",actor));
            String[] armor={"diamond_boots","diamond_leggings","diamond_chestplate","diamond_helmet"};
            boolean equipped=!"boxing".equals(s.data.result.get("mode"));
            for(int slot=1;slot<=4;slot++)send(viewer,R.construct("net.minecraft.network.play.server.S04PacketEntityEquipment",id(actor),slot,equipped?namedItem(armor[slot-1]):null));
        }
        send(viewer,R.construct("net.minecraft.network.play.server.S18PacketEntityTeleport",actor));
        send(viewer,R.construct("net.minecraft.network.play.server.S19PacketEntityHeadLook",actor,(byte)(R.num(actor,"rotationYawHead")*256/360)));
        send(viewer,R.construct("net.minecraft.network.play.server.S1CPacketEntityMetadata",id(actor),R.call(actor,"getDataWatcher"),true));
        int held=(int)ReplayData.number(p,"held_item_id",namedItemId("boxing".equals(s.data.result.get("mode"))?"diamond_sword":"wooden_sword"));
        if(spawn||s.held[side]!=held){send(viewer,R.construct("net.minecraft.network.play.server.S04PacketEntityEquipment",id(actor),0,item(held)));s.held[side]=held;}
        int swing=(int)ReplayData.number(p,"swingProgressInt",-1);
        if(!spawn&&!s.clock.paused()&&ReplayData.flag(p,"isSwingInProgress")&&(s.swing[side]<0||swing<s.swing[side]))send(viewer,R.construct("net.minecraft.network.play.server.S0BPacketAnimation",actor,0));
        s.swing[side]=ReplayData.flag(p,"isSwingInProgress")?swing:-1;
        hook(s,viewer,side,ReplayData.map(p.get("fish_hook")));
    }
    static void hook(Session s,Object viewer,int side,Map<String,Object> p){
        int recorded=(int)ReplayData.number(p,"id",-1);
        if(recorded!=s.hookRecordedId[side]){
            if(s.hooks[side]!=null)send(viewer,R.construct("net.minecraft.network.play.server.S13PacketDestroyEntities",new int[]{id(s.hooks[side])}));
            s.hooks[side]=null;s.hookRecordedId[side]=recorded;
            if(recorded!=-1){
                Object h=R.construct("net.minecraft.entity.projectile.EntityFishHook",R.get(viewer,"worldObj"));s.hooks[side]=h;
                R.call(h,"setPosition",ReplayData.number(p,"x",0)+s.center-s.data.originalCenter,ReplayData.number(p,"y",4),ReplayData.number(p,"z",0));
                send(viewer,R.construct("net.minecraft.network.play.server.S0EPacketSpawnObject",h,90,id(s.actors[side])));
            }
        }
        if(s.hooks[side]!=null){Object h=s.hooks[side];
            R.call(h,"setPosition",ReplayData.number(p,"x",0)+s.center-s.data.originalCenter,ReplayData.number(p,"y",4),ReplayData.number(p,"z",0));
            send(viewer,R.construct("net.minecraft.network.play.server.S18PacketEntityTeleport",h));}
    }
    public static void tick(){
        long now=System.currentTimeMillis();
        for(Session s:new ArrayList<>(sessions.values()))try{
            Object viewer=Referee.player(s.viewer);if(viewer==null){sessions.remove(s.viewer);continue;}
            if(now<s.readyAt)continue;
            boolean spawn=!s.spawned;
            if(spawn){s.clock.seek(s.clock.position(),now);s.clock.pause(!s.startPlaying,now);}
            long ms=s.clock.advance(now);Map<String,Object> frame=s.data.frame(ms);
            for(int i=0;i<2;i++)pose(s,viewer,i,frame,spawn);
            if(spawn){s.spawned=true;camera(viewer,s);}
            while(s.eventIndex<s.data.events.size()&&ReplayData.number(s.data.events.get(s.eventIndex),"time",0)<=s.data.start+ms){
                Map<String,Object> e=s.data.events.get(s.eventIndex++);String type=ReplayData.string(e,"type","");
                int attacker=Objects.equals(e.get("attacker"),s.data.result.get("a"))?0:1;
                int victim=1-attacker;Object a=s.actors[attacker],b=s.actors[victim];
                if(type.equals("hit")){
                    if(!ReplayData.flag(e,"rod"))send(viewer,R.construct("net.minecraft.network.play.server.S0BPacketAnimation",a,0));
                    send(viewer,R.construct("net.minecraft.network.play.server.S19PacketEntityStatus",b,(byte)2));
                    s.lastEvent=s.data.label(attacker==0?"a":"b")+(ReplayData.flag(e,"rod")?" rod contact":ReplayData.flag(e,"damage_upgrade")?" damage upgrade":" sword hit");s.eventUntil=now+700;
                }else if(type.equals("critical"))send(viewer,R.construct("net.minecraft.network.play.server.S0BPacketAnimation",b,4));
                else if(type.equals("rod_trick")){s.lastEvent="Rod trick: "+s.data.label(attacker==0?"a":"b");s.eventUntil=now+1000;}
                else if(type.equals("blocked_damage")){s.lastEvent=s.data.label(victim==0?"a":"b")+" blocked damage";s.eventUntil=now+700;}
            }
            if(Referee.ticks%2==0)Referee.overlay(viewer,String.format(Locale.ROOT,"§6Replay %s §f%.2f/%.2fs §7%s%s §8| §b%s %.1f♥ §7vs §6%s %.1f♥%s",
                s.data.id,ms/1000.0,s.data.duration/1000.0,s.clock.speedLabel(),s.clock.paused()?" PAUSED":"",s.data.label("a"),ReplayData.number(frame,"health_a",20)/2,
                s.data.label("b"),ReplayData.number(frame,"health_b",20)/2,now<s.eventUntil?" §8| §e"+s.lastEvent:""));
            if(ms>=s.data.duration&&!s.ended){s.ended=true;Referee.message(viewer,"§6Replay finished. §7/replay play to restart; /replay seek <seconds> to review; /stop to leave.");}
        }catch(Throwable e){e.printStackTrace();Object viewer=Referee.player(s.viewer);close(s.viewer,true);if(viewer!=null)Referee.message(viewer,"§cReplay stopped: "+e.getMessage());}
    }
    static boolean close(String who,boolean lobby){
        Session s=sessions.remove(who);if(s==null)return false;Object viewer=Referee.player(who);
        if(viewer!=null){
            R.call(viewer,"setSpectatingEntity",viewer);
            for(Object actor:s.actors)if(actor!=null){send(viewer,R.construct("net.minecraft.network.play.server.S13PacketDestroyEntities",new int[]{id(actor)}));listPacket(viewer,actor,"REMOVE_PLAYER");}
            for(Object hook:s.hooks)if(hook!=null)send(viewer,R.construct("net.minecraft.network.play.server.S13PacketDestroyEntities",new int[]{id(hook)}));
            if(lobby)Referee.returnToLobby(viewer);
        }
        return true;
    }
    static List<Map<String,Object>> state(){
        List<Map<String,Object>> out=new ArrayList<>();
        for(Session s:sessions.values())out.add(Map.of("viewer",s.viewer,"match",s.data.id,"time_ms",s.clock.position(),"duration_ms",s.data.duration,
            "paused",s.clock.paused(),"speed",s.clock.speed(),"camera",s.camera,"spawned",s.spawned,
            "actor_ids",Arrays.stream(s.actors).filter(Objects::nonNull).map(ServerReplay::id).toList(),"center_x",s.center));
        return out;
    }
}
