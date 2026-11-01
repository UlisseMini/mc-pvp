/** Non-search checks for the new external clock adapter. */
public final class PeriodicComboClockCheck {
    public static void main(String[] args){
        PeriodicComboFightModel.State s=new PeriodicComboFightModel.State(4093,0,4099,0);
        PeriodicComboFightModel.Config c=new PeriodicComboFightModel.Config();c.ownRtt=c.otherRtt=0;
        PeriodicComboClock clock=new PeriodicComboClock(s,c,new PeriodicComboClock.Timing(),o->new PeriodicComboClock.Control(0,0,false),o->new PeriodicComboClock.Control(0,0,false));
        clock.runUntil(1000);if(s.a.q.x()!=4093||s.b.q.x()!=4099||s.hits!=0||s.incomingHits!=0)throw new AssertionError("Rest changed");
        s=new PeriodicComboFightModel.State(4093,0,4099,0);c=new PeriodicComboFightModel.Config();c.ownRtt=c.otherRtt=0;
        clock=new PeriodicComboClock(s,c,new PeriodicComboClock.Timing(),o->new PeriodicComboClock.Control(2,0,false),o->new PeriodicComboClock.Control(0,0,false));
        clock.runUntil(.3);if(s.a.q.x()!=4093.127399981022)throw new AssertionError("First native sprint step: "+s.a.q.x());
        clock.runUntil(30);if(s.serverAx!=s.a.q.x())throw new AssertionError("C03 not admitted at independent drain");
        if(s.hits!=0||s.incomingHits!=0)throw new AssertionError("Invented contact");
        s=new PeriodicComboFightModel.State(4093,0,4099,0);s.serverTicks=1;s.teleportAgeA=400;
        PeriodicComboFightModel.track(s,c,0,true);if(s.teleportAgeA!=400||!s.packets.isEmpty())throw new AssertionError("Teleport age advanced outside gate");
        s.serverTicks=2;PeriodicComboFightModel.track(s,c,50,true);
        if(s.teleportAgeA!=0||s.packets.size()!=1||s.packets.get(0).packetId!=0x18)throw new AssertionError("Forced teleport missing at gated age 401");
        System.out.println("PeriodicComboClockCheck passed");
    }
}
