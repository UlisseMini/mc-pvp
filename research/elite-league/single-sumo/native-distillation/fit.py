"""Measure the data requirements of action cloning and score distillation."""

import math
import multiprocessing as mp
import json
import sys

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from prepare import CONFIG, OUT, write_json

TEMP = .12
torch.set_num_threads(1)
torch.set_num_interop_threads(1)


class Student(nn.Module):
    def __init__(self, width=8):
        super().__init__()
        self.encoder = nn.Linear(22, width)
        self.output = nn.Linear(width, 5)

    def forward(self, x):
        return self.output(F.softsign(self.encoder(x)))


def heads(action):
    return torch.stack((action % 4, (action // 4) % 3,
                        (action // 12) % 2, action // 24), dim=-1)


def actions(z, ground):
    walk, sprint, click, jump, side = z.unbind(-1)
    return torch.stack((torch.where(walk < -.65, 3, torch.where(walk > 0, 1 + (sprint > 0), 0)),
                        torch.where(side < -.45, 0, torch.where(side > .45, 2, 1)),
                        click > 0, ground & (jump > 0)), dim=-1)


def ordinal_loss(z, lo, hi, left, right):
    a, b = (lo - z) / TEMP, (z - hi) / TEMP
    middle = F.logsigmoid(-a) + F.logsigmoid(-b) + math.log(-math.expm1((lo - hi) / TEMP))
    return -torch.where(left, F.logsigmoid(a), torch.where(right, F.logsigmoid(b), middle))


def hard_loss(z, y, ground):
    key, side, click, jump = y.unbind(-1)
    walking = (key == 1) | (key == 2)
    w = ordinal_loss(z[:, 0], -.65, 0., key == 3, walking)
    s = F.softplus(torch.where(key == 2, -z[:, 1], z[:, 1]) / TEMP) * walking
    a = F.softplus(torch.where(click.bool(), -z[:, 2], z[:, 2]) / TEMP)
    j = F.softplus(torch.where(jump.bool(), -z[:, 3], z[:, 3]) / TEMP) * ground
    d = ordinal_loss(z[:, 4], -.45, .45, side == 0, side == 2)
    return ((w + s + a + j + d) / 4).mean()


class Panel:
    def __init__(self):
        self.meta = json.loads((OUT / "manifest.json").read_text())["fixtures"]
        with np.load(OUT / "native.npz") as data:
            self.x = torch.from_numpy(data["x"])
            self.scores = torch.from_numpy(data["scores"])
            self.y = heads(torch.from_numpy(data["y"].astype(np.int64)))
            self.cuts = data["cuts"]
        self.ground = self.x[:, 8] > 0
        self.matches = sorted({r["match"] for r in self.meta})
        self.match_rows = {m: np.concatenate([np.arange(a, b) for f, a, b in
                          zip(self.meta, self.cuts[:-1], self.cuts[1:]) if f["match"] == m])
                           for m in self.matches}
        self.splits = {split: sorted({r["match"] for r in self.meta if r["split"] == split})
                       for split in ("train", "development", "test", "opponent_test")}
        self.gid = np.empty(len(self.x), dtype=int)
        for i, m in enumerate(self.matches):
            self.gid[self.match_rows[m]] = i

    def rows(self, matches):
        return torch.from_numpy(np.concatenate([self.match_rows[m] for m in matches]))

    @torch.no_grad()
    def measure(self, rows, predicted, mode):
        true = self.y[rows]
        correct = true == predicted
        joint = correct.all(1).numpy()
        gid = self.gid[rows.numpy()]
        counts = np.bincount(gid)
        game_agreements = np.bincount(gid, weights=joint)[counts > 0] / counts[counts > 0]
        unusual = true != mode
        strafe_error = ~correct[:, 1]
        strafe_margin = (self.scores[rows, 4].abs() - .45).abs()
        return dict(joint_correct=float(joint.mean()), game_weighted=float(game_agreements.mean()),
                    exact_games=int(np.count_nonzero(game_agreements == 1)),
                    perfect_oracle_available=bool(torch.equal(actions(self.scores[rows], self.ground[rows]), true)),
                    per_action=correct.float().mean(0).tolist(),
                    per_class=[{str(int(k)): [int((true[:, h] == k).sum()),
                                              float(correct[true[:, h] == k, h].float().mean())]
                                for k in torch.unique(true[:, h])} for h in range(4)],
                    unusual_recall=[float(correct[mask, i].float().mean()) if mask.any() else None
                                    for i, mask in enumerate(unusual.T)],
                    strafe_errors_near_boundary=int((strafe_error & (strafe_margin < .1)).sum()),
                    errors=int((~joint).sum()), rows=len(rows), fights=len(game_agreements))


def calibrate(model, x, z, score_scale, passes):
    """Refine the small score regressor with full-batch curvature estimates."""
    opt = torch.optim.LBFGS(model.parameters(), lr=1., max_iter=passes, history_size=20,
                           line_search_fn="strong_wolfe", tolerance_change=1e-9)

    def closure():
        opt.zero_grad()
        loss = ((model(x) - z) / score_scale).square().mean()
        loss.backward()
        return loss

    opt.step(closure)


def fit_models(seed, followup):
    panel = Panel()
    torch.manual_seed(seed)
    all_matches = np.array(panel.splits["train"])
    permutation = np.random.default_rng(seed).permutation(all_matches)
    train_rows = panel.rows(all_matches)
    validation = panel.rows(panel.splits["development"])
    tests = {k: panel.rows(panel.splits[k]) for k in ("test", "opponent_test")}
    mode = panel.y[train_rows].mode(0).values
    baseline = {k: panel.measure(rows, mode.expand(len(rows), -1), mode) for k, rows in tests.items()}
    result = []
    budgets = [64, 256, "all"] if followup else CONFIG["training_games"]
    methods = ("wide_actions", "balanced_scores") if followup else ("recorded_actions", "teacher_scores")
    for budget in budgets:
        train = panel.rows(permutation if budget == "all" else permutation[:budget])
        tf = panel.x[train]
        mean, scale = tf.mean(0), tf.std(0, unbiased=False).clamp_min(.1)
        x = (tf - mean) / scale
        vx = (panel.x[validation] - mean) / scale
        yt, yv = panel.y[train], panel.y[validation]
        gt, gv = panel.ground[train], panel.ground[validation]
        zt, zv = panel.scores[train], panel.scores[validation]
        for method in methods:
            from_actions = method.endswith("actions")
            score_scale = zt.std(0).clamp_min(.15) if method == "balanced_scores" else torch.ones(5)
            torch.manual_seed(seed)
            model = Student(width=64 if method == "wide_actions" else 8)
            optimizer = torch.optim.Adam(model.parameters(), lr=.012)
            batches = math.ceil(len(x) / 1024)
            epochs = min(350, max(30, 4000 // batches))
            best, state = float("inf"), None
            for epoch in range(epochs):
                if epoch in (epochs // 2, epochs * 4 // 5):
                    optimizer.param_groups[0]["lr"] *= .35
                model.train()
                for batch in torch.randperm(len(x)).split(1024):
                    guess = model(x[batch])
                    loss = (hard_loss(guess, yt[batch], gt[batch]) if from_actions
                            else ((guess - zt[batch]) / score_scale).square().mean())
                    optimizer.zero_grad()
                    loss.backward()
                    optimizer.step()
                if (epoch + 1) % 5 == 0 or epoch + 1 == epochs:
                    with torch.no_grad():
                        guess = model(vx)
                        loss = (hard_loss(guess, yv, gv) if from_actions
                                else ((guess - zv) / score_scale).square().mean())
                        if loss.item() < best:
                            best, state = loss.item(), {k: v.detach().clone() for k, v in model.state_dict().items()}
            model.load_state_dict(state)
            if not from_actions:
                calibrate(model, x, zt, score_scale, 160)
                with torch.no_grad():
                    if ((model(vx) - zv) / score_scale).square().mean().item() > best:
                        model.load_state_dict(state)
            with torch.no_grad():
                summary = {}
                for k, rows in tests.items():
                    prediction = model((panel.x[rows] - mean) / scale)
                    summary[k] = panel.measure(rows, actions(prediction, panel.ground[rows]), mode)
                    summary[k]["teacher_score_rmse"] = float(F.mse_loss(prediction, panel.scores[rows]).sqrt())
                summary["train_agreement"] = float((actions(model(x), gt) == yt).all(1).float().mean())
            result.append(dict(budget=budget, method=method, train_rows=len(x), **summary))
            torch.save(dict(weights=model.state_dict(), mean=mean, scale=scale),
                       OUT / f"{seed}-{budget}-{method}.pt")
            print(seed, budget, method, len(x), round(summary["test"]["joint_correct"], 5),
                  round(summary["opponent_test"]["joint_correct"], 5), flush=True)
            path = OUT / (f"followup-{seed}.json" if followup else f"seed-{seed}.json")
            write_json(path, dict(results=result, baseline=baseline))


if __name__ == "__main__":
    followup = "--followup" in sys.argv
    seeds = list(map(int, [a for a in sys.argv[1:] if a != "--followup"])) or CONFIG["fit_seeds"]
    with mp.get_context("spawn").Pool(len(seeds)) as pool:
        pool.starmap(fit_models, [(seed, followup) for seed in seeds])
