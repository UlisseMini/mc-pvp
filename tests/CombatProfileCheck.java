import lab.CombatProfile;

public final class CombatProfileCheck {
    public static void main(String[] args){
        check(CombatProfile.parse(new String[0],0).vanilla());
        check(new CombatProfile(1,1,10).sprintVertical()==.1&&new CombatProfile(1,1,10).vanilla());
        CombatProfile housing=new CombatProfile(1,1,9,.07);
        check(housing.freshVertical(.46,true)==.46+(.07-.1));
        check(housing.freshVertical(.36,false)==.36);
        check(new CombatProfile(1,.95,9,.07).freshVertical(.46,true)==(.46+(.07-.1))*.95);
        check(CombatProfile.VANILLA.freshVertical(.46,true)==.46);
        check(CombatProfile.parse(new String[]{"sprint_vertical=.07"},0).sprintVertical()==.07);
        check(CombatProfile.parse(new String[]{"combat_sprint_vertical=.07"},0).sprintVertical()==.07);
        java.util.Properties hp=new java.util.Properties();hp.setProperty("combat_sprint_vertical",".07");
        check(CombatProfile.fromOverrides(hp,CombatProfile.VANILLA).sprintVertical()==.07);
        CombatProfile p=CombatProfile.parse(new String[]{"kb_horizontal=0.95","kb_vertical=0.9","hit_ticks=9"},0);
        java.util.Properties overrides=new java.util.Properties();
        check(CombatProfile.fromOverrides(overrides,p).equals(p));
        overrides.setProperty("combat_hit_ticks","8");
        CombatProfile partial=CombatProfile.fromOverrides(overrides,p);
        check(partial.horizontal()==.95&&partial.vertical()==.9&&partial.freshTicks()==8);
        overrides.setProperty("combat_kb_horizontal","1.0");overrides.setProperty("combat_kb_vertical","1.0");overrides.setProperty("combat_hit_ticks","10");
        check(CombatProfile.fromOverrides(overrides,p).vanilla());
        check(!p.vanilla()&&p.freshTicks()==9&&p.horizontal()==.95&&p.vertical()==.9);
        for(String invalid:new String[]{"hit_ticks=0","hit_ticks=21","hit_ticks=9.5","kb_horizontal=NaN","kb_vertical=Infinity","kb_vertical=-1","oops=1","sprint_vertical=NaN","sprint_vertical=-.1","sprint_vertical=1.1"}){
            boolean rejected=false;try{CombatProfile.parse(new String[]{invalid},0);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        }
        boolean rejected=false;try{CombatProfile.parse(new String[]{"hit_ticks=8","hit_ticks=9"},0);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        rejected=false;try{CombatProfile.parse(new String[]{"sprint_vertical=.07","combat_sprint_vertical=.1"},0);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        System.out.println("PASS CombatProfile (defaults, bounds, immutable values, duplicate rejection)");
    }
    static void check(boolean b){if(!b)throw new AssertionError();}
}
