package lab;

/** No Minecraft initialization or file writes: trace-only deduplication lifecycle. */
public final class NativeTimingTraceCheck {
    static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    public static void main(String[] args){
        check(ReplicationTrace.firstAttackLoop("fixture-a",1),"Empty applicable loop gets first marker");
        check(!ReplicationTrace.firstAttackLoop("fixture-a",1),"Additional same-tick press conditions do not create opportunities");
        check(ReplicationTrace.firstAttackLoop("fixture-a",2),"Next native tick is independent of previous click count");
        check(!ReplicationTrace.firstAttackLoop("fixture-a",2),"Repeated false/end condition suppressed");
        check(ReplicationTrace.firstAttackLoop("fixture-b",2),"New match resets trace deduplication even at same tick");
        System.out.println("NativeTimingTraceCheck passed");
    }
}
