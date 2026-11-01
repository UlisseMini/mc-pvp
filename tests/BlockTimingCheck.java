import lab.BlockTiming;

public final class BlockTimingCheck {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        var b=new BlockTiming();b.transition(true,1000);b.hit(true,1080);
        check(b.text(1080).contains("BLOCKED")&&b.text(1080).contains("80 ms"),"successful hold lead");
        b.transition(false,1100);b.hit(false,1160);
        check(b.text(1160).contains("EARLY")&&b.text(1160).contains("60 ms"),"released too early");
        b.transition(true,1180);
        check(b.text(1180).contains("LATE")&&b.text(1180).contains("20 ms"),"closer late edge replaces early attribution");
        b.hit(true,1200);check(b.text(1200).contains("BLOCKED"),"new success supersedes miss");
        b.transition(false,1300);b.hit(false,2000);
        check(b.text(2000).contains("no nearby block"),"do not invent timing for distant attempts");
        b.transition(true,2400);check(!b.text(2400).contains("LATE"),"late attribution bounded");
        check(b.text(3101).isEmpty(),"temporary feedback expires");
        var c=new BlockTiming();c.transition(true,1000);c.transition(false,1100);c.hit(false,1110);c.transition(true,1200);
        check(c.text(1200).contains("EARLY"),"keep the closer earlier release");
        System.out.println("PASS blocked lead, early release, late press, nearest edge, bounded attribution and expiry");
    }
}
