import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Adapt the native-style movement, directional aim and click-window controllers to a duel. */
final class StrafeOpponent extends DuelEnv.Player {
    private final SumoModel.Bot bot;
    private interface Event {void apply(SumoModel.Bot replay);}
    private final List<Event> history=new ArrayList<>();
    StrafeOpponent(int rtt){this("zest-strafe",rtt,true,10);}
    StrafeOpponent(String name,int rtt,boolean mmc,int diameter){
        super(name,0,rtt,0);
        try{bot=new SumoModel.Bot(name,rtt,9,20,mmc,diameter);}
        catch(IOException ex){throw new UncheckedIOException(ex);}
    }
    @Override public StrafeOpponent copy(){
        StrafeOpponent c=new StrafeOpponent(style,rtt,bot.mmc,bot.diameter);
        if(!c.bot.options.equals(bot.options))throw new IllegalStateException("Opponent configuration changed since the opening");
        // Some displacement decisions depend on consumed attacks and impact callbacks between input heads.
        for(Event e:history)e.apply(c.bot);
        c.history.addAll(history);c.firstClick=firstClick;return c;
    }
    @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
        history.add(p->p.input(o));return bot.input(o);
    }
    @Override public float aimYaw(float trackingYaw){return bot.aimYaw(trackingYaw);}
    @Override public boolean contactOnly(){return bot.contactOnly();}
    @Override public boolean allowInjection(PeriodicComboClock.Observation o,String source){return bot.allowInjection(o,source);}
    @Override public void consumed(PeriodicComboClock.Observation o,long id){
        history.add(p->p.consumed(o,id));bot.consumed(o,id);
    }
    @Override public void received(PeriodicComboClock.PacketObservation packet){
        history.add(p->p.received(packet));bot.received(packet);
    }
    @Override public boolean injected(PeriodicComboClock.Observation o,double t){
        super.injected(o,t);history.add(p->p.injected(o,t));return bot.injected(o,t);
    }
}
