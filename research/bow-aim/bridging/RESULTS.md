# SkyWars bow review and bridge motion tests

## SkyWars review

Client text logs show the initial trial at 00:20 local time. Detailed
recording began at 00:27:15. A review of that later sample through 00:29:38
associated 18 release packets with arrow spawns owned by the recorder and
compared observed arrow segments with nearby visible player hitboxes.
Remote poses and packet timing limit fine collision attribution.

The code was using the item-use **countdown** as the **elapsed draw**. A
captured value of 71,992 meant an approximately eight-tick draw. The old
assist treated that shot as full power. The fix uses the native elapsed
draw accessor for trajectory preview, aim, release, and the full-charge
gate. The native probe checks an eight-tick draw and a full 20-tick draw.

At 00:28:18, one reconstructed arrow passed almost half a block below the
feet of a player about 41 blocks away. That player rose about two blocks
during the previous 750 ms of observed motion. At 00:28:08, another shot
passed about 0.7 blocks above the expanded hitbox of a player about 24
blocks away. The build also searches shoulder and lower aim points when
the usual torso trajectory is blocked. [Anonymized shot review](../../../runtime/bow-aim/bridging/skywars-review.csv)

## Counterfactual bridge tests

The miner found 99,146 client ticks with a player holding a block, looking
down and near a solid block update. That is a proxy for building activity.
The update helps select examples offline. At inference, the model uses
visible motion, sneak status and the block-held pose.

The bridge specialist learns horizontal and vertical movement from these
cases. It uses velocities over the last second, recent sneaking and changes
in the player's minimum recorded height to distinguish climbing from a
temporary bounce. Its forecast contributes 50% during a sustained,
moving build pose. The other half comes from the existing adaptive model.
This blend was selected on games 07–08. Games 09–12 and a separate set of
220 scripted tracks were reserved for evaluation.

The shot comparisons keep the actual future target positions, arrow physics
and dispersion seed identical. They ask whether a hypothetical open shot
from 10, 18, 28, 40 or 52 blocks would have hit. There are 26,830 paired
bridge shot scenarios in the archived evaluation and 78,310 in the
scripted set. Neighboring starts reuse movement and are correlated.

| Recorded movement | Previous predictor | Bridge specialist blend |
| --- | ---: | ---: |
| Flat, continued sneaking | 92.8% | 93.2% |
| Flat, released sneak | 87.1% | 87.9% |
| Rising | 69.3% | 71.7% |
| Rising at 28 blocks | 73.7% | 78.0% |
| Rising at 40 blocks | 50.8% | 54.0% |

The general non-building cohort scored 71.9% in both arms. On scripted
stairs with sneak held, the rate rose from 91.1% to 92.7%. With sneak
released intermittently it rose from 88.2% to 89.8%. The recorded and
scripted score tables retain the straight-line comparison and five
distance bins. [Recorded scores](../../../runtime/bow-aim/bridging/heldout.csv) · [Scripted scores](../../../runtime/bow-aim/bridging/drill-heldout.csv)

## Hit chance and full-draw release

The chance model compares the arrow path from the actual displayed aim to
80 alternate futures. The alternatives sample movement errors from archived
forecasts by flight time, speed, vertical movement and the use of the
bridge specialist. They also use Minecraft's arrow spread. The path is
clipped at observed terrain. The estimate is calibrated on games 07–08.

On 24,433 held-out open-shot comparisons with randomized aim offsets, the
bucket averaging 80.3% predicted chance hit 79.7% of the time. The held-out
Brier score was 0.124 for both the raw and calibrated estimates, rounded
to three decimals. The probability tests use modelled shots on recorded
movement. Individual live shots also depend on latency and reactions after
release. [Probability checks](../../../runtime/bow-aim/bridging/probability-heldout.csv)

The native test covers the corrected draw clock, exposed aim over cover, the
bridge predictor, the chance label, and the release gates. Auto-release is
opt-in and fires once per held draw. It requires a full charge, a target and
a hit chance at or above the selected threshold. [Native checks](../ForgeProbe.java) · [Mod controls](../../../practice-client/README.md#adaptive-bow-aim)
