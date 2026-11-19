import java.io.IOException;
import java.io.UncheckedIOException;

/** Transfer opponent used only in the additional lateral/hit-select test. */
final class StrafeOpponent extends DuelEnv.Player {
    private final SumoModel.Bot bot;

    StrafeOpponent(int rtt) {
        super("zest-strafe", 0, rtt, 0);
        try {
            bot = new SumoModel.Bot(style, rtt, 9, 20, true, 10);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private StrafeOpponent(StrafeOpponent source) throws IOException {
        super(source.style, 0, source.rtt, 0);
        bot = source.bot.fork(style);
        firstClick = source.firstClick;
    }

    @Override public StrafeOpponent copy() {
        try {
            return new StrafeOpponent(this);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
        return bot.input(o);
    }

    @Override public void received(PeriodicComboClock.PacketObservation p) {
        bot.received(p);
    }

    @Override public boolean injected(PeriodicComboClock.Observation o, double t) {
        super.injected(o,t);
        return bot.injected(o,t);
    }
}
