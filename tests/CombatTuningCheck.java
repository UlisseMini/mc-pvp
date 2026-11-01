package lab;

import java.nio.file.*;

/** Fake reflective bodies; tests the real match-scoped hook without Minecraft/network. */
public final class CombatTuningCheck {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    public static final class Player {
        final String name;boolean sprint;int sprintReads;
        double motionX=.2,motionY=.46,motionZ=.3;int hurtResistantTime,maxHurtResistantTime=18;
        Player(String n){name=n;}
        public String getName(){return name;}
        public boolean isSprinting(){sprintReads++;return sprint;}
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("combat-tuning-check");System.setProperty("lab.root",root.toString());System.setProperty("lab.role","server");
        var m=new Referee.Match();m.a="A";m.b="B";m.start=0;m.replay=root.resolve("test/events.jsonl");m.combat=new CombatProfile(1,1,9,.07);
        Referee.server=new Object();Referee.matches.put("test",m);Player a=new Player("A"),b=new Player("B");
        try{
            a.sprint=true;CombatTuning.before(a,b);a.sprint=false;m.ha++;CombatTuning.outgoing(b);
            check(b.motionY==.46+(.07-.1),"snapshot BEFORE native sprint consumption controls lift");check(b.motionX==.2&&b.motionZ==.3,"native horizontal preserved");CombatTuning.after();
            b.motionY=.36;CombatTuning.before(a,b);m.ha++;CombatTuning.outgoing(b);check(b.motionY==.36,"normal fresh unchanged");CombatTuning.after();
            b.motionY=.46;b.hurtResistantTime=18;a.sprint=true;CombatTuning.before(a,b);m.ha++;CombatTuning.outgoing(b);check(b.motionY==.46,"damage upgrade not adjusted");CombatTuning.after();b.hurtResistantTime=0;
            CombatTuning.before(a,b);CombatTuning.outgoing(b);check(b.motionY==.46,"rejected attack cannot adjust");CombatTuning.after();
            CombatTuning.before(a,new Player("other"));m.ha++;CombatTuning.outgoing(b);check(b.motionY==.46,"other victim not match-scoped");CombatTuning.after();
            m.combat=CombatProfile.VANILLA;int calls=a.sprintReads;CombatTuning.before(a,b);m.ha++;CombatTuning.outgoing(b);check(b.motionY==.46&&a.sprintReads==calls,"vanilla no-op skips even new sprint read");CombatTuning.after();
            m.combat=new CombatProfile(1,.95,9,.07);CombatTuning.before(a,b);m.ha++;CombatTuning.outgoing(b);check(b.motionY==(.46+(.07-.1))*.95,"replace lift before vertical scaling");CombatTuning.after();
            m.finish=1;double prior=b.motionY;CombatTuning.before(a,b);m.ha++;CombatTuning.outgoing(b);check(b.motionY==prior,"finished match not adjusted");
        }finally{CombatTuning.after();Referee.matches.clear();Referee.server=null;}
        System.out.println("PASS CombatTuningCheck "+checks+" checks (offline fake bodies, no native execution)");
    }
}
