# Nomophobia’s six base-KB hits in m10373

This 21.151-second, normal-speed MMC sumo duel against Gen 4 used the 137-block floor and zero added RTT on both seats. Nomophobia landed 26 fresh hits, including 20 with sprint knockback, and eight damage upgrades. The client received exactly 20 sprint and six base crosshair messages. The server’s sprint flag at attack entry agreed with the recorded fresh impulse for every hit.

| Fresh hit | Match time | Previous accepted hit by Nomophobia | What happened before the new hit |
|---|---:|---|---|
| 5 | 1.152 s | 4, sprinting damage upgrade, 49 ms earlier | W went up and down. The server had already spent the new sprint on #4. |
| 30 | 9.002 s | 29, sprinting damage upgrade, 100 ms earlier | The W-tap generated a sprint start. #29 used it. The client showed sprint active on #30. |
| 41 | 11.797 s | 40, sprinting damage upgrade, 95 ms earlier | W and the sprint binding were down through the new hit. The last sprint start preceded #40. |
| 55 | 15.852 s | 53, sprinting damage upgrade, 149 ms earlier | W was back down about 298 ms before the attack. #53 spent that restart. |
| 62 | 18.203 s | 60, sprinting fresh hit, 657 ms earlier | W went back down on the attack tick. The client sent attack first and sprint-start 0.200 ms later. |
| 66 | 19.551 s | 65, sprinting damage upgrade, 150 ms earlier | W was back down about 259 ms before this attack. #65 spent that restart. |

On #62, the client recorded the W keypress 0.102 ms before entering the click handler. The previous movement still had forward input zero. The attack went out, the ordinary movement update started sprinting, and the server handled the attack 0.973 ms before handling the sprint-start. In comparison, on #7, sprint-start went out 44 ms before the attack and the bot received 0.9495 horizontal blocks/tick. The replay keystrokes show the binding. They can show W and sprint down even when the attack uses an earlier movement state or an already-consumed server sprint.

The six measured base launches were 0.5269–0.5275 horizontal blocks/tick. In the same-hit counterfactual on #62, adding sprint increased 0.5275 to 0.9495. After two movement updates, Gen 4 projected 0.652 blocks farther out. At its recorded press 126 ms after receiving the launch, its nearest distance to Nomophobia changed from 2.55 to 3.20 blocks. That breaks the original contact geometry and ends the reliable comparison. In an actual rematch, repositioning the tap or delaying a press could also change whether that hit lands.

For practice, use #7 and #62 as a pair. Complete the W re-press on the movement tick before the intended contact. For the other five, look one accepted hit earlier. A stronger critical during immunity can count as a damage upgrade and spend sprint even though it makes no fresh MMC launch and gives no sprint-KB crosshair cue of its own.

[Server replay](../../runtime/replays/m10373.jsonl) · [Recorded client and server streams](../../runtime/pov/m10373/manifest.json) · [Per-hit packet audit](../../build/gen4-auto-sprint/m10373-sprint.json) · [Same-hit counterfactuals](../../build/gen4-auto-sprint/m10373.json)
