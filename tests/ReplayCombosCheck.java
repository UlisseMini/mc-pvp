package lab;

import java.util.*;

public final class ReplayCombosCheck {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static Map<String,Object> hit(int side,int index,long time){
        return new LinkedHashMap<>(Map.of("type","hit","attacker",side==0?"human":"bot","target",side==0?"bot":"human",
            "time",time,"hit_index",index,"rod",false));
    }
    static Map<String,Object> damage(int side,int index,double loss){
        return Map.of("type","melee_damage","attacker",side==0?"human":"bot","target",side==0?"bot":"human",
            "hit_index",index,"health_loss",loss);
    }
    static List<ReplayCombos.Combo> combos(List<Map<String,Object>> events){return ReplayCombos.find(events,"human","bot",1000,false);}
    public static void main(String[] args){
        List<Map<String,Object>> events=new ArrayList<>();int index=0;
        for(int side:new int[]{0,0,0,1,0,0,0,0,1,1,1})events.add(hit(side,++index,1000+index*100));
        List<ReplayCombos.Combo> found=combos(events);
        check(found.size()==3,"all maximal runs including trailing run");
        check(found.get(0).side==0&&found.get(0).hits==3&&found.get(0).firstMs==100&&found.get(0).lastMs==300,"opening combo boundaries");
        check(found.get(1).side==0&&found.get(1).hits==4&&found.get(1).firstMs==500&&found.get(1).lastMs==800,"one reply breaks a run; four hits produce one row");
        check(found.get(2).side==1&&found.get(2).hits==3&&found.get(2).firstMs==900,"opponent's trailing combo included");
        check(found.get(0).seekMs()==0&&found.get(2).seekMs()==150,"lead-in clamps at start and otherwise begins 750ms early");
        events=new ArrayList<>();
        for(int i=1;i<=4;i++){
            events.add(hit(0,i*2,1000+i*100));events.add(damage(0,i*2,1));
            Map<String,Object> rod=hit(1,i*2+1,1050+i*100);rod.put("rod",true);events.add(rod);
            events.add(Map.of("type","critical","attacker","bot","target","human","time",1050+i*100));
        }
        found=combos(events);check(found.size()==1&&found.get(0).hits==4,"opponent rod contacts and duplicated critical callbacks neither count nor end a melee combo");
        events=new ArrayList<>(List.of(hit(0,1,1100),damage(0,1,1),hit(1,2,1200),damage(1,2,0),
            hit(0,3,1300),damage(0,3,1),hit(0,4,1400),damage(0,4,.25)));
        events.get(6).put("damage_upgrade",true);
        found=combos(events);check(found.size()==1&&found.get(0).hits==3,"zero HP-damage contact is not a response; damaging partial upgrade counts once");
        check(ReplayCombos.find(events,"human","bot",1000,true).isEmpty(),"boxing counts accepted opponent replies despite no HP loss");
        events=List.of(hit(0,1,1100),hit(0,2,2100),hit(0,3,121000));
        found=combos(events);check(found.size()==1&&found.get(0).lastMs==120000,"definition adds no arbitrary maximum time gap");
        events=new ArrayList<>(List.of(hit(0,1,900),hit(0,2,1100),hit(0,3,1200),hit(0,4,1300)));
        events.get(3).put("target","unrelated");
        check(combos(events).isEmpty(),"pre-start hits and unrelated participants ignored");
        check(combos(List.of(hit(0,1,1000),hit(0,2,1000),hit(1,3,1000),hit(0,4,1000))).isEmpty(),"same-timestamp response preserves recorded order");
        check(combos(List.of(hit(0,1,1000),hit(0,2,1000),hit(0,3,1000),hit(1,4,1000))).size()==1,"same-timestamp third hit before response completes combo");
        events=new ArrayList<>(List.of(hit(0,1,1100),hit(0,2,1200),hit(0,3,1300),damage(0,2,0)));
        events.get(1).remove("hit_index");events.get(1).put("hits_a",2);events.get(1).put("hits_b",0);
        check(combos(events).isEmpty(),"older hit events correlate HP damage through accepted-hit counters");
        check(combos(List.of()).isEmpty(),"empty recording has no combos");
        check(ReplayCombos.timestamp(62340).equals("1:02.34"),"readable timestamp");
        System.out.println("PASS replay combos: "+checks+" checks (run boundaries, replies, rods, damage upgrades, boxing, old recordings, event order and seek lead-in)");
    }
}
