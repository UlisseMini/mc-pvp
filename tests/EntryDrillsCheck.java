package lab;
import java.util.*;

/** A repeat may keep the native entities only if both participants stayed alive in the arena. */
public final class EntryDrillsCheck {
    public static final class Player {
        final String name;boolean alive=true;
        Player(String name){this.name=name;}
        public String getName(){return name;}
        public boolean isEntityAlive(){return alive;}
    }
    public static final class Manager {public final List<Player> playerEntityList;Manager(Player...players){playerEntityList=Arrays.asList(players);}}
    public static final class Server {final Manager manager;Server(Player...players){manager=new Manager(players);}public Manager getConfigurationManager(){return manager;}}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        Player human=new Player("PracticeCheck"),bot=new Player("LabSlot0");Referee.server=new Server(human,bot);
        EntryDrills.Session session=new EntryDrills.Session(human.name,"mixed","wtap",80);EntryDrills.sessions.put(human.name,session);
        Referee.Match m=new Referee.Match();m.a=human.name;m.b=bot.name;m.drillRound=1;m.arena=2;m.pingB=80;
        check(!EntryDrills.sameArena(m),"first round requires normal setup");
        check(EntryDrills.finished(m,"drill_completed"),"living participants remain in arena");m.drillRound++;
        check(EntryDrills.sameArena(m),"normal repeat preserves the native entities");
        m.arena=1;check(!EntryDrills.sameArena(m),"changed arena requires normal setup");m.arena=2;
        m.b="LabSlot1";check(!EntryDrills.sameArena(m),"changed bot requires normal setup");m.b=bot.name;
        human.alive=false;check(!EntryDrills.finished(m,"death"),"death takes the ordinary respawn path");human.alive=true;m.drillRound++;
        check(!EntryDrills.sameArena(m),"respawn cannot reuse a spectator bot left in the lobby");
        check(EntryDrills.finished(m,"drill_completed"),"continuity is restored after the fresh round");m.drillRound++;
        check(EntryDrills.sameArena(m)&&session.botPing==80,"subsequent repeats retain bot and ping");
        EntryDrills.sessions.clear();Referee.server=null;
        System.out.println("PASS drill reuse: live repeats, changed arena/bot, death/respawn, restored continuity and ping");
    }
}
