---
name: sumo-replay-analysis
description: Diagnose archived Minecraft PvP Lab sumo matches from player, bot, and server recordings. Use for manual trade or ring-out analysis, missed knockback brakes, sprint and W-tap timing, and local counterfactuals about floor or reach in the next exchange.
---

# Analyze a Sumo replay

Read the applicable research brief and follow the project W-tap requirements
if proposing a controller experiment. This skill is for offline replay diagnosis.
Keep the recorded settings, footprint, timing, and replay sources with each case.

From the repository root, list the fresh launches and candidate comparisons.

```sh
python3 research/sumo-replay-analysis/inspect_replay.py m9153 --hit 49 50 52 --out build/sumo-review
```

Read the [tool guide and worked cases](../../../research/sumo-replay-analysis/README.md)
for the evidence fields, provenance, assumptions, and important failure modes.
The tool accepts arbitrary lab match IDs with a complete three-POV archive and
the recorded MMC arena. Use the original capture or a compatible replay tool
for other profiles. For a single public-server POV, omit private opponent
claims and bound the analysis by the actual captured fields.

## Work backward from the outcome

- Find the loser's last supported portion of the trajectory, the first frame
  of the final unsupported run, and the descent through platform height.
  Locate the launch that produced that trajectory, then inspect the one or
  two previous exchanges and an in-match example of the opposite behavior.
  Check the actual two-dimensional path. A slide exceeding a straight-line
  margin can still curve back onto the platform. Six movements can also
  end before the decisive descent. Later server hits may arrive once the
  player is committed to a fall.
- Anchor observations to server hit indices and receiver-local times. Link
  hit → emitted S12 → applied S12 → motion update. An accepted C02, a local
  40% speed cut, and a sprint-powered outgoing launch are three distinct
  events. Compare before and after motion and link the accepted hit
  independently where possible. Read the other client's actual rendered
  selection, sprint flag, and press timing to explain its response.
- Identify the earliest actionable window. Distinguish selected sprint-off
  presses, no click at an aimed sprint-on sample, missed aim at reachable
  geometry, and presses after reach had expired. Check the actual movement
  W state and recorded key transitions. W held again can coexist with
  sprint off on a click. Treat a few milliseconds of sample lead as
  preparation and timing evidence, with no claim about reaction time.
- When a miss timer appears, find the first miss and test what clearing the
  timer could affect. Later presses already outside reach provide weak
  evidence for an independent timer cost. Aimed samples certify one time
  only. Nearest-hitbox distance can exclude reach. A distance inside the
  limit still needs the actual aimed ray.

## Test a specific alternative

- Start with one meaningful edit. Add a local brake where aim, timer, and
  sprint support it, correct sprint at a selected press, test aim and sprint
  jointly where both failed, or remove a real brake to show its value. Use the
  archived profile's same-hit sprint strength swap as a separate mechanism
  check. It changes the victim impulse while keeping contact assumptions.
- Compare on the same verified movement prefix. Give the comparable count
  and peak slide, then name the first relevant change in closest reach or
  landing support. For the previous-hit test, carry the spatial shift through
  the next recorded velocity overwrite. Report actual and edited floor
  margin, the unchanged follow-up travel, and any earlier changed contact
  geometry. Check the alternate branch at the factual descent when available.
  Give qualitative weight to margins within a few hundredths of an edge.
- Show timing sensitivity when early versus late placement changes the
  answer. Do not add per-fix block savings or infer that the same server
  damage follows a changed click. The local replay holds later movement,
  attack decisions, launches, and replica history fixed. A changed reach
  window creates a possible new branch; it does not prove hit acceptance.
  The same branch stops at a changed landing or a later selected press
  whose geometry may no longer work. Treat open-loop results as conditional.
- Lead with the exchange, actual evidence and then the comparison, ending
  with the lesson. Keep preparation cost, ability to land a brake, space
  gained, and match outcome distinct. Keep useful negative examples.
  If tuning on old coaching examples, preserve their original metrics and
  use separate untouched matches for any later accuracy evaluation.
