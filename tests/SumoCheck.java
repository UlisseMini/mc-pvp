package lab;

import java.util.Properties;

public final class SumoCheck {
    public static final class Player {
        float health=7;public String getName(){return "TestA";}
        public void setHealth(float value){health=value;}
    }
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        int cells=0;
        for(int x=-8;x<8;x++)for(int z=-8;z<8;z++)if(SumoArena.cell(x,z)){
            cells++;check(x>=-5&&x<5&&z>=-5&&z<5,"ten-block footprint");
            check(SumoArena.cell(-1-x,z)&&SumoArena.cell(x,-1-z)&&SumoArena.cell(z,x),"symmetric arena");
        }
        for(int diameter:new int[]{8,10,12,14,16}){
            int r=diameter/2;
            check(SumoArena.cell(-r,0,diameter)&&!SumoArena.cell(-r-1,0,diameter),"selectable disk extent");
            check(SumoArena.supports(0,4096+r+.29,0,diameter)&&!SumoArena.supports(0,4096+r+.31,0,diameter),"bounding box lip matches diameter");
            check(SumoArena.geometry(diameter,6).get("diameter").equals(diameter),"replay metadata");
        }
        check(DuelSettings.parse("sumo diameter=14".split(" "),0,"boxing",0,0,new Properties()).overrides.getProperty("sumo_diameter").equals("14"),"human option");
        boolean rejected=false;try{DuelSettings.sumoDiameter("9");}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"odd geometry rejected");
        rejected=false;try{BotMatchSettings.parse(new String[]{"a.diameter=12"},0,"test");}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"disk belongs to both seats");
        check(cells==80,"expected circular footprint");
        check(SumoArena.cell(-3,0)&&SumoArena.cell(3,0),"both spawns supported");
        check(SumoArena.outcome(12,12)==-1&&SumoArena.outcome(13,11.5)==-1,"ground, jumping, and edge tolerance");
        check(SumoArena.outcome(11.49,12)==2&&SumoArena.outcome(12,11.49)==1,"both ring-out directions");
        check(SumoArena.outcome(11,11)==0,"same tick falls draw");
        DuelSettings settings=DuelSettings.parse("/duel wtap sumo cps=15 ping=80".split(" "),2,"boxing",0,30,new Properties());
        check(settings.mode.equals("sumo")&&settings.botPing==80&&settings.humanPing==30,"sumo preserves custom duel settings and player ping");
        check(EntryDrills.COUNTDOWN_MS==2000,"two-second drills");
        Player p=new Player();Referee.server=new Object();Referee.Match m=new Referee.Match();m.id="sumo-test";m.a="TestA";m.b="TestB";m.start=0;Referee.matches.put(m.id,m);
        for(String mode:Referee.MODES){m.mode=mode;p.health=7;Referee.restoreHealth(p);check(p.health==(mode.equals("sumo")||mode.equals("boxing")?20:7),"health rule for "+mode);}
        Referee.matches.clear();p.health=7;Referee.restoreHealth(p);check(p.health==7,"lobby unaffected");
        check(Referee.errors==0,"native hook guards");
        System.out.println("PASS sumo geometry, ring-outs, draw, settings, health scope, two-second drills");
    }
}
