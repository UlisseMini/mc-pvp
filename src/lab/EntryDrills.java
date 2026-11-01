package lab;

import java.util.*;

/** Repeated ordinary rod-diamond approaches with controlled setup, never mid-fight movement edits. */
public final class EntryDrills {
    static final Map<String,Session> sessions=new HashMap<>();
    static final class Session {
        String name,preset,bot;int rounds;long next;double botPing;
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
        Referee.message(sender,"§bDrills enabled: "+s.preset+" vs "+s.bot+". Five seconds after the first sword hit; /stop exits. Stand ready during the countdown.");
    }
    static void cancel(String name){sessions.remove(name);}
    static void tick()throws Exception{
        long now=System.currentTimeMillis();
        for(Session s:new ArrayList<>(sessions.values())){
            Object player=Referee.player(s.name);if(player==null){cancel(s.name);continue;}
            if(Referee.find(s.name)!=null||now<s.next||!(Boolean)R.call(player,"isEntityAlive"))continue;
            String bot=Referee.freeBot(Set.of());if(bot==null)continue;
            double distance=gap(s.preset,s.rounds);s.rounds++;
            ServerReplay.close(s.name,false);
            try{Referee.start(s.name,bot,"human",s.bot,Referee.preferredPing(s.name),s.botPing,"drill-"+System.nanoTime(),12000,"rod_diamond",new Properties(),distance,s.rounds,s.preset);}
            catch(Exception e){cancel(s.name);Referee.Match m=Referee.find(s.name);if(m!=null)Referee.finish(m,"drill_setup_failed");Referee.message(player,"§cDrill setup failed: "+e.getMessage());}
        }
    }
    static void finished(Referee.Match m,String reason){
        Session s=sessions.get(m.a);if(s==null||m.drillRound==0)return;
        if(!Set.of("drill_completed","death","scenario_completed").contains(reason)){cancel(m.a);return;}
        s.botPing=m.pingB;s.next=System.currentTimeMillis()+1200;
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
