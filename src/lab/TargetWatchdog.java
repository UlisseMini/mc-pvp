package lab;

/** Fail closed on removed, unticked or stalled native target entities. */
final class TargetWatchdog {
    private Object target;
    private long tick,advancedAt;
    private int advances;
    void reset(){target=null;tick=advancedAt=0;advances=0;}
    boolean update(Object current,long now,long nativeTick,boolean loaded,boolean chunkMember,boolean regionLoaded){
        if(current!=target){target=current;tick=nativeTick;advancedAt=now;advances=0;}
        else if(nativeTick!=tick){tick=nativeTick;advancedAt=now;advances++;}
        return current!=null&&loaded&&chunkMember&&regionLoaded&&advances>=2&&now-advancedAt<=150;
    }
}
