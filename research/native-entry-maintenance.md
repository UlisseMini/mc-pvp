# Native mechanism validation

30s is a finite maintenance test, never proof of an infinite combo. Fixed trials screen mechanisms, not tournament ranking. No fitted constants after seeing outcomes.

| Match | Wtap opponent / scenario | RTT A/B | Score A–B | Fresh hits A/B | Longest continuous denial with ≥3 fresh hits | Model checks |
|---|---|---|---|---|---|---|
| m0376 | science-hold-probe / science-hold-probe | 0/0 | 0–0 | 0/0 | 0.00s, 0 hits | pass |
| m0377 | science-hold-probe / science-hold-probe | 100/100 | 0–0 | 0/0 | 0.00s, 0 hits | pass |
| m0378 | science-wtap / science-wake-probe | 0/0 | 39–0 | 39/0 | 8.35s, 10 hits | pass |
| m0379 | science-wtap / science-wake-click | 0/0 | 54–36 | 40/30 | 4.10s, 5 hits | pass |
| m0380 | science-wtap / science-wake-probe | 100/100 | 37–0 | 37/0 | 8.25s, 9 hits | pass |
| m0381 | science-wtap / science-wake-click | 100/100 | 44–29 | 38/26 | 8.14s, 9 hits | pass |

These checks condition on measured native pre-state. A full duel prediction remains unvalidated.

| Equation/check | Samples | Outside tolerance |
|---|---:|---:|
| interpolation | 5144 | 0 |
| reach_classification | 5003 | 0 |
| native_look_reach_classification | 5002 | 0 |
| movement_position | 5448 | 0 |
| movement_velocity | 5448 | 0 |
| click_drag | 864 | 0 |
| click_drag_during_visible_hurt | 684 | 0 |
| velocity_overwrite | 210 | 0 |
| hit_acceptance | 848 | 0 |
| server_motion_restore | 210 | 0 |
| knockback_packet | 210 | 0 |
