package practice.bridge;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static practice.bridge.SumoCoach.Option.*;

/** Small native settings screen. Result cards use the same screen and stay on the connection. */
final class SumoCoachScreen extends GuiScreen {
    private static final int SETTINGS = 0, CURRENT = 1, LAST = 2, RECENT = 3;
    private static final int BACK = 4, BRAKE_SOUND = 5, VOLUME = 6, JUMP = 7, UP = 8, DOWN = 9;
    private static final int SOUNDS = 10, HELP = 11, SOUND_CHOICE = 40, SOUND_PREVIEW = 60;
    private static final int OPTION = 20, HISTORY = 100;
    private static final int TEXT = 0xFFEAF0EC, MUTED = 0xFFA9B8BB, GREEN = 0xFF74E6A6;
    private static final List<String> ANALYSIS_HELP = Arrays.asList(
            "Where you lost ground",
            "Slid is the furthest you moved along the direction of the hit. The main timestamp is when the hit reached your client. Each +ms line is time since that hit.",
            "",
            "What a brake does",
            "Clicking the opponent while sprinting cuts your current horizontal speed by 40%. If you are sliding backward, that slows the slide. Your client can brake even if the opponent is still immune to damage.",
            "",
            "Why a brake was missed",
            "Sprint off means your client registered an on-target click while you were still sliding, but you were not sprinting. ToggleSprint can be enabled while actual sprint is off. W up means W was released for a movement update.",
            "No click means your crosshair was on them, but you did not click in that opening. An aim miss means a nearby target was missed. A miss timer is the short delay Minecraft applies after swinging at air. Some moments have more than one blocker.",
            "",
            "What a correction could save",
            "Less slide estimates the reduction in the furthest point of this slide if you fixed that one moment. It uses your recorded movement and keeps later contacts. If a click needs two fixes, the number assumes both. A far-away or uncertain click may have no estimate.",
            "",
            "Saved and ideal",
            "Saved is what your actual brakes saved compared with the same movement with no brakes. Ideal adds one brake per recorded tick when your crosshair was on the opponent and the miss timer allowed a click. The individual estimates overlap. Read the ideal as one combined comparison.",
            "",
            "Where you gained ground",
            "These moments show how much farther you would have slid without your own brakes. Lab results can also use the server's hit record and the bot's saved view. The bot's +ms lines start when your hit reached its client.",
            "",
            "Footing and the edge",
            "Grounded or airborne describes your first move after the hit. Floor support is how much platform the client saw in the push direction. Server sprint on fresh accepted hits counts the times your sprint added knockback to an outgoing hit in the lab.");
    private final GuiScreen parent;
    private final SumoCoach coach;
    private SumoCoach.Summary selected;
    private int tab, scroll, analysisScroll, left, width;
    private String error = "";

    SumoCoachScreen(GuiScreen parent, SumoCoach coach, SumoCoach.Summary selected) {
        this.parent = parent;
        this.coach = coach;
        this.selected = selected;
        this.tab = selected == null ? SETTINGS : LAST;
    }

    @Override public boolean func_73868_f() { return false; }

    @Override public void func_73866_w_() {
        field_146292_n.clear();
        width = Math.min(368, field_146294_l - 20);
        left = (field_146294_l - width) / 2;
        String[] tabs = {"Settings", "Current", "Analysis", "Recent"};
        for (int i = 0; i < tabs.length; i++) {
            GuiButton button = button(i, left + i * width / tabs.length, 28, width / tabs.length - 2, tabs[i]);
            button.field_146124_l = tab != i;
        }
        int bottom = field_146295_m - 24;
        button(BACK, left, bottom, 72, "Back");
        if (tab == SETTINGS) settingsButtons();
        else {
            button(UP, left + width - 86, bottom, 40, "Up");
            button(DOWN, left + width - 42, bottom, 42, "Down");
            if (tab == LAST) button(HELP, left + width - 142, bottom, 52, "Help");
            if (tab == RECENT) historyButtons();
            if (tab == SOUNDS) soundButtons();
        }
    }

    private void settingsButtons() {
        int half = (width - 6) / 2;
        option(LIVE, left, 54, half);
        option(HUD, left + half + 6, 54, half);
        option(SUMMARY, left, 76, half);
        button(VOLUME, left + half + 6, 76, half, "Volume " + coach.volume + "%");
        int audio = left + width - 159, visual = left + width - 65;
        button(BRAKE_SOUND, audio - 24, 102, 115, coach.brakeSound.label);
        shortOption(BRAKE_FLASH, visual, 102, "Flash");
        shortOption(SPRINT_SOUND, audio, 124, "Sound");
        shortOption(SPRINT_TEXT, visual, 124, "Note");
        shortOption(TIMER_SOUND, audio, 146, "Sound");
        shortOption(TIMER_TEXT, visual, 146, "Note");
        shortOption(WINDOW_SOUND, audio, 168, "Sound");
        shortOption(WINDOW_TEXT, visual, 168, "Note");
        button(OPTION + COSTLY_BRAKE_SOUND.ordinal(), audio - 23, 190, 88, "Brake alert " + on(coach.enabled(COSTLY_BRAKE_SOUND)));
        shortOption(AIM_FLASH, visual, 190, "Flash");
        button(SOUNDS, left + 76, field_146295_m - 24, width - 237, "Sounds");
        button(JUMP, left + width - 157, field_146295_m - 24, 157,
                "Jump-reset trainer: " + on(CombatCoach.jumpEnabled()));
    }

    private int soundRows() { return Math.max(1, (field_146295_m - 79) / 21); }

    private void soundButtons() {
        SumoCoach.SoundCue[] cues = SumoCoach.SoundCue.values();
        int choiceWidth = (width - 52) / 2;
        for (int i = scroll; i < Math.min(cues.length, scroll + soundRows()); i++) {
            int y = 52 + (i - scroll) * 21;
            button(SOUND_CHOICE + i, left + width - 50 - choiceWidth, y, choiceWidth, coach.selectedSound(cues[i]).label);
            button(SOUND_PREVIEW + i, left + width - 46, y, 46, "Play");
        }
    }

    private void historyButtons() {
        List<SumoCoach.Summary> history = coach.recent();
        for (int i = scroll; i < history.size() && 77 + (i - scroll)*22 <= field_146295_m - 47; i++) {
            button(HISTORY + i, left, 77 + (i - scroll)*22, width,
                    field_146289_q.func_78269_a(history.get(i).title(), width - 12));
        }
    }

    private void shortOption(SumoCoach.Option setting, int x, int y, String label) {
        button(OPTION + setting.ordinal(), x, y, 65, label + " " + on(coach.enabled(setting)));
    }

    private void option(SumoCoach.Option setting, int x, int y, int w) {
        button(OPTION + setting.ordinal(), x, y, w, setting.label + ": " + on(coach.enabled(setting)));
    }

    private static String on(boolean on) { return on ? "On" : "Off"; }

    private GuiButton button(int id, int x, int y, int w, String label) {
        GuiButton button = new GuiButton(id, x, y, w, 20, label);
        field_146292_n.add(button);
        return button;
    }

    @Override protected void func_146284_a(GuiButton button) {
        int id = button.field_146127_k;
        if (id == BACK) { back(); return; }
        if (id == UP || id == DOWN) { scroll(id == UP ? -4 : 4); return; }
        if (id == HELP) {
            analysisScroll = scroll;
            tab = HELP;
            scroll = 0;
            func_73866_w_();
            return;
        }
        if (id >= SOUND_PREVIEW && id < SOUND_PREVIEW + SumoCoach.SoundCue.values().length) {
            coach.preview(SumoCoach.SoundCue.values()[id - SOUND_PREVIEW]);
            return;
        }
        if (id >= HISTORY) {
            int rank = id - HISTORY;
            if (rank < coach.recent().size()) { selected = coach.recent().get(rank); tab = LAST; scroll = 0; }
        } else if (id < BACK || id == SOUNDS) {
            tab = id;
            scroll = 0;
            selected = null;
        } else try {
            if (id == BRAKE_SOUND) coach.nextSound();
            else if (id == VOLUME) coach.nextVolume();
            else if (id == JUMP) coach.toggleJump();
            else if (id >= SOUND_CHOICE && id < SOUND_CHOICE + SumoCoach.SoundCue.values().length)
                coach.nextSound(SumoCoach.SoundCue.values()[id - SOUND_CHOICE]);
            else if (id >= OPTION && id < OPTION + SumoCoach.Option.values().length)
                coach.toggle(SumoCoach.Option.values()[id - OPTION]);
            error = "";
        } catch (IOException e) { error = "Could not save settings"; }
        func_73866_w_();
    }

    @Override protected void func_73869_a(char typed, int key) {
        if (key == Keyboard.KEY_ESCAPE) back();
        else if (key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN) scroll(key == Keyboard.KEY_UP ? -1 : 1);
    }

    @Override public void func_146274_d() {
        super.func_146274_d();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) scroll(wheel > 0 ? -3 : 3);
    }

    private void scroll(int amount) {
        if (tab == SETTINGS) return;
        int max = tab == SOUNDS ? Math.max(0, SumoCoach.SoundCue.values().length - soundRows()) :
                tab == RECENT ? Math.max(0, coach.recent().size() - 1) : Math.max(0, wrapped().size() - visibleLines());
        scroll = Math.max(0, Math.min(max, scroll + amount));
        if (tab == RECENT || tab == SOUNDS) func_73866_w_();
    }

    private SumoCoach.Summary displayed() {
        return tab == CURRENT ? coach.current() : selected == null ? coach.last() : selected;
    }

    private List<String> wrapped() {
        SumoCoach.Summary summary = displayed();
        List<String> content = tab == HELP ? ANALYSIS_HELP : summary == null ? Collections.emptyList() : summary.details;
        List<String> result = new ArrayList<>();
        for (String line : content) result.addAll(field_146289_q.func_78271_c(line, width - 6));
        return result;
    }

    private int visibleLines() { return Math.max(1, (field_146295_m - 104) / 11); }

    @Override public void func_73863_a(int mouseX, int mouseY, float partial) {
        func_146276_q_();
        func_73732_a(field_146289_q, "Sumo Coach", field_146294_l / 2, 12, TEXT);
        if (tab == SETTINGS) drawSettings();
        else if (tab == SOUNDS) drawSounds();
        else if (tab == RECENT) drawRecent();
        else drawSummary();
        if (!error.isEmpty()) text(error, left + 79, field_146295_m - 17, 0xFFFFB873);
        super.func_73863_a(mouseX, mouseY, partial);
    }

    private void drawSettings() {
        String[] labels = {"Successful brake", "Sprint off at contact", "Miss timer blocked", "Unclicked opening", "In-range aim miss"};
        for (int i = 0; i < labels.length; i++) text(labels[i], left + 3, 109 + i * 22, TEXT);
        if (field_146295_m >= 265) text("Live cues are available in the lab.", left + 3, 220, MUTED);
    }

    private void drawSounds() {
        SumoCoach.SoundCue[] cues = SumoCoach.SoundCue.values();
        for (int i = scroll; i < Math.min(cues.length, scroll + soundRows()); i++)
            text(field_146289_q.func_78269_a(cues[i].label, (width - 52) / 2 - 6), left + 3, 59 + (i - scroll) * 21, TEXT);
    }

    private void drawRecent() {
        List<SumoCoach.Summary> history = coach.recent();
        int rounds = Math.min(5, history.size()), opens = 0, cuts = 0, late = 0;
        for (int i = 0; i < rounds; i++) {
            opens += history.get(i).opportunities;
            cuts += history.get(i).early;
            late += history.get(i).unclicked;
        }
        text(rounds == 0 ? "No completed Sumo matches yet" : "Last " + rounds + " rounds: " + cuts + "/" + opens +
                " early brakes / " + late + " unclicked", left + 3, 57, GREEN);
    }

    private void drawSummary() {
        if (tab == HELP) {
            text("How to read match analysis", left + 3, 57, GREEN);
            drawLines();
            return;
        }
        SumoCoach.Summary summary = displayed();
        if (summary == null) {
            text(tab == CURRENT ? "No Sumo match in progress" : "No completed Sumo match yet", left + 3, 57, MUTED);
        } else {
            text(summary.title(), left + 3, 57, GREEN);
            drawLines();
        }
        String status = coach.saveError();
        if (status.isEmpty() && tab == LAST && coach.interrupted() != null &&
                (summary == null || coach.interrupted().finished > summary.finished)) status = "Latest round interrupted";
        if (!status.isEmpty()) text(status, left + 79, field_146295_m - 17, MUTED);
    }

    private void drawLines() {
        List<String> lines = wrapped();
        int offset = Math.min(scroll, Math.max(0, lines.size() - visibleLines()));
        for (int i = offset; i < lines.size() && i < offset + visibleLines(); i++)
            text(lines.get(i), left + 3, 73 + (i - offset)*11, TEXT);
    }

    private void text(String line, int x, int y, int color) { field_146289_q.func_175063_a(line, x, y, color); }
    private void back() {
        if (tab == SOUNDS) { tab = SETTINGS; scroll = 0; func_73866_w_(); }
        else if (tab == HELP) { tab = LAST; scroll = analysisScroll; func_73866_w_(); }
        else field_146297_k.func_147108_a(parent);
    }
}
