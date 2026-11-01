# What Zest-tapping tells this experiment

The useful instruction is to choose when to stop resetting sprint, and to release
W around a selected contact. It is not a fixed-rate W tap after every swing.

Our earlier direct inspection of [asian_batman's tutorial](https://www.youtube.com/watch?v=Dfj1oHBOKwQ)
(0:28–1:13, 1:53–2:23) found a three-contact sequence with W released for the
third contact, plus a two-contact variation with W released for the second.
[jazitation's tutorial](https://www.youtube.com/watch?v=mTeXebYfIIs) teaches entry,
recovery after receiving a hit, and a three-contact sequence; it emphasizes
strafing and describes opponents around90–140ms as favorable. These are creator
instructions, not measured universal timings. See the contemporaneous
`experiments/ZEST-NOTES.md` for what was actually inspected. Its downloaded caption
track contained music recognition, not an instructional transcript. The current
web fetch of the videos failed/throttled; no new transcript is being claimed.

For the W-only study, the strong hint is a repeatable sequence of strong and weak
launches with deliberately timed pursuit/coasting. The first native test uses
the two-contact variant. The model, rather than the name “Zest”, determines the
specific input durations. Strafe remains a separate future axis.

## Hypixel evidence is not an exact specification

The [Hypixel Hit plugin author's description](https://modrinth.com/mod/hypixel-hit)
claims faster contacts, adjustable airborne sprint behavior and simulated motion
delay, but explicitly says it does not accurately reproduce Hypixel knockback.
It provides no verified Bedwars constants. It is a clue about plausible control
axes, not a reference implementation. We did not install it or ProtocolLib.

No authoritative numeric Hypixel melee KB/damage-immunity specification was
established in this search. “Hit delay” in discussions also conflates damage
immunity, the client's miss penalty, packet latency and server stalls. We must
not infer one constant from those anecdotes. A successful small tweak in our
server identifies a sufficient mechanism; it cannot prove that Hypixel uses it.

## Exact local reference and configurable experiment

The pinned Mojang1.8.9 bytecode is the reference. For an unenchanted fresh melee
hit, the server halves its stored victim motion, adds a normalized horizontal
0.4 impulse, adds0.4 vertically and caps vertical motion at0.4. A sprint hit then
adds another0.5 horizontally and0.1 vertically. Native float rounding and S12
1/8000 quantization matter. The server restores its old stored victim velocity
after sending S12; the victim client receives the launch. Thus the victim's
client velocity and latent server velocity are different state variables.

The new `lab combat` research controls scale the resulting outgoing fresh melee
velocity before S12 quantization, and independently adjust the native immunity
threshold. Defaults change no launch values. Profiles apply only to subsequent
timed bot scenarios, are frozen in each recording, and never silently change
ordinary practice duels. `lab combat vanilla` restores defaults. These are our
experimental settings, explicitly not “Hypixel mode”.
