package practice.bridge;

import java.util.Locale;
import java.util.Properties;
import net.minecraft.client.gui.FontRenderer;
import practice.replay.SprintTransitions;

/** Cues for the lab's own-player sprint transitions. */
final class ServerSprintCue {
    enum Mode {
        OFF, FLASH, SOUND, BOTH;

        static Mode parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        boolean flash() { return this == FLASH || this == BOTH; }
        boolean sound() { return this == SOUND || this == BOTH; }
        String setting() { return name().toLowerCase(Locale.ROOT); }

        static Mode of(boolean flash, boolean sound) {
            if (flash) return sound ? BOTH : FLASH;
            return sound ? SOUND : OFF;
        }
    }

    private static final long FLASH_NS = SprintTransitions.DISPLAY_MS * 1_000_000L;
    private Mode gain = Mode.FLASH, loss = Mode.FLASH;
    private String match = "";
    private long gainUntil, lossUntil;
    private String replaySubject = "";
    private long replayRevision;
    private double replayTime;
    private boolean replayPlaying;

    static String key(boolean sprint) { return sprint ? "serversprint.gain" : "serversprint.loss"; }
    Mode mode(boolean sprint) { return sprint ? gain : loss; }

    void settings(Properties settings) {
        gain = read(settings, true);
        loss = read(settings, false);
    }

    private static Mode read(Properties settings, boolean sprint) {
        Mode mode = Mode.parse(settings.getProperty(key(sprint), "flash").trim());
        return mode == null ? Mode.FLASH : mode;
    }

    void changed(String match, boolean sprint) {
        if (!this.match.equals(match)) {
            this.match = match;
            gainUntil = lossUntil = 0;
        }
        Mode mode = mode(sprint);
        if (mode.flash()) {
            long until = System.nanoTime() + FLASH_NS;
            if (sprint) gainUntil = until;
            else lossUntil = until;
        }
    }

    void draw(String match, FontRenderer font, int width, int height) {
        if (!this.match.equals(match)) return;
        long now = System.nanoTime();
        draw(font, width, height, now < gainUntil, now < lossUntil);
    }

    void replay(String subject, long revision, double time, boolean playing,
                SprintTransitions.Sample transitions, SumoCoach coach) {
        if (!replaySubject.equals(subject) || revision != replayRevision || !replayPlaying || !playing) {
            replayTime = time;
        } else if (time > replayTime) {
            if (gain.sound() && transitions.gain > replayTime && SprintTransitions.Sample.visible(transitions.gain, time))
                coach.playServerSprint(true);
            if (loss.sound() && transitions.loss > replayTime && SprintTransitions.Sample.visible(transitions.loss, time))
                coach.playServerSprint(false);
            replayTime = time;
        }
        replaySubject = subject;
        replayRevision = revision;
        replayPlaying = playing;
    }

    void clearReplay() { replayPlaying = false; }

    void drawReplay(FontRenderer font, int width, int height, double time, SprintTransitions.Sample transitions) {
        draw(font, width, height, SprintTransitions.Sample.visible(transitions.gain, time),
                SprintTransitions.Sample.visible(transitions.loss, time));
    }

    private void draw(FontRenderer font, int width, int height, boolean gained, boolean lost) {
        gained &= gain.flash();
        lost &= loss.flash();
        int y = height / 2 - 19;
        if (gained) drawLabel(font, width, y, "Sprint gained", 0xFF82F2AA);
        if (lost) drawLabel(font, width, gained ? y - 10 : y, "Sprint lost", 0xFFFFBA74);
    }

    private static void drawLabel(FontRenderer font, int width, int y, String label, int color) {
        font.func_175063_a(label, (width - font.func_78256_a(label)) / 2f, y, color);
    }
}
