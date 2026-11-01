package lab;

import java.util.*;

/** Repeated ordinary rod-diamond approaches with controlled setup, never mid-fight movement edits. */
public final class EntryDrills {
    static final Map<String,Session> sessions=new HashMap<>();
    static final class Session {
        String name,preset,bot,slot;int rounds,arena;double botPing;
        Session(String n,String p,String b,double ping){name=n;preset=p;bot=b;botPing=ping;}
    }
    static double gap(String preset,int round){
        return switch(preset){case "near"->4.5;case "medium"->6;case "far"->9;case "mixed"->new double[]{4.5,6,9}[round%3];default->throw new IllegalArgumentException("Choose near, medium, far, or mixed.");};
    }
    static void command(Object sender,String[] args)throws Exception{
        String name=Referee.safeName(sender);
        if(Referee.isBot(name)||Referee.player(name)==null)throw new IllegalArgumentException("Use /drill in your Minecraft client.");
        if(args.length==1){
            Referee.message(sender,"§bEntry drills: rod diamond vs wtap; automatic repeats. Gaps are player-center distances. /drill stop exits.");
            for(String preset:List.of("near","medium","far","mixed"))Referee.button(sender,preset+" — "+(preset.equals("mixed")?"cycle 4.5 / 6 / 9 blocks":gap(preset,0)+" blocks"),"/drill "+preset);
            return;
        }
        if(args[1].equals("stop")){cancel(name);Referee.Match m=Referee.find(name);if(m!=null)Referee.finish(m,"stopped");Referee.message(sender,"§bDrills stopped.");return;}
        if(args.length>3)throw new IllegalArgumentException("/drill near|medium|far|mixed [bot]");
        gap(args[1],0);String bot=args.length==3?args[2]:"wtap";Referee.loadPolicies();
        if(!Referee.policies.containsKey(bot))throw new IllegalArgumentException("Unknown bot. /bots lists them.");
        if(Referee.find(name)!=null)throw new IllegalArgumentException("Use /stop to finish your current duel first.");
        if(!(Boolean)R.call(sender,"isEntityAlive"))throw new IllegalArgumentException("Respawn first.");
        Session s=new Session(name,args[1],bot,Referee.lastPings.getOrDefault(name,new double[]{0,0})[0]);sessions.put(name,s);
        Referee.message(sender,"§bDrills enabled: "+s.preset+" vs "+s.bot+". Reset five seconds after the first sword hit, then a one-second countdown. /stop exits.");
    }
    static void cancel(String name){if(sessions.remove(name)!=null&&Referee.player(name)!=null)Referee.vanilla("title "+name+" clear");}
    static int arena(String name){Session s=sessions.get(name);return s==null?0:s.arena;}
    static boolean sameArena(Referee.Match m){Session s=sessions.get(m.a);return s!=null&&m.drillRound>1&&m.b.equals(s.slot)&&m.arena==s.arena;}
    static void resetPlayers(Referee.Match m,Object a,Object b){
        double center=4096.0*(m.arena+1);
        Referee.resetRoundPosition(a,center-m.startGap/2,4,0,270);
        Referee.resetRoundPosition(b,center+m.startGap/2,4,0,90);
    }
    static void tick()throws Exception{
        for(Session s:new ArrayList<>(sessions.values())){
            Object player=Referee.player(s.name);if(player==null){cancel(s.name);continue;}
            if(Referee.find(s.name)!=null||!(Boolean)R.call(player,"isEntityAlive"))continue;
            Object previous=s.slot==null?null:Referee.player(s.slot);
            String bot=previous!=null&&Referee.find(s.slot)==null&&(Boolean)R.call(previous,"isEntityAlive")?s.slot:Referee.freeBot(Set.of());if(bot==null)continue;
            double distance=gap(s.preset,s.rounds);s.rounds++;
            ServerReplay.close(s.name,false);
            try{Referee.start(s.name,bot,"human",s.bot,Referee.preferredPing(s.name),s.botPing,"drill-"+System.nanoTime(),12000,"rod_diamond",new Properties(),distance,s.rounds,s.preset);}
            catch(Exception e){cancel(s.name);Referee.Match m=Referee.find(s.name);if(m!=null)Referee.finish(m,"drill_setup_failed");Referee.message(player,"§cDrill setup failed: "+e.getMessage());}
        }
    }
    static boolean finished(Referee.Match m,String reason){
        Session s=sessions.get(m.a);if(s==null||m.drillRound==0)return false;
        if(!Set.of("drill_completed","death","scenario_completed").contains(reason)){cancel(m.a);return false;}
        s.botPing=m.pingB;s.slot=m.b;s.arena=m.arena;
        // The next round starts at the end of this server tick. Keep living
        // participants in the arena instead of sending them through the lobby.
        Object a=Referee.player(m.a),b=Referee.player(m.b);
        boolean keep=a!=null&&b!=null&&(Boolean)R.call(a,"isEntityAlive")&&(Boolean)R.call(b,"isEntityAlive");
        if(!keep)s.slot=null; // A death/lobby return needs the ordinary kit and tracking setup after respawn.
        return keep;
    }
    static void countdown(Referee.Match m,long now){
        if(m.drillRound==0)return;
        int tenths=(int)Math.max(0,Math.min(10,(m.start-now+99)/100));
        if(tenths==m.drillCountdown)return;
        boolean first=m.drillCountdown<0;m.drillCountdown=tenths;
        for(String who:List.of(m.a,m.b)){
            if(Referee.isBot(who)||Referee.player(who)==null)continue;
            if(first||tenths==0){
                Referee.vanilla("title "+who+" times 0 "+(tenths==0?8:6)+" "+(tenths==0?4:0));
                Referee.vanilla("title "+who+" subtitle "+Json.encode(Map.of("text",tenths==0?"":"Round "+m.drillRound+" · "+m.startGap+" blocks","color","gray")));
            }
            Referee.vanilla("title "+who+" title "+Json.encode(Map.of("text",tenths==0?"GO!":String.format(Locale.ROOT,"%.1f",tenths/10.0),"color",tenths==0?"green":"yellow","bold",true)));
        }
        Referee.append(m.replay,Map.of("type","drill_countdown","time",now,"combat_start",m.start,"tenths",tenths));
    }
    static void holdStart(Referee.Match m,Object a,Object b){
        double center=4096.0*(m.arena+1);
        for(int side=0;side<2;side++){
            Object p=side==0?a:b;double x=center+(side==0?-1:1)*m.startGap/2;
            Object hook=R.get(p,"fishEntity");if(hook!=null){R.call(hook,"setDead");R.set(p,"fishEntity",null);}
            if(Math.abs(R.num(p,"posX")-x)>.02||Math.abs(R.num(p,"posY")-4)>.02||Math.abs(R.num(p,"posZ"))>.02){
                Referee.teleport(p,x,4,0);R.set(p,"motionX",0.0);R.set(p,"motionY",0.0);R.set(p,"motionZ",0.0);
            }
        }
    }
}
