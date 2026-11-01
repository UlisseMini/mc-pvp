import lab.CombatProfile;

public final class CombatProfileCheck {
    public static void main(String[] args){
        check(CombatProfile.parse(new String[0],0).vanilla());
        CombatProfile p=CombatProfile.parse(new String[]{"kb_horizontal=0.95","kb_vertical=0.9","hit_ticks=9"},0);
        check(!p.vanilla()&&p.freshTicks()==9&&p.horizontal()==.95&&p.vertical()==.9);
        for(String invalid:new String[]{"hit_ticks=0","hit_ticks=21","hit_ticks=9.5","kb_horizontal=NaN","kb_vertical=Infinity","kb_vertical=-1","oops=1"}){
            boolean rejected=false;try{CombatProfile.parse(new String[]{invalid},0);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        }
        boolean rejected=false;try{CombatProfile.parse(new String[]{"hit_ticks=8","hit_ticks=9"},0);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        System.out.println("PASS CombatProfile (defaults, bounds, immutable values, duplicate rejection)");
    }
    static void check(boolean b){if(!b)throw new AssertionError();}
}
