# Native mechanism validation

30s is a finite maintenance test, never proof of an infinite combo. Fixed trials screen mechanisms, not tournament ranking. No fitted constants after seeing outcomes.

| Match | Wtap opponent / scenario | RTT A/B | Score A–B | Fresh hits A/B | Longest continuous denial with ≥3 fresh hits | Model checks |
|---|---|---|---|---|---|---|
| m0359 | science-motion / science-still-probe | 0/0 | 0–0 | 0/0 | 0.00s, 0 hits | pass |
| m0360 | science-wtap / science-still-probe | 0/0 | 30–0 | 30/0 | 29.30s, 30 hits | pass |
| m0361 | science-wtap / science-still-click | 0/0 | 29–0 | 29/0 | 24.25s, 25 hits | pass |
| m0362 | science-wtap / science-hold-probe | 0/0 | 42–0 | 42/0 | 0.00s, 0 hits | pass |
| m0363 | science-wtap / science-hold-opening | 0/0 | 68–41 | 47/33 | 1.30s, 3 hits | pass |
| m0364 | science-wtap / science-hold-click | 0/0 | 61–39 | 45/31 | 0.00s, 0 hits | pass |
| m0365 | science-wtap / science-still-probe | 100/100 | 26–0 | 26/0 | 29.10s, 26 hits | pass |
| m0366 | science-wtap / science-still-click | 100/100 | 26–0 | 26/0 | 29.25s, 26 hits | pass |
| m0367 | science-wtap / science-hold-probe | 100/100 | 42–0 | 42/0 | 0.00s, 0 hits | FAIL / incomplete |
| m0368 | science-wtap / science-hold-opening | 100/100 | 51–42 | 44/35 | 0.00s, 0 hits | pass |
| m0369 | science-wtap / science-hold-click | 100/100 | 53–44 | 43/39 | 0.00s, 0 hits | pass |

These checks condition on measured native pre-state. A full duel prediction remains unvalidated.

| Equation/check | Samples | Outside tolerance |
|---|---:|---:|
| interpolation | 11397 | 0 |
| reach_classification | 11356 | 3 |
| movement_position | 12330 | 0 |
| movement_velocity | 12330 | 0 |
| click_drag | 2079 | 0 |
| click_drag_during_visible_hurt | 1609 | 0 |
| hit_acceptance | 2023 | 0 |
| server_motion_restore | 512 | 0 |
| knockback_packet | 512 | 0 |
