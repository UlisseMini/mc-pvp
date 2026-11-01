# Project guidance

Help the non-programmer user build and understand the project. Apply ordinary
Git, coding and verification practices without requiring them to manage those
details. Explain results plainly and distinguish tested behavior from hypotheses.

Before combat-model or combo experiments, read
[the current research brief](research/combo-research-brief.md). It records the
user's objective, constraints, hints and methodological corrections. The current
long-term target is own reach at the next **native fresh-damage window**, while
denying return hits; it does not require continuous own reach during immunity.
Delaying clicks does not redefine that window. Score missed windows separately
from the primary matched improvement gate below; do not reject only candidates
with a stricter criterion than W-tap. Preserve old evidence under its original
metric definition.

Check live status before deployment. Do not interrupt a human duel. Research
policies use only their own client observations; other POVs are offline evidence.

## Mandatory W-tap comparison gate

ALWAYS compare every controller/search result against plain W-tap under identical
settings, in BOTH the model and native Minecraft. Match reach, both RTTs, combat
profile, CPS ceiling, reaction/aim settings, duration, starting/acquisition state,
opening handicap and metric. Use several seeds/tick phases or independent native
runs and report the spread. Hold-W is an additional control, not a replacement
for W-tap. Every results table MUST contain a W-tap baseline column; explicitly
mark historical/unmatched evidence as unqualified until backfilled.

The restricted task and primary improvement gate is: maximize fresh hits in a
fixed time without taking a return hit. Report fresh hits before the first
accepted return and whether the full horizon had zero returns; keep longest
unanswered fresh runs and stricter POV/readiness checks as separate diagnostics.
Use the same starting state and scoring window for the candidate and W-tap.

If a controller/search is not clearly beating W-tap on this task across the
tested spread, STOP adding controller features. Diagnose why W-tap does as well:
what it gets right about inputs, contact drag, sprint state, spacing, knockback
and timing that the candidate misses. Do not treat complexity, a lone favorable
run, an oracle label or a stricter metric applied to only one side as progress.
Resume development only from a concrete baseline-grounded causal diagnosis.
