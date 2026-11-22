# Project guidance

Help the non-programmer user build and understand the project. Apply ordinary
Git, coding and verification practices without requiring them to manage those
details. Explain results plainly and distinguish tested behavior from hypotheses.

For three-POV Sumo replay diagnosis and counterfactual analysis, use the
[Sumo Replay Analysis project skill](.agents/skills/sumo-replay-analysis/SKILL.md).

Before combat-model or combo experiments, read
[the current research brief](research/combo-research-brief.md). It records the
user's objective, constraints, hints and methodological corrections. The current
long-term target is own reach at the next **native fresh-damage window**, while
denying return hits; it does not require continuous own reach during immunity.
Delaying clicks does not redefine that window. Score missed windows separately
from the primary matched improvement gate below; do not reject only candidates
with a stricter criterion than W-tap. Preserve old evidence under its original
metric definition.

Use the main lab for experiments. Check live status before deployment. The
owner authorizes restarting the main server even during their own duel.
Research policies use only their own client observations. Other POVs are
offline evidence.

The overall 1.8.9 goal covers both boxing and sumo. Target the provisional
MMC sprint-reset preset first and measure transfer to other knockback profiles.
Isolate one skill at a time before integrating it. Score boxing maintenance and
sumo ring-outs under their own win conditions.

Use the [recorded MMC 13×13 platform](research/mmc-arena/README.md) as
the default in sumo play, training and transfer tests. Its 137 blocks include
two gold spawn markers eight blocks apart. Preserve the original ring size
and opening when comparing archived evidence.

Keep controller examples and held-out calendars separate, with a frozen split
recorded before evaluation. A new jitter seed on an example calendar is NOT
held-out calendar transfer. Never add examples from a held-out calendar; after
using a calendar to diagnose or tune a controller, label it development instead
of silently retaining a pristine-held-out claim. Report previously exposed
never-example calendars separately from prospective sealed validation.

## Mandatory W-tap comparison gate

ALWAYS compare every controller/search result against plain W-tap under identical
settings, in BOTH the model and native Minecraft. Match reach, both RTTs, combat
profile, CPS ceiling, reaction/aim settings, duration, starting/acquisition state,
opening handicap and metric. Use several seeds/tick phases or independent native
runs and report the spread. Hold-W is an additional control, not a replacement
for W-tap. Every results table MUST contain a W-tap baseline column; explicitly
mark historical/unmatched evidence as unqualified until backfilled.

The current primary task is MAINTENANCE AFTER ESTABLISHMENT, not acquisition.
Use three consecutive unanswered fresh hits after the defender starts clicking
as the default landmark; show sensitivity at five and ten. From that landmark,
report completed hit-to-hit cycles, accepted-return failures (including damage
upgrades), exposure and right-censoring, and the distribution of remaining fresh
hits/time. Entry rate is a separate result, never the main promotion gate.
Report return-only survival and a separate inactivity-qualified version; the
latter ends at an inter-fresh gap greater than twice the declared native hurt
window plus100ms. This is an activity proxy, not a POV reach certificate.

Model candidate and W-tap must fork the SAME COMPLETE mid-combo state, queues,
clock and primed observation history. Do not select survivors separately in each
arm and claim a causal comparison. Native repeats share a declared acquisition
protocol and measured seed-state/timing bins; independent runs are NOT identical
full-state forks. Report that limitation. Historical results retain their
original fixed-start/first-return metric; rescores must be explicitly labeled.
Per-cycle failure fractions are descriptive; correlated cycles in one long run
are not independent trials and zero observed failures is not proof of infinity.
Privileged current-state/native and future-calendar/model oracles are authorized
research diagnostics, clearly labeled; legal bots still use own observations.

If a controller/search is not clearly beating W-tap on this task across the
tested spread, STOP adding controller features. Diagnose why W-tap does as well:
what it gets right about inputs, contact drag, sprint state, spacing, knockback
and timing that the candidate misses. Do not treat complexity, a lone favorable
run, an oracle label or a stricter metric applied to only one side as progress.
Resume development only from a concrete baseline-grounded causal diagnosis.
