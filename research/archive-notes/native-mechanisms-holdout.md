# Native mechanism validation

30s is a finite maintenance test, never proof of an infinite combo. Fixed trials screen mechanisms, not tournament ranking. No fitted constants after seeing outcomes.

| Match | Wtap opponent / scenario | RTT A/B | Score A–B | Fresh hits A/B | Longest continuous denial with ≥3 fresh hits | Model checks |
|---|---|---|---|---|---|---|
| m0370 | science-wtap / science-hold-probe | 20/100 | 42–0 | 42/0 | 0.00s, 0 hits | pass |
| m0371 | science-wtap / science-hold-opening | 20/100 | 53–44 | 43/37 | 0.00s, 0 hits | pass |
| m0372 | science-wtap / science-hold-click | 20/100 | 47–43 | 42/35 | 0.00s, 0 hits | pass |
| m0373 | science-wtap / science-hold-probe | 100/20 | 44–0 | 44/0 | 1.55s, 3 hits | pass |
| m0374 | science-wtap / science-hold-opening | 100/20 | 57–41 | 42/33 | 0.00s, 0 hits | FAIL / incomplete |
| m0375 | science-wtap / science-hold-click | 100/20 | 59–48 | 42/39 | 0.00s, 0 hits | pass |

These checks condition on measured native pre-state. A full duel prediction remains unvalidated.

| Equation/check | Samples | Outside tolerance |
|---|---:|---:|
| interpolation | 7021 | 0 |
| click_drag | 1717 | 0 |
| click_drag_during_visible_hurt | 1425 | 0 |
| velocity_overwrite | 404 | 0 |
| hit_acceptance | 1708 | 0 |
| server_motion_restore | 399 | 0 |
| reach_classification | 6619 | 1 |
| native_look_reach_classification | 6617 | 0 |
| movement_position | 7204 | 0 |
| movement_velocity | 7204 | 0 |
| knockback_packet | 399 | 0 |
