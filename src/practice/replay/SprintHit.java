package practice.replay;

import java.util.Map;

/** Classification of an outgoing fresh melee hit using the lab referee's attack-entry flag. */
public final class SprintHit {
    public static final int DISPLAY_MS=300;
    private SprintHit(){}

    public static Boolean classify(Map<String,Object> row){
        if(!"hit".equals(row.get("type"))||!Boolean.FALSE.equals(row.get("rod"))||
                !Boolean.FALSE.equals(row.get("damage_upgrade"))||Boolean.FALSE.equals(row.get("direct_melee")))return null;
        Object sprint=row.get("attacker_sprint");
        return sprint instanceof Boolean?(Boolean)sprint:null;
    }
}
