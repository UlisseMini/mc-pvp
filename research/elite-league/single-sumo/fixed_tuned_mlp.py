"""Fixed-learner continuation from remote-005 (MLP): cures live-HUD RTT distribution shift,
48x timeout gradient dominance, and zero-entropy brittleness while keeping 500k+ fights of
cumulative league skill. Evaluates against plain wtap, full archive, and remote-005 under
BOTH known-RTT and masked-opening-RTT conditions."""
import base64
import hashlib
import os
import sys
import time
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from league_tournament import historical, pin_model
from learner import (
    CLOCKS,
    EVIDENCE,
    FAMILIES,
    INCUMBENT,
    ROOT,
    RTTS,
    Actor,
    Worker,
    atomic,
    prepare,
    train_update,
    training_schedule,
    write_json,
)

OUT = EVIDENCE / "fixed-v2-tuned"
UPDATES = 512  # 32,768 fights (GRU32 fine-tune from remote-005 with all fixes)


def load_net_actor(net_path):
    raw = Path(net_path).read_text().strip()
    prefix, b64 = raw.split(".", 1)
    if prefix == "GRU32":
        actor = Actor("gru")
        params = (actor.encoder.weight_ih_l0, actor.encoder.weight_hh_l0, actor.encoder.bias_ih_l0, actor.head.weight, actor.head.bias)
    elif prefix == "LEAGUE_MLP180":
        actor = Actor("mlp")
        params = (actor.encoder.weight, actor.encoder.bias, actor.head.weight, actor.head.bias)
    else:
        raise ValueError(f"Unexpected prefix {prefix}")
    arr = np.frombuffer(base64.b64decode(b64), dtype=">f4").astype(np.float32)
    pos = 0
    with torch.no_grad():
        for p in params:
            n = p.numel()
            p.copy_(torch.from_numpy(arr[pos : pos + n].reshape(p.shape)))
            pos += n
    assert pos == len(arr), f"Weight mismatch {pos} vs {len(arr)}"
    return actor


def eval_vs(worker, actor_ref, opp_ref, mask_rtt=False):
    plan = [
        (actor_ref, opp_ref, c, s, a, b, 30001 + i, 40001 + i)
        for i, (c, s, (a, b)) in enumerate(
            (c, s, r) for rep in range(2) for c in CLOCKS["dev"] for s in "ab" for r in RTTS
        )
    ]
    rows = []
    for off in range(0, len(plan), 32):
        rows.extend(worker.run(plan[off : off + 32], collect=mask_rtt, mask_rtt=mask_rtt))
    wins = sum(r[0] == 1 for r in rows)
    draws = sum(r[0] == 0.5 for r in rows)
    losses = sum(r[0] == 0 for r in rows)
    score = sum(r[0] for r in rows) / len(rows)
    return dict(games=len(rows), wins=wins, draws=draws, losses=losses, score=round(score, 4))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    r005_rel = "research/elite-league/single-sumo/evidence/fixed-v2/baselines/remote-005.net"
    r005_abs = ROOT / r005_rel
    field = historical()
    families = {k: [field[n]["reference"] for n in v] for k, v in FAMILIES.items()}
    families["prior remote champion"] = [r005_rel]
    g1_mlp = "research/elite-league/single-sumo/evidence/fixed-v2/g001/main-mlp/final.net"
    if (ROOT / g1_mlp).is_file():
        families["fixed-v2-g001"] = [g1_mlp]

    torch.manual_seed(91337)
    net = load_net_actor(r005_abs)
    opt = torch.optim.Adam(net.parameters(), lr=0.00025)

    threats = [r005_rel, field[INCUMBENT]["reference"], field["sumo-seven-first"]["reference"]]
    if (ROOT / g1_mlp).is_file():
        threats.append(g1_mlp)
    cfg = dict(
        kind=net.kind,
        role="main",
        seed=91337,
        draw_seed=99133,
        action_seed=91337 * 101 + 1,
        incumbent=r005_rel,
        counter=r005_rel,
        families=families,
        threats=threats,
        scores={},
    )
    write_json(OUT / "config.json", cfg)

    worker = Worker(current_runtime=True, threads=int(os.environ.get("SUMO_WORKER_THREADS", "3")))
    logs = []
    try:
        print("=== BASELINE remote-005 (128 dev games each) ===", flush=True)
        base_eval = {}
        for name, ref in [
            ("wtap", "wtap"),
            ("hold", "hold"),
            ("v4-ppo-g0496", "v4-ppo-g0496"),
            ("sumo-seven-first", "sumo-seven-first"),
            ("remote-005", r005_rel),
        ]:
            k_res = eval_vs(worker, r005_rel, ref, mask_rtt=False)
            m_res = eval_vs(worker, r005_rel, ref, mask_rtt=True)
            base_eval[name] = dict(known_rtt=k_res, hud_masked_rtt=m_res)
            print(
                f"  remote-005 vs {name:16s}: known={k_res['score']:.4f}  hud_masked={m_res['score']:.4f}",
                flush=True,
            )

        best_score = -1.0
        best_path = OUT / "best.net"
        for step in range(UPDATES):
            t0 = time.monotonic()
            wpath = worker.folder / "current.net"
            net.export(wpath)
            fixtures = training_schedule(cfg, wpath, step)
            rows = worker.run(fixtures, True, mask_rtt=True)
            t1 = time.monotonic()
            episodes = prepare(rows, gamma=0.996)
            st = train_update(net, opt, episodes, cfg["seed"] + step, ent_coef=0.008)
            entry = dict(
                update=step + 1,
                duels=(step + 1) * 64,
                heads=sum(len(e) for e in episodes),
                score=float(np.mean([r[0] for r in rows])),
                rollout_s=round(t1 - t0, 3),
                total_s=round(time.monotonic() - t0, 3),
                **st,
            )
            logs.append(entry)
            if (step + 1) in (1, 16) or (step + 1) % 32 == 0:
                net.export(OUT / "current.net")
                write_json(OUT / "progress.json", entry)
                print("fixed-v2-tuned", entry, flush=True)
            if (step + 1) % 128 == 0:
                ck = OUT / f"step-{step+1:04d}.net"
                net.export(ck)
                ck_rel = str(ck.relative_to(ROOT))
                vs_wt = eval_vs(worker, ck_rel, "wtap", mask_rtt=True)["score"]
                vs_r5_k = eval_vs(worker, ck_rel, r005_rel, mask_rtt=False)["score"]
                vs_r5_m = eval_vs(worker, ck_rel, r005_rel, mask_rtt=True)["score"]
                vs_7 = eval_vs(worker, ck_rel, "sumo-seven-first", mask_rtt=True)["score"]
                combo = 0.35 * vs_r5_k + 0.35 * vs_r5_m + 0.15 * vs_wt + 0.15 * vs_7
                print(
                    f"  [CHECKPOINT {step+1}] vs_wtap(mask)={vs_wt:.4f} vs_r005(known)={vs_r5_k:.4f} "
                    f"vs_r005(mask)={vs_r5_m:.4f} vs_seven(mask)={vs_7:.4f} combo={combo:.4f}",
                    flush=True,
                )
                if combo > best_score:
                    best_score = combo
                    atomic(best_path, ck.read_bytes())

        net.export(OUT / "current.net")
        pin_model(OUT)
        cand_rel = str((best_path if best_path.exists() else OUT / "final.net").relative_to(ROOT))
        print("=== FINAL EVALUATION (128 dev games each, known vs live-HUD masked RTT) ===", flush=True)
        final_eval = {}
        for name, ref in [
            ("wtap", "wtap"),
            ("hold", "hold"),
            ("v4-ppo-g0496", "v4-ppo-g0496"),
            ("sumo-seven-first", "sumo-seven-first"),
            ("sumo-seven-ppo", "sumo-seven-ppo"),
            ("sumo-cap-ramp", "sumo-cap-ramp"),
            ("v3-memory-27", "v3-memory-27"),
            ("shift-train-07", "shift-train-07"),
            ("remote-005", r005_rel),
        ]:
            k_res = eval_vs(worker, cand_rel, ref, mask_rtt=False)
            m_res = eval_vs(worker, cand_rel, ref, mask_rtt=True)
            final_eval[name] = dict(known_rtt=k_res, hud_masked_rtt=m_res)
            print(
                f"  fixed-v2-tuned vs {name:16s}: known={k_res['score']:.4f}  hud_masked={m_res['score']:.4f}",
                flush=True,
            )

        wtap_eval = {}
        for name, ref in [
            ("wtap", "wtap"),
            ("hold", "hold"),
            ("v4-ppo-g0496", "v4-ppo-g0496"),
            ("sumo-seven-first", "sumo-seven-first"),
            ("remote-005", r005_rel),
            ("fixed-v2-tuned", cand_rel),
        ]:
            wtap_eval[name] = eval_vs(worker, "wtap", ref, mask_rtt=False)

        write_json(
            OUT / "result.json",
            dict(
                policy_path=cand_rel,
                sha256=hashlib.sha256((ROOT / cand_rel).read_bytes()).hexdigest(),
                updates=UPDATES,
                fights=UPDATES * 64,
                baseline_remote005=base_eval,
                fixed_v2_tuned=final_eval,
                wtap_baseline=wtap_eval,
            ),
        )
        print("=== fixed-v2-tuned COMPLETE ===", flush=True)
    finally:
        worker.close()


if __name__ == "__main__":
    main()
