package lab;

import java.util.*;

/** In-game command reference, including the optional client's /p commands. */
public final class LabHelp {
    static final String[] TOPICS={"play","settings","matches","replay","views","client"};
    static final String[][] PAGES={
        {"/bots [all] — choose an opponent",
         "/duel <bot> [mode] [bot RTT] [your RTT] [options]",
         "/rematch [mode] [options] — repeat with saved bot settings",
         "/mode boxing|diamond|diamond_box|rod_diamond|sumo",
         "/drill near|medium|far|mixed [bot] — repeated entries, 2s countdown",
         "/drill stop; /stop — exit your drill, duel, replay or spectating",
         "/ping; /ping me <ms>; /ping bot <ms>; /ping <your RTT> <bot RTT>",
         "/blocksound on|off|test; /blocktiming on|off",
         "Your added RTT persists between matches. /help settings lists duel options."},
        {"Append options to /duel <bot> or /rematch:",
         "preset=lock8 — KB1.1/1, hit8, reach3, cap20, bot RTT150/your RTT0; explicit options win",
         "preset=hypixel — PROVISIONAL Housing fit: native KB, sprint extra Y .07, hit9; keeps your configured RTT",
         "/duel lock8 boxing preset=lock8 — face the demo controller; this does not disable your attacks",
         "cps=20 — maximum inputs/sec (1–30); bot may deliberately click less",
         "reach=3.0 — bot attack reach (1–5 blocks); 3.0 is normal",
         "ping=100 (or botping=100); myping=20 — added RTT, 0–500ms",
         "aimdelay=150 — delayed local target tracking, 0–500ms; default 0",
         "aimnoise=2 — smooth angular aim error in degrees, 0–10; default 0",
         "aimrate=360 — turn limit in degrees/sec, 30–1440; 0 means unlimited",
         "tapdelay=150 — W-tap observation/reaction delay in ms, 0–1000",
         "kb_horizontal=1 kb_vertical=1 — fresh melee knockback multipliers, 0–2, both players",
         "hit_ticks=10 — fresh damage delay, 1–20 server ticks; kb=vanilla resets all combat options",
         "sprint_vertical=.1 — fresh sprint extra Y before vertical multiplier (0–1); property combat_sprint_vertical",
         "/duel wtap boxing cps=20 aimdelay=150 aimnoise=2 tapdelay=150",
         "These override only this bot/duel; /rematch retains them. Perfect aim: aimdelay=0 aimnoise=0 aimrate=0."},
        {"/lab match <bot-a> <bot-b> [RTT-a] [RTT-b] [options] — boxing",
         "/lab demo lock8 — spectate 4s setup + 60s active hold-W; modified combat, native reach",
         "/lab match lock8 lock8-target preset=lock8 — same setup, ends at 100 hits",
         "preset=lock8 sets A/B RTT150/0; a.ping/b.ping or positional RTTs override it",
         "preset=hypixel is provisional combat only; bot-match added RTT defaults0/0, explicit RTTs still win",
         "/lab diamond|diamond_box|rod_diamond|sumo <bot-a> <bot-b> [RTT-a] [RTT-b] [options]",
         "Shared option: cps=20. Per-bot option: a.aimdelay=0 b.aimdelay=150",
         "Combat options kb_horizontal/kb_vertical/hit_ticks/sprint_vertical apply equally to both bots; no a./b. prefix",
         "/lab match wtap wtap 20 80 cps=20 b.aimdelay=150 b.aimnoise=2",
         "/matches — clickable active matches; /watch [match-id] [a|b]",
         "/lab ping <match-id> <RTT-a> <RTT-b> — change active added RTT",
         "/lab scenario <bot-a> <bot-b> <RTT-a> <RTT-b> <duration-ms> <request-id>",
         "/lab seed list — saved synthetic mid-combo starts",
         "/lab seed <name> <bot-a> <bot-b> [RTT-a RTT-b duration-ms request] — exact declared seed, audited native phases",
         "/lab prepare long — prewarm long arena while no match is active. Scenarios: 1000–304000ms. Normal boxing always ends at 100 accepted hits.",
         "/lab combat [vanilla | kb_horizontal=1 kb_vertical=1 hit_ticks=10 sprint_vertical=.1] — next bot scenarios only",
         "Bot matches also accept an optional request-id after both positional RTTs."},
        {"/replay [match-id] — latest own match, or a specific recording",
         "/replay list [all] — recent own/all matches",
         "/replay menu; /replay controls — hotbar/settings and chat controls",
         "/replay pause; /replay play; /replay speed <factor>",
         "/replay seek <seconds>; /replay seek +5; /replay seek -5",
         "/replay back [seconds]; /replay forward [seconds] — default 5",
         "/replay a|b|free; /replay camera a|b|free — follow/fly",
         "/replay combos [page] — clickable 3+ unanswered-hit sequences",
         "/replay stop — leave; /help views lists viewpoints and ghosts."},
        {"/replay pov server|human|bot|a|b — recorded viewpoint",
         "/replay ghost human-self|human-opponent|bot-self|bot-opponent on|off",
         "/replay ghost human|bot on|off; /replay ghost off — groups/all",
         "Client mod commands (/p is /practice):",
         "/p advantage on|off; /p opening on|off|large|small",
         "/p reach on|off; /p ghost bot|me on|off; /p debug on|off",
         "/p range yours|theirs|both on|off|crosshair|edge|screen",
         "/p range yours|theirs|both color <RRGGBB>",
         "/p range yours|theirs|both opacity <0–100|auto>",
         "/p range yours|theirs|both pulse on|off — entry flash"},
        {"Forge practice mod: /p and /practice are interchangeable.",
         "/p reload — reload module/config; /p status — diagnostics",
         "/p sprint on|off; /p potions on|off; /p metrics on|off",
         "/p record on|off — local observation CSV",
         "/p capture on|off — detailed packet/input capture",
         "/p link on|off — local client/server coordination",
         "/p mark <text> — shared timestamped note; /p ping — clock probe",
         "/help views — all HUD, range and ghost controls",
         "/help [page|topic]; /? [page|topic]; /help all — this full reference"}
    };
    public static int page(String value){
        for(int i=0;i<TOPICS.length;i++)if(TOPICS[i].equalsIgnoreCase(value))return i;
        try{int n=Integer.parseInt(value)-1;if(n>=0&&n<PAGES.length)return n;}catch(NumberFormatException ignored){}
        throw new IllegalArgumentException("Use /help 1–6, play, settings, matches, replay, views, client, or all");
    }
    static void show(Object sender,String topic){
        if(topic.equals("all")){for(int i=0;i<PAGES.length;i++)showPage(sender,i);return;}
        int index=topic.equals("lab")?0:page(topic);
        showPage(sender,index);
        if(index>0)Referee.button(sender,"Previous help page","/help "+index);
        if(index+1<PAGES.length)Referee.button(sender,"Next: "+TOPICS[index+1],"/help "+(index+2));
        for(String name:TOPICS)Referee.button(sender,name,"/help "+name);
    }
    private static void showPage(Object sender,int index){
        Referee.message(sender,"§6PvP practice help §e"+(index+1)+"/"+PAGES.length+" §7— "+TOPICS[index]);
        for(String line:PAGES[index])Referee.message(sender,(line.startsWith("/")?"§b":"§7")+line);
    }
}
