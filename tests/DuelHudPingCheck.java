package lab;

/** Match the real countdown string. A formatting digit can immediately precede "RTT". */
public final class DuelHudPingCheck {
    public static void main(String[] args){
        DuelHudPing.Reading r=new DuelHudPing.Reading();
        if(!Double.isNaN(r.observe("m1",false,23,"Countdown")) ||
            r.observe("m1",false,23,"§7RTT 97/23 ms")!=97 ||
            r.observe("m1",false,23,"§cHIT")!=97 ||
            r.observe("m1",false,23,"§7RTT 40/23 ms")!=40 ||
            !Double.isNaN(r.observe("m1",false,24,"§cHIT")) ||
            r.observe("m1",false,24,"§7RTT 40/24 ms")!=40 ||
            !Double.isNaN(r.observe("m2",false,24,"§cHIT")))
            throw new AssertionError("Remember only this duel's latest public RTT");
        String bar="Starting sumo in 1... §8| §7RTT 97/23 ms";
        if(DuelHudPing.read(bar,false,23)!=97 || DuelHudPing.read(bar,true,97)!=23 ||
           DuelHudPing.read("RTT 0/0 ms",false,0)!=0 || DuelHudPing.read("RTT 23/0 ms",true,23)!=0 ||
           !Double.isNaN(DuelHudPing.read("Starting sumo...",true,0)) ||
           !Double.isNaN(DuelHudPing.read(bar,true,15)))throw new AssertionError("HUD RTT observation");
    }
}
