package lab;

import java.util.*;

/** Private packet-only actors: never registered as live players/world entities and never run combat. */
public final class ServerReplay {
    static final Map<String,Session> sessions=new LinkedHashMap<>();
    static final Set<String> floorChunks=new HashSet<>();
    static final String USAGE="/replay [match-id] | menu | pov server/human/bot | ghost <view/self/opponent> on/off | combos [page] | event prev/next | tick prev/next | pause/play | back/forward [seconds] | seek <seconds> | speed <factor> | camera a/b/free | stop";
    static final class Session {
        final String viewer;
        final ReplayData data;
        final ReplayTimeline clock;
        final List<ReplayCombos.Combo> combos;
        final Object[] actors=new Object[2],hooks=new Object[2];
        final int[] hookRecordedId={-1,-1},held={-1,-1},swing={-1,-1};
        final int arena;
        final double center;
        final long readyAt;
        int eventIndex,stepIndex=-1;
        boolean stepIsTick;
        String stepLabel="";
        Map<String,Object> stepFrame=Map.of();
        final Deque<Step> pendingSteps=new ArrayDeque<>();
        String camera="free",source="server",lastEvent="";final Set<String> ghosts=new LinkedHashSet<>();
        int ghostPreset,savedSlot;Object savedInventory;long lastToolbar;boolean viewMissing;
        long eventUntil;
        boolean spawned,ended,startPlaying=true;
        Session(String viewer,ReplayData data,int arena,long now){
            this.viewer=viewer;this.data=data;this.arena=arena;center=4096.0*(arena+1);
            combos=ReplayCombos.find(data.events,ReplayData.string(data.result,"a","a"),ReplayData.string(data.result,"b","b"),data.start,"boxing".equals(data.result.get("mode")));
            readyAt=now+2000;clock=new ReplayTimeline(data.duration,readyAt);clock.pause(true,readyAt);
        }
    }
    private record Step(boolean tick,boolean next){}
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
        String name=MatchLabels.profileName(label);
        Object actor=R.construct("net.minecraft.entity.player.EntityPlayerMP",Referee.server,world,
            R.construct("com.mojang.authlib.GameProfile",uuid,name),R.construct("net.minecraft.server.management.ItemInWorldManager",world));
        R.call(actor,"setCustomNameTag",label);return actor;
    }
    static void start(Object viewer,String match)throws Exception {
        String who=Referee.safeName(viewer);
        if(Referee.find(who)!=null)throw new IllegalArgumentException("Finish your duel or use /stop before watching a replay.");
        if(!(Boolean)R.call(viewer,"isEntityAlive"))throw new IllegalArgumentException("Respawn first, then use /replay.");
        ReplayData data=new ReplayData(match); // Validate before replacing an existing replay.
        close(who,false);Referee.watching.remove(who);
        int arena=-2;while(inUse(arena))arena--;
        Object world=R.get(viewer,"worldObj");
        prepareStage(world,data,4096.0*(arena+1));Referee.clearBox(world,arena);SumoArena.clear(world,arena);
        if("sumo".equals(data.result.get("mode"))){
            double size=ReplayData.number(ReplayData.map(data.result.get("sumo_arena")),"diameter",SumoFootprint.LEGACY);
            SumoArena.build(world,arena,DuelSettings.sumoDiameter(Integer.toString((int)size)));
        }
        if("diamond_box".equals(data.result.get("mode")))Referee.prepareBox(world,arena);
        Session s=new Session(who,data,arena,System.currentTimeMillis());sessions.put(who,s);
        for(int i=0;i<2;i++)s.actors[i]=actor(viewer,data.label(i==0?"a":"b"),UUID.randomUUID());
        ReplayToolbar.equip(viewer,s);R.call(viewer,"setSpectatingEntity",viewer);Referee.configureProxy(who,0);
        Map<String,Object> first=data.frame(0),a=ReplayData.map(first.get("a")),b=ReplayData.map(first.get("b"));
        Referee.teleport(viewer,s.center-data.originalCenter+(ReplayData.number(a,"posX",data.originalCenter)+ReplayData.number(b,"posX",data.originalCenter))/2,"sumo".equals(data.result.get("mode"))?SumoArena.TOP_Y+5:9,
            (ReplayData.number(a,"posZ",0)+ReplayData.number(b,"posZ",0))/2+6);
        Referee.message(viewer,"§6Replay "+match+" §7— "+data.label("a")+" vs "+data.label("b")+". §fViewpoint: server. §7Use the compass to switch recorded views.");
        for(String side:List.of("a","b"))Referee.message(viewer,"§7"+data.label(side)+" POV: "+(data.pov.available(side)?data.pov.quality.get(side):"not recorded"));
        for(String warning:data.pov.warnings)Referee.message(viewer,"§e"+warning);
        if(!data.posesRecorded)Referee.message(viewer,"§7Older recording: look direction/equipment are approximated; rod flight and precise block poses were not recorded.");
        controls(viewer);
        combos(viewer,s,1);
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
        Referee.message(p,"§6Replay controls §7— use the hotbar, or /replay menu. Chat shortcuts:");
        controlRow(p,"Time",new String[][]{{"-5s","back 5"},{"-1s","back 1"},{"Pause","pause"},{"Play","play"},{"+1s","forward 1"},{"+5s","forward 5"},{"Go to...","seek ","suggest_command"}});
        controlRow(p,"Inspect",new String[][]{{"Prev event","event prev"},{"Next event","event next"},{"Prev tick","tick prev"},{"Next tick","tick next"}});
        controlRow(p,"Speed",new String[][]{{"0.1x","speed 0.1"},{"0.25x","speed 0.25"},{"0.5x","speed 0.5"},{"1x","speed 1"},{"2x","speed 2"},{"Custom...","speed ","suggest_command"}});
        controlRow(p,"View",new String[][]{{"Follow A","a"},{"Follow B","b"},{"Fly freely","free"},{"Combos","combos"},{"Exit replay","stop"}});
        controlRow(p,"POV",new String[][]{{"Server","pov server"},{"Human","pov human"},{"Bot","pov bot"},{"Settings","menu"}});
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
    static void combos(Object viewer,Session s,int page){
        int pages=Math.max(1,(s.combos.size()+ReplayCombos.PAGE_SIZE-1)/ReplayCombos.PAGE_SIZE);
        if(page<1||page>pages)throw new IllegalArgumentException("Use /replay combos <page> between 1 and "+pages+".");
        if(s.combos.isEmpty()){
            Referee.message(viewer,"§6Combos §7— no runs of 3+ unanswered melee hits in this replay.");return;
        }
        int from=(page-1)*ReplayCombos.PAGE_SIZE,to=Math.min(s.combos.size(),from+ReplayCombos.PAGE_SIZE);
        Referee.message(viewer,"§6Combos "+(from+1)+"–"+to+" of "+s.combos.size()+" §7— click to watch, with a 0.75s lead-in. /replay combos shows this again.");
        Referee.message(viewer,"§8Three or more unanswered melee hits; rod contacts ignored. "+("boxing".equals(s.data.result.get("mode"))?"Boxing uses accepted hits.":"HP-damage hits when recorded; otherwise accepted hits."));
        for(int i=from;i<to;i++){
            ReplayCombos.Combo c=s.combos.get(i);String side=c.side==0?"a":"b",other=c.side==0?"b":"a";
            String hover="Watch "+s.data.label(side)+" land "+c.hits+" unanswered melee hits ("+ReplayCombos.timestamp(c.firstMs)+"–"+
                ReplayCombos.timestamp(c.lastMs)+"). Starts at "+ReplayCombos.timestamp(c.seekMs())+"; keeps your replay speed.";
            Map<String,Object> row=Map.of("text","["+(i+1)+"] "+ReplayCombos.timestamp(c.firstMs)+" ","color","gray",
                "clickEvent",Map.of("action","run_command","value","/replay combo "+s.data.id+" "+(i+1)),
                "hoverEvent",Map.of("action","show_text","value",hover),
                "extra",List.of(Map.of("text",s.data.label(side),"color",c.side==0?"aqua":"gold"),
                    Map.of("text"," comboed ","color","gray"),Map.of("text",s.data.label(other),"color",c.side==0?"gold":"aqua"),
                    Map.of("text",": ","color","gray"),Map.of("text",c.hits+" hits","color","yellow","bold",true)));
            Referee.vanilla("tellraw "+Referee.safeName(viewer)+" "+Json.encode(row));
        }
        if(pages>1){
            List<String[]> buttons=new ArrayList<>();
            if(page>1)buttons.add(new String[]{"Previous","combos "+(page-1)});
            if(page<pages)buttons.add(new String[]{"Next","combos "+(page+1)});
            controlRow(viewer,"Combo page "+page+"/"+pages,buttons.toArray(String[][]::new));
        }
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
                Referee.button(viewer,match+" ["+row.get("mode")+"] "+ReplayData.label(row,"a")+" vs "+ReplayData.label(row,"b"),"/replay "+match);}
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
        else if(action.equals("play")&&args.length==2){clearStep(s);s.startPlaying=true;if(s.clock.position()>=s.data.duration)seek(s,0,false,now);s.clock.pause(false,now);}
        else if((action.equals("event")||action.equals("tick"))&&args.length==3&&Set.of("prev","next").contains(args[2])){
            s.startPlaying=false;s.clock.pause(true,now);
            if(s.pendingSteps.size()<64)s.pendingSteps.add(new Step(action.equals("tick"),args[2].equals("next")));
            applySteps(viewer,s,now);
        }
        else if(action.equals("controls")&&args.length==2)controls(viewer);
        else if(action.equals("menu")&&args.length==2){if(PovLink.subscribed(who))PovLink.send(viewer,Map.of("type","replay_menu","version",1));else{controls(viewer);ghostMenu(viewer,s);}}
        else if(action.equals("control")&&args.length==4&&Set.of("left","right").contains(args[3])){ReplayToolbar.use(viewer,s,Integer.parseInt(args[2]),args[3].equals("right"));}
        else if(action.equals("pov")&&args.length==3){
            String source=s.data.pov.source(args[2]);if(!s.data.pov.available(source))throw new IllegalArgumentException("That client POV was not recorded. Server POV is still available.");
            if(!source.equals(s.source))clearStep(s);s.data.pov.steps(source);
            s.source=source;s.camera=source.equals("server")?"free":source;Arrays.fill(s.held,-1);if(s.spawned)camera(viewer,s);
            Referee.message(viewer,"§bViewpoint: "+(source.equals("server")?"server":s.data.label(source)+" client")+". §7Settings lets you follow either player or fly freely.");
        }
        else if(action.equals("ghost")){ghostCommand(viewer,s,args);}
        else if(action.equals("combos")&&(args.length==2||args.length==3))combos(viewer,s,args.length==3?Integer.parseInt(args[2]):1);
        else if(action.equals("combo")&&args.length==4){
            if(!s.data.id.equals(args[2]))throw new IllegalArgumentException("That combo belongs to "+args[2]+". Open that replay first; /replay combos shows this replay's combos.");
            int index=Integer.parseInt(args[3]);
            if(index<1||index>s.combos.size())throw new IllegalArgumentException("Unknown combo. Use /replay combos.");
            seek(s,s.combos.get(index-1).seekMs()/1000.0,false,now);s.startPlaying=true;
            if(s.spawned)s.clock.pause(false,now);
        }
        else if(action.equals("speed")&&args.length==3)s.clock.speed(Double.parseDouble(args[2]),now);
        else if(action.equals("seek")&&args.length==3){
            seek(s,Double.parseDouble(args[2]),args[2].startsWith("+")||args[2].startsWith("-"),now);
        }else if(Set.of("back","forward").contains(action)&&(args.length==2||args.length==3)){
            double seconds=args.length==3?Double.parseDouble(args[2]):5;
            if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Use /replay "+action+" [seconds], with a nonnegative number (default: 5).");
            seek(s,action.equals("back")?-seconds:seconds,true,now);
        }else if(action.equals("camera")&&args.length==3&&Set.of("a","b","free").contains(args[2])){s.camera=args[2];if(s.spawned)camera(viewer,s);
        }else if(Set.of("a","b","free").contains(action)&&args.length==2){
            s.camera=action;if(s.spawned)camera(viewer,s);
        }else throw new IllegalArgumentException(USAGE);
    }
    static void seek(Session s,double seconds,boolean relative,long now){
        s.clock.seekSeconds(seconds,relative,now);clearStep(s);resetPlaybackEvents(s);
    }
    static void resetPlaybackEvents(Session s){
        s.eventIndex=0;s.ended=false;s.lastEvent="";s.eventUntil=0;
        while(s.eventIndex<s.data.events.size()&&ReplayData.number(s.data.events.get(s.eventIndex),"time",0)<=s.data.start+s.clock.position())s.eventIndex++;
        Arrays.fill(s.swing,-1);
    }
    private static void clearStep(Session s){s.stepIndex=-1;s.stepLabel="";s.stepFrame=Map.of();s.pendingSteps.clear();}
    private static void applySteps(Object viewer,Session s,long now){
        if(s.pendingSteps.isEmpty())return;
        var future=s.data.pov.steps(s.source);
        if(!future.isDone()){s.stepLabel="Loading "+s.data.label(s.source)+" events…";return;}
        ReplaySteps index;
        try{index=future.join();}catch(java.util.concurrent.CompletionException e){
            clearStep(s);s.stepLabel="Could not read "+s.source+" events: "+e.getCause().getMessage();
            Referee.message(viewer,"§e"+s.stepLabel);return;
        }
        while(!s.pendingSteps.isEmpty()){
            Step action=s.pendingSteps.removeFirst();var points=index.points(action.tick());
            String kind=action.tick()?"tick":"event";
            int i=ReplaySteps.adjacent(points,s.clock.position(),s.stepIsTick==action.tick()?s.stepIndex:-1,action.next());
            if(i<0||i>=points.size()){
                String warning=points.isEmpty()?"No recorded "+kind+"s in this view.":"No "+(action.next()?"later ":"earlier ")+kind+".";
                Referee.message(viewer,"§7"+warning);if(s.stepIndex<0)s.stepLabel=warning;continue;
            }
            ReplaySteps.Point mark=points.get(i);s.clock.seek(mark.ms(),now);resetPlaybackEvents(s);
            s.stepIndex=i;s.stepIsTick=action.tick();s.stepFrame=mark.frame();
            s.stepLabel=String.format(Locale.ROOT,"%s %d/%d · %.3fs · %s",action.tick()?"Tick":"Event",i+1,points.size(),mark.ms()/1000.,mark.label())+(index.partial?" · partial capture":"");
        }
    }
    static void ghostMenu(Object viewer,Session s){
        for(String source:List.of("server","a","b"))if(s.data.pov.available(source))for(String side:List.of("a","b")){
            String key=source+":"+side;Referee.button(viewer,(s.ghosts.contains(key)?"[ON] ":"[off] ")+s.data.label(side)+" as seen by "+(source.equals("server")?"server":s.data.label(source)),"/replay ghost "+key+" "+(s.ghosts.contains(key)?"off":"on"));}
    }
    static void ghostCommand(Object viewer,Session s,String[] args){
        if(args.length==3&&args[2].equals("preset")){
            s.ghosts.clear();s.ghostPreset=(s.ghostPreset+1)%4;
            for(String source:List.of("a","b"))if(s.data.pov.available(source)&&(s.ghostPreset==3||s.ghostPreset==(source.equals("a")?1:2)))for(String side:List.of("a","b"))s.ghosts.add(source+":"+side);
            Referee.message(viewer,"§bGhosts: "+(s.ghostPreset==0?"off":s.ghostPreset==3?"all client views":s.data.label(s.ghostPreset==1?"a":"b")+" view"));return;
        }
        if(args.length==3&&args[2].equals("off")){s.ghosts.clear();return;}
        if(args.length!=4||!Set.of("on","off").contains(args[3]))throw new IllegalArgumentException("/replay ghost human|bot|human-self|human-opponent|bot-self|bot-opponent on/off; or /replay ghost off");
        String spec=args[2];List<String> keys=new ArrayList<>();
        if(spec.matches("(server|a|b):[ab]"))keys.add(spec);
        else{String[] parts=spec.split("-");String source=s.data.pov.source(parts[0]);if(parts.length==1){keys.add(source+":a");keys.add(source+":b");}
            else if(parts.length==2&&Set.of("self","opponent").contains(parts[1])&&!source.equals("server"))keys.add(source+":"+(parts[1].equals("self")?source:source.equals("a")?"b":"a"));
            else throw new IllegalArgumentException("Use a view name, or human-self / human-opponent / bot-self / bot-opponent.");}
        for(String key:keys){if(!s.data.pov.available(key.split(":")[0]))throw new IllegalArgumentException("That client POV was not recorded.");if(args[3].equals("on"))s.ghosts.add(key);else s.ghosts.remove(key);}
    }
    static void sendView(Object viewer,Session s,long now,long ms){
        if(!PovLink.subscribed(s.viewer))return;
        Map<String,Object> views=new LinkedHashMap<>(),next=new LinkedHashMap<>();
        for(String source:List.of("server","a","b"))if(s.data.pov.available(source)){
            Map<String,Object> frame=compactFrame(viewFrame(s,source,ms));views.put(source,frame);
            next.put(source,s.clock.paused()?frame:compactFrame(s.data.pov.at(source,Math.min(s.data.duration,ms+50*s.clock.speed()))));}
        Map<String,Object> data=new LinkedHashMap<>();data.put("type","replay_view");data.put("version",1);data.put("active",true);data.put("match",s.data.id);data.put("start_ms",s.data.start);data.put("server_ms",now);data.put("time_ms",ms);data.put("duration_ms",s.data.duration);
        data.put("step_label",s.stepLabel);data.put("paused",s.clock.paused());data.put("speed",s.clock.speed());data.put("source",s.source);data.put("camera",s.camera);data.put("ghosts",new ArrayList<>(s.ghosts));data.put("views",views);data.put("next",next);data.put("view_missing",s.viewMissing);
        data.put("offset_x",s.center-s.data.originalCenter);data.put("actor_ids",Arrays.stream(s.actors).map(ServerReplay::id).toList());data.put("availability",s.data.pov.info());data.put("labels",List.of(s.data.label("a"),s.data.label("b")));
        data.put("reach",List.of(ReplayData.number(s.data.result,"attack_reach_a",3),ReplayData.number(s.data.result,"attack_reach_b",3)));PovLink.send(viewer,data);
    }
    private static Map<String,Object> viewFrame(Session s,String source,long ms){
        return source.equals("server")&&!s.stepFrame.isEmpty()?s.stepFrame:s.data.pov.at(source,ms);
    }
    static Map<String,Object> compactFrame(Map<String,Object> frame){if(frame.isEmpty())return Map.of();Map<String,Object> out=new LinkedHashMap<>();
        for(String side:List.of("a","b")){Map<String,Object> p=ReplayData.map(frame.get(side)),v=new LinkedHashMap<>();
            for(String k:List.of("posX","posY","posZ","logicalX","logicalY","logicalZ","rotationYaw","rotationPitch","rotationYawHead","renderYawOffset","swingProgress","swingProgressInt","isSwingInProgress","hurtTime","limbSwing","limbSwingAmount","eye_height","box","border","sneaking","blocking","held_item_id","health"))if(p.containsKey(k))v.put(k,p.get(k));out.put(side,v);}return out;
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
            boolean equipped=!Set.of("boxing","sumo").contains(s.data.result.get("mode"));
            for(int slot=1;slot<=4;slot++)send(viewer,R.construct("net.minecraft.network.play.server.S04PacketEntityEquipment",id(actor),slot,equipped?namedItem(armor[slot-1]):null));
        }
        send(viewer,R.construct("net.minecraft.network.play.server.S18PacketEntityTeleport",actor));
        send(viewer,R.construct("net.minecraft.network.play.server.S19PacketEntityHeadLook",actor,(byte)(R.num(actor,"rotationYawHead")*256/360)));
        send(viewer,R.construct("net.minecraft.network.play.server.S1CPacketEntityMetadata",id(actor),R.call(actor,"getDataWatcher"),true));
        int held=(int)ReplayData.number(p,"held_item_id","sumo".equals(s.data.result.get("mode"))?0:namedItemId("boxing".equals(s.data.result.get("mode"))?"diamond_sword":"wooden_sword"));
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
            Object viewer=Referee.player(s.viewer);if(viewer==null){close(s.viewer,false);continue;}
            if(now<s.readyAt)continue;
            applySteps(viewer,s,now);
            boolean spawn=!s.spawned;
            if(spawn){s.clock.seek(s.clock.position(),now);s.clock.pause(!s.startPlaying,now);}
            long ms=s.clock.advance(now);Map<String,Object> serverFrame=viewFrame(s,"server",ms),frame=s.source.equals("server")?serverFrame:viewFrame(s,s.source,ms);s.viewMissing=frame.isEmpty();
            if(frame.isEmpty()){if(spawn)frame=serverFrame;else{sendView(viewer,s,now,ms);Referee.overlay(viewer,"§e"+s.stepLabel+" §7· no recorded pose here");continue;}}
            frame=new LinkedHashMap<>(frame);frame.put("health_a",serverFrame.get("health_a"));frame.put("health_b",serverFrame.get("health_b"));
            for(int i=0;i<2;i++)pose(s,viewer,i,frame,spawn);
            if(spawn){s.spawned=true;camera(viewer,s);}
            sendView(viewer,s,now,ms);
            while(s.eventIndex<s.data.events.size()&&ReplayData.number(s.data.events.get(s.eventIndex),"time",0)<=s.data.start+ms){
                Map<String,Object> e=s.data.events.get(s.eventIndex++);String type=ReplayData.string(e,"type","");
                int attacker=Objects.equals(e.get("attacker"),s.data.result.get("a"))?0:1;
                int victim=1-attacker;Object a=s.actors[attacker],b=s.actors[victim];
                if(type.equals("hit")){
                    if(!ReplayData.flag(e,"rod"))send(viewer,R.construct("net.minecraft.network.play.server.S0BPacketAnimation",a,0));
                    send(viewer,R.construct("net.minecraft.network.play.server.S19PacketEntityStatus",b,(byte)2));
                    s.lastEvent=s.data.label(attacker==0?"a":"b")+(ReplayData.flag(e,"rod")?" rod contact":ReplayData.flag(e,"damage_upgrade")?" damage upgrade":"sumo".equals(s.data.result.get("mode"))?" punch":" sword hit");s.eventUntil=now+700;
                }else if(type.equals("critical"))send(viewer,R.construct("net.minecraft.network.play.server.S0BPacketAnimation",b,4));
                else if(type.equals("rod_trick")){s.lastEvent="Rod trick: "+s.data.label(attacker==0?"a":"b");s.eventUntil=now+1000;}
                else if(type.equals("blocked_damage")){s.lastEvent=s.data.label(victim==0?"a":"b")+" blocked damage";s.eventUntil=now+700;}
            }
            if(!s.stepLabel.isEmpty())Referee.overlay(viewer,"§b"+(s.source.equals("server")?"Server":s.data.label(s.source))+" §7| §f"+s.stepLabel);
            else if(Referee.ticks%2==0)Referee.overlay(viewer,String.format(Locale.ROOT,"§6Replay %s §f%.2f/%.2fs §7%s%s §8| §b%s %.1f♥ §7vs §6%s %.1f♥%s",
                s.data.id+" ["+s.source+" POV]",ms/1000.0,s.data.duration/1000.0,s.clock.speedLabel(),s.clock.paused()?" PAUSED":"",s.data.label("a"),ReplayData.number(frame,"health_a",20)/2,
                s.data.label("b"),ReplayData.number(frame,"health_b",20)/2,now<s.eventUntil?" §8| §e"+s.lastEvent:""));
            if(ms>=s.data.duration&&!s.ended){s.ended=true;Referee.message(viewer,"§6Replay finished. §7/replay play to restart; /replay seek <seconds> to review; /stop to leave.");}
        }catch(Throwable e){e.printStackTrace();Object viewer=Referee.player(s.viewer);close(s.viewer,true);if(viewer!=null)Referee.message(viewer,"§cReplay stopped: "+e.getMessage());}
    }
    static boolean close(String who,boolean lobby){
        Session s=sessions.remove(who);if(s==null)return false;s.data.pov.close();Object viewer=Referee.player(who);
        if(viewer!=null){
            R.call(viewer,"setSpectatingEntity",viewer);
            for(Object actor:s.actors)if(actor!=null){send(viewer,R.construct("net.minecraft.network.play.server.S13PacketDestroyEntities",new int[]{id(actor)}));listPacket(viewer,actor,"REMOVE_PLAYER");}
            for(Object hook:s.hooks)if(hook!=null)send(viewer,R.construct("net.minecraft.network.play.server.S13PacketDestroyEntities",new int[]{id(hook)}));
            ReplayToolbar.restore(viewer,s);Referee.configureProxy(who,Referee.preferredPing(who));if(PovLink.subscribed(who))PovLink.send(viewer,Map.of("type","replay_view","version",1,"active",false));
            if(lobby)Referee.returnToLobby(viewer);
        }
        return true;
    }
    static List<Map<String,Object>> state(){
        List<Map<String,Object>> out=new ArrayList<>();
        for(Session s:sessions.values()){Map<String,Object> row=new LinkedHashMap<>(Map.of("viewer",s.viewer,"match",s.data.id,"time_ms",s.clock.position(),"duration_ms",s.data.duration,
            "paused",s.clock.paused(),"speed",s.clock.speed(),"camera",s.camera,"spawned",s.spawned,
            "actor_ids",Arrays.stream(s.actors).filter(Objects::nonNull).map(ServerReplay::id).toList(),"center_x",s.center));row.put("source",s.source);row.put("step_label",s.stepLabel);row.put("ghosts",new ArrayList<>(s.ghosts));row.put("availability",s.data.pov.info());out.add(row);}
        return out;
    }
}
