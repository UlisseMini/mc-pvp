package lab;

/** Safety regression for same-ID replacements and a stalled native entity. */
public final class TargetWatchdogCheck {
    static void require(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        TargetWatchdog watch=new TargetWatchdog();Object first=new Object(),replacement=new Object();
        require(!watch.update(first,0,1000,true,true,true));
        require(!watch.update(first,50,1001,true,true,true));
        require(watch.update(first,100,1002,true,true,true));
        require(!watch.update(first,251,1002,true,true,true));
        require(watch.update(first,300,1003,true,true,true));
        require(!watch.update(first,350,1004,false,true,true));
        require(!watch.update(first,400,1005,true,false,true));
        require(!watch.update(first,450,1006,true,true,false));
        require(!watch.update(replacement,500,0,true,true,true));
        require(!watch.update(replacement,550,0,true,true,true));
        require(!watch.update(replacement,600,1,true,true,true));
        require(watch.update(replacement,650,2,true,true,true));
        watch.invalidate();require(!watch.update(replacement,675,3,true,true,true));
        watch.reset();require(!watch.update(replacement,700,3,true,true,true));
        System.out.println("PASS target watchdog");
    }
}
