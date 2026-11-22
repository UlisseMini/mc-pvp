package practice.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.EnumChatFormatting;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** BedWars can assign each player a different scoreboard team ID in the same squad. */
final class BowAimTeams {
    private static final Pattern TEAM_ID = Pattern.compile(
            "^(red|blue|green|yellow|aqua|white|pink|gr[ae]y)[0-9]*$", Pattern.CASE_INSENSITIVE);
    private static final String[] TEAMS = {"red", "blue", "green", "yellow", "aqua", "white", "pink", "gray"};
    private static final String LETTERS = "RBGYAWPG";
    private static final String[] COLORS = {"4c", "19", "2a", "6e", "3b", "f", "5d", "78"};
    private static final Pattern FORMAT_COLOR = Pattern.compile("(?i)§([0-9a-f])");
    private WorldClient world;
    private boolean bedWars;

    void observe(Minecraft mc) {
        if (world != mc.field_71441_e) {
            world = mc.field_71441_e;
            bedWars = false;
        }
        if (world == null || bedWars) return;
        Scoreboard board = world.func_96441_U();
        if (title(board.func_96539_a(1))) bedWars = true;
        for (int slot = 3; slot <= 18 && !bedWars; slot++) bedWars = title(board.func_96539_a(slot));
    }

    void chat(Minecraft mc, String message) {
        observe(mc);
        if (world != null && EnumChatFormatting.func_110646_a(message).trim()
                .startsWith(BedWarsForecastOverlay.START_MARKER)) bedWars = true;
    }

    private static boolean title(ScoreObjective objective) {
        return objective != null && "BEDWARS".equalsIgnoreCase(EnumChatFormatting
                .func_110646_a(objective.func_96678_d()).replaceAll("\\s+", ""));
    }

    boolean opponent(Minecraft mc, EntityPlayer self, EntityPlayer other) {
        if (self.func_142014_c(other)) return false;
        Team own = self.func_96124_cp(), theirs = other.func_96124_cp();
        ScorePlayerTeam ownTab = BedWarsForecastOverlay.tabTeam(mc, self.func_110124_au());
        ScorePlayerTeam theirTab = BedWarsForecastOverlay.tabTeam(mc, other.func_110124_au());
        if (same(own, theirTab) || same(ownTab, theirs) || same(ownTab, theirTab)) return false;
        if (!bedWars) return true;
        int ownColors = colors(own, ownTab), theirColors = colors(theirs, theirTab);
        return Integer.bitCount(ownColors) == 1 && Integer.bitCount(theirColors) == 1 && ownColors != theirColors;
    }

    private static boolean same(Team a, Team b) {
        return a != null && b != null && a.func_96661_b().equals(b.func_96661_b());
    }

    private static int colors(Team first, ScorePlayerTeam second) {
        return colors(first) | colors(second);
    }

    private static int colors(Team team) {
        if (!(team instanceof ScorePlayerTeam)) return 0;
        ScorePlayerTeam score = (ScorePlayerTeam)team;
        int result = 0;
        Matcher id = TEAM_ID.matcher(score.func_96661_b());
        if (id.matches()) {
            String name = id.group(1).toLowerCase(Locale.ROOT).replace("grey", "gray");
            for (int i = 0; i < TEAMS.length; i++) if (name.equals(TEAMS[i])) result |= 1 << i;
        }
        String prefix = score.func_96668_e();
        String indicator = EnumChatFormatting.func_110646_a(prefix).trim();
        if (indicator.length() == 1) {
            Matcher color = FORMAT_COLOR.matcher(prefix);
            if (color.find()) {
                char code = Character.toLowerCase(color.group(1).charAt(0));
                for (int i = 0; i < COLORS.length; i++)
                    if (indicator.charAt(0) == LETTERS.charAt(i) && COLORS[i].indexOf(code) >= 0) result |= 1 << i;
            }
        }
        return result;
    }
}
