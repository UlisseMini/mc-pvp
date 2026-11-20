package lab;

import java.util.Properties;

public final class SumoCheck {
    public static final class Player {
        public Object fishEntity;public double posX,posY=12,posZ;
        float health=7;public String getName(){return "TestA";}
        public void setHealth(float value){health=value;}
    }
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        int cells=0;
        for(int x=-8;x<8;x++)for(int z=-8;z<8;z++)if(SumoArena.cell(x,z)){
            cells++;check(Math.abs(x)<=6&&Math.abs(z)<=6,"recorded extent");
            check(SumoArena.cell(-x,z)&&SumoArena.cell(x,-z)&&SumoArena.cell(z,x),"symmetric arena");
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
        var pinned=new Referee.Match();pinned.mode="sumo";pinned.startGap=8;
        Player left=new Player(),right=new Player();
        left.posX=4092.5;right.posX=4100.5;left.posZ=right.posZ=.5;
        // Enter through native countdown holding. Both centered players must stay put.
        EntryDrills.holdStart(pinned,left,right);
        var recorded=RecordedSumoMap.load("mmc-129");
        pinned.sumoMap=recorded;left.posX=right.posX=4096.5;left.posZ=-3.5;right.posZ=4.5;
        EntryDrills.holdStart(pinned,left,right);
        check(pinned.spawnYaw(true)==0&&pinned.spawnYaw(false)==180,"look along the recorded axis");
        var pair=BotMatchSettings.parse(new String[]{"map=mmc-221"},0,"test");
        check("mmc-221".equals(pair.a.getProperty("sumo_map"))&&pair.a.equals(pair.b),"both fighters share the map");
        for(String[] options:new String[][]{{"a.map=mmc-129"},{"map=mmc-129","diameter=10"},{"map=mmc-999"}}){
            rejected=false;try{BotMatchSettings.parse(options,0,"test");}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"invalid named map settings rejected");
        }
        check(cells==137,"recorded platform cell count");
        check(DuelSettings.sumoDiameter("13")==SumoArena.DIAMETER,"recorded layout selectable");
        check(SumoFootprint.state(-4,0,13)==656&&SumoFootprint.state(4,0,13)==656,"gold under both recorded spawns");
        var hash=java.security.MessageDigest.getInstance("SHA-256");
        for(int z=-8;z<8;z++)for(int x=-8;x<8;x++){int state=SumoFootprint.state(x,z,13);hash.update((byte)(state>>8));hash.update((byte)state);}
        check(java.util.HexFormat.of().formatHex(hash.digest()).equals("89f9197f91ca4b33e1e00ef751b22ab2ba476885608c528b51bfdb32cb6c123a"),"all recorded block IDs and metadata in oriented order");
        for(int diameter:new int[]{8,10,12,13,14,16}){
            var disk=new ExactTravel.Disk(4096,diameter);
            for(double x=-8.5;x<=8.5;x+=.125)for(double z=-8.5;z<=8.5;z+=.125){
                double px=x+4096;
                var box=new ExactTravel.Box(px-.3,12,z-.3,px+.3,13.8,z+.3);
                check(disk.supports(box)==SumoArena.supports(0,px,z,diameter),"model/server support");
            }
        }
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
