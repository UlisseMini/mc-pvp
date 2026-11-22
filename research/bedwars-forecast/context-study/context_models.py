"""Fit a causal gate over full-resolution position forecasts from player history."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
import torch
from torch import nn

PARENT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT))
from forecast import HORIZONS, PlayerModel, cube_mass, load_games, observed, take, write_csv
from heatmap import (Heatmap, MetaHistory, data_for, history_dist, mixture_tiles, raw_mix_scores,
                     scores, supervised, support_for)

EXPERTS = ('frozen heatmap', 'frozen motion', 'stay tightly', 'recent trail', 'older locations', 'route matches', 'home')
STANDARD = ['map only', 'frozen heatmap', 'frozen motion', 'old meta history']
RECENT, MEMORY, ROUTE, HOME = 24, 88, 20, 48
FOLDS = (
    ('game-01', 'game-03', 'game-05', 'game-08'),
    ('game-02', 'game-04', 'game-06', 'game-07'),
)
GATES = {
    'fixed weights': ('constant', slice(None)),
    'current-state gate': ('current', slice(0, 3)),
    'history without route': ('full', [0,1,2,3,4,6]),
    'full personal gate': ('full', slice(None)),
}


def collect_tracks(game):
    seen = defaultdict(list)
    for second, (_, _, players) in game['frames'].items():
        for uid, pose in players.items():
            seen[uid].append((second, *pose[1:4]))
    return {uid: np.asarray(sorted(rows)) for uid, rows in seen.items()}


def windows(path, times, h):
    """Build distributions using only the prefix available at each issue time."""
    n = len(times)
    stamps, xyz = path[:, 0], path[:, 1:]
    upper = np.searchsorted(stamps, times, side='right')
    short = np.arange(RECENT)
    ri = (upper[:, None] - 1 - short).clip(0)
    r_age = times[:, None] - stamps[ri]
    r_available = (upper[:, None] > short) & (r_age < RECENT)
    rw = np.exp(-r_age / 10) * r_available

    old_stamps = np.flatnonzero(stamps % 3 == 0)
    memory_end = np.searchsorted(stamps[old_stamps], times-18, side='right')
    midx = (memory_end[:, None] - 1 - np.arange(MEMORY)).clip(0)
    mi = old_stamps[midx] if len(old_stamps) else np.zeros_like(midx)
    m_age = times[:, None] - stamps[mi]
    mw = np.exp(-m_age.clip(min=0)/185) * (m_age >= 18)
    mw *= memory_end[:, None] > np.arange(MEMORY)

    home = xyz[0]
    usable_home = stamps[0] <= 10 and np.linalg.norm(home[[0,2]]) >= 36
    home_visit = np.flatnonzero((np.linalg.norm((xyz - home)[:, [0,2]], axis=1) <= 15)
                                & (np.abs(xyz[:, 1] - home[1]) <= 9) & (stamps % 2 == 0))
    home_end = np.searchsorted(stamps[home_visit], times, side='right')
    hidx = (home_end[:, None] - 1 - np.arange(HOME)).clip(0)
    hi = home_visit[hidx] if len(home_visit) else np.zeros_like(hidx)
    hw = (home_end[:, None] > np.arange(HOME)).astype(float) * usable_home

    exact_moves = np.linalg.norm(np.diff(xyz[:, [0,2]], axis=0, prepend=xyz[:1, [0,2]]), axis=1)
    stamp_diff = np.diff(stamps, prepend=-1)
    valid_speed = (stamp_diff == 1) & (exact_moves <= 10)
    nearby_speed = exact_moves[ri]
    speed_known = r_available & valid_speed[ri]
    count = speed_known.sum(axis=1).clip(min=1)
    slow = speed_known & (nearby_speed < .45)
    used = np.cumsum(~slow, axis=1) == 0
    slow_run = used.sum(axis=1)
    avg_speed = (nearby_speed * speed_known).sum(axis=1) / count
    current = xyz[upper-1]
    mean_pos = np.sum(xyz[ri] * rw[:, :, None], axis=1) / rw.sum(axis=1)[:, None]
    distance_to_home = np.linalg.norm((current-home)[:, [0,2]], axis=1)
    home_freq = ((np.linalg.norm((xyz[ri] - home)[:, :, [0,2]],axis=2) <= 15) * r_available).sum(1)
    recent_tile = ((xyz[ri][:, :, [0,2]] + 128)//16).astype(np.int32)
    current_tile = ((current[:, [0,2]] + 128)//16).astype(np.int32)
    old_tile = ((xyz[mi][:, :, [0,2]] + 128)//16).astype(np.int32)
    share_recent = (((recent_tile == current_tile[:, None]).all(axis=2)) * r_available).sum(1) / r_available.sum(1)
    share_old = (((old_tile == current_tile[:, None]).all(axis=2)) * mw).sum(1) / mw.sum(1).clip(min=1e-6)
    features = np.column_stack((
        avg_speed / 4, slow.sum(1)/count, slow_run/RECENT,
        np.linalg.norm((current-mean_pos)[:, [0,2]], axis=1)/30,
        r_available.sum(1)/RECENT, np.log1p(mw.sum(1))/5,
        share_recent, share_old, share_recent-share_old,
        np.full(n, usable_home), distance_to_home / 80 * usable_home,
        home_freq / r_available.sum(1) * usable_home, np.log1p(hw.sum(1))/4))
    return [(xyz[ri], rw, [1.1+.016*h, .55+.006*h, 1.1+.016*h]),
            (xyz[mi], mw, [1.3+.020*h, .67+.006*h, 1.3+.020*h]),
            (xyz[hi], hw, [1.05+.009*h, .55+.004*h, 1.05+.009*h])], features


def route_matches(rows, query, track, horizon):
    seen = observed(rows)
    reference = track[seen[track] & (rows['time'][track] % 3 == 0)]
    batch = len(query)
    if not len(reference):
        no_match = np.tile([0, 7/6, 7/6], (batch, 1))
        return np.zeros((batch, 1, 3)), np.zeros((batch, 1)), no_match

    def geometry(ix):
        f = rows['features'][ix]
        # Both 2s (f[:, 0:3]) and 8s (f[:, 3:6]) velocities are in the same heading-local frame.
        return np.column_stack((rows['xyz'][ix]/[13, 6, 13],
                                f[:, [0, 2]]/2.2, f[:, 3:6]/3.4, f[:, 8]/2))

    current, earlier = geometry(query), geometry(reference)
    # A reference at s is admitted only when its future endpoint has arrived.
    age = rows['time'][query,None] - rows['time'][reference] - horizon
    valid = age >= 0
    ds = ((current[:, None] - earlier[None])**2).sum(axis=2)
    rank = np.where(valid, ds + age.clip(min=0)/170, 1e8)
    width = min(ROUTE, len(reference))
    near = np.argpartition(rank, width-1, axis=1)[:, :width]
    best = np.take_along_axis(rank, near, axis=1)
    support = best < 1e8
    distance = best.min(1)
    weight = np.exp(-.55*(best - distance[:, None])) * support
    count = support.sum(1)
    mean = np.where(count > 0, np.where(support, best, 0).sum(1) / count.clip(min=1), 49)
    features = np.column_stack((np.log1p(count)/3.1,
                                np.sqrt(distance.clip(max=49)) / 6,
                                np.sqrt(mean.clip(max=49)) / 6))
    return rows['future'][reference[near]], weight, features


def kernel_probability(points, weights, scale, actual):
    available = weights.sum(1) > 0
    normalized = weights / weights.sum(1, keepdims=True).clip(min=1e-30)
    # Center on the discrete feet block. Keep narrow support on a stationary
    # player's repeated block, while retaining positive probability nearby.
    parts = np.floor(points)+.5
    fine = cube_mass(parts, np.asarray(scale)[None,None], actual[:, None])
    point_xz = parts[:, :, [0,2]]
    scale_xz = np.asarray(scale)[None,None,[0,2]]
    horizontal = cube_mass(point_xz, scale_xz, actual[:, None, [0,2]])
    lower = ((actual[:, [0,2]] + 128)//16)*16 - 128
    # Evaluate a 16-block horizontal tile by scaling the ordinary one-block CDF integration.
    coarse = cube_mass(point_xz/16, scale_xz/16, lower[:, None, :]/16)
    return np.stack((np.sum(fine*normalized, axis=1),
                     np.sum(coarse*normalized, axis=1),
                     np.sum(horizontal*normalized, axis=1)), axis=1), available


@torch.no_grad()
def prior_probability(net, old_gate, data, query, track):
    logits, enc = net.coarse(data.batch(query))
    label, tile = data.target(query)
    log_xz, log_y = net.fine_logp_parts(enc, data.xyz[query], label, tile, data.features[query])
    fine = log_xz + log_y
    probability = logits.softmax(1)
    adjusted = probability.clone()
    counts = np.zeros(len(query), dtype=int)
    times = data.raw['time'][query]
    buckets = times//30*30
    for cutoff in np.unique(buckets):
        on = buckets == cutoff
        support = support_for(data.raw, track, cutoff)
        counts[on] = len(support)
        if old_gate is None or len(support) < 4:
            continue
        wi = np.flatnonzero(on)
        hist = torch.tensor(history_dist(data.tile[support].numpy()))
        count = torch.full((len(wi),), len(support))
        w = old_gate.weight(data.hindex[query[wi]], count, data.features[query[wi]])[:, None]
        adjusted[wi] = probability[wi]*(1-w) + hist*w
    def columns(logits):
        pair = (-scores(logits, fine, tile)[:, :2]).exp()
        horizontal = (logits.log_softmax(1).gather(1, tile[:, None])[:, 0] + log_xz).exp()
        return torch.column_stack((pair, horizontal)).numpy()

    prior = columns(logits)
    old = columns(adjusted.log())
    return prior, old, counts


def make_motion(data, games):
    rows = data.raw
    return [PlayerModel(take(rows, (rows['hindex']==hi) & np.isin(rows['game'], games)), h, ablate=False)
            for hi,h in enumerate(HORIZONS)]


def map_only_pool(data, games, seed):
    rng = np.random.default_rng(seed)
    xyz_collection = []
    for game in games:
        rows = data.raw
        pool = rows['future'][(rows['game']==game['id']) & (rows['hindex']==1) & observed(rows)]
        if not len(pool):
            continue
        records = pool[rng.choice(len(pool), 96, replace=len(pool)<96)].copy()
        records[:,1] -= game['base_y']
        xyz_collection.append(records)
    return np.concatenate(xyz_collection)


def get_predictions(data, games, hmap, meta, motion, map_pool, stride=1):
    """Export exact probabilities on matching labels plus the gate's issue-time inputs."""
    data_rows = data.raw
    seen = observed(data_rows)
    all_rows = defaultdict(list)
    for game in games:
        gt = collect_tracks(game)
        here = np.flatnonzero(data_rows['game']==game['id'])
        buckets = defaultdict(list)
        for idx in here:
            buckets[data_rows['uid'][idx], int(data_rows['hindex'][idx])].append(idx)
        for (player, hi), track_list in buckets.items():
            track = np.asarray(track_list)
            query = track[seen[track] & (data_rows['time'][track] % stride == 0)]
            if not len(query):
                continue
            horizon = HORIZONS[hi]
            t = data_rows['time'][query]
            actual = data.label[query].numpy()
            pieces, context = windows(gt[player], t, horizon)
            route_pts, route_w, route_features = route_matches(data_rows, query, track, horizon)
            prior, old, supports = prior_probability(hmap, meta, data, query, track)
            law = motion[hi].predict(take(data_rows, query))['Motion mixture'][0]
            motion_prob = np.exp(-raw_mix_scores(law, mixture_tiles(law), actual,
                                                  data.tile[query].numpy())[:, :2])
            motion_plane = cube_mass(law.centers[:, :, [0,2]], law.scales[:, :, [0,2]],
                                     actual[:, None, [0,2]])
            motion_prob = np.column_stack((motion_prob, (motion_plane*law.weights).sum(1)))
            xyz = data_rows['xyz'][query]
            stay_centers = xyz[:,None]
            stay_ps = np.zeros((len(query), 3))
            for w, scale in ((.68,[.5,.46,.5]),(.24,[1.9,.87,1.9]),(.08,[6.5,2.1,6.5])):
                p, _ = kernel_probability(stay_centers, np.ones((len(query),1)), scale, actual)
                stay_ps += w*p
            experts = [prior, motion_prob, stay_ps]
            available = [np.ones(len(query), dtype=bool) for _ in experts]
            for points, weights, scale in (*pieces[:2], (route_pts, route_w, [2.2+.027*horizon,.8+.007*horizon,2.2+.027*horizon]), pieces[2]):
                p, has = kernel_probability(points, weights, scale, actual)
                experts.append(p)
                available.append(has)
            # The global prior receives the opening floor height and no current player information.
            map_pts = map_pool + [0, game['base_y'], 0]
            map_prob, _ = kernel_probability(np.broadcast_to(map_pts,(len(query),*map_pts.shape)),
                                              np.ones((len(query),len(map_pts))), [7, 4, 7], actual)
            fields = {
                'exact': np.stack(experts,axis=1)[:,:,0], 'tile': np.stack(experts,axis=1)[:,:,1],
                'plane': np.stack(experts,axis=1)[:,:,2],
                'available': np.stack(available,axis=1),
                'features': np.concatenate((data_rows['features'][query], context, route_features),axis=1),
                'baselines': np.stack((map_prob, prior, motion_prob, old),axis=1),
                'game': data_rows['game'][query], 'hindex': data_rows['hindex'][query], 'time': t,
                'player': data_rows['name'][query], 'own': data_rows['own'][query], 'support': supports,
                'late': (t >= game['cut']+10) & (supports >= 8),
            }
            for k,v in fields.items():
                all_rows[k].append(v)
        print(f'Built causal expert probabilities for {game["id"]}',flush=True)
    return {key: np.concatenate(values) for key, values in all_rows.items()}


def concat(parts):
    return {k: np.concatenate([p[k] for p in parts]) for k in parts[0]}


class Gate(nn.Module):
    def __init__(self, mode, enabled, feature_size):
        super().__init__()
        self.mode = mode
        self.channels = np.arange(len(EXPERTS))[enabled]
        self.offset = nn.Embedding(len(HORIZONS), len(self.channels))
        nn.init.zeros_(self.offset.weight)
        used = 17 if mode=='current' else feature_size
        self.layers = (None if mode=='constant' else
                       nn.Sequential(nn.Linear(used+len(HORIZONS),24), nn.Tanh(), nn.Linear(24,len(self.channels))))
        self.register_buffer('center',torch.zeros(feature_size))
        self.register_buffer('scale',torch.ones(feature_size))

    def forward(self, features, horizon, valid):
        logits = self.offset(horizon)
        if self.layers is not None:
            x = ((features-self.center)/self.scale).clamp(-5,5)
            if self.mode == 'current':
                x = x[:, :17]
            logits = logits + self.layers(torch.cat((x,nn.functional.one_hot(horizon,len(HORIZONS))),dim=1))
        logits = logits.masked_fill(~valid[:,self.channels],-1e8)
        return logits.softmax(1)


def tensors(rows):
    return tuple(torch.tensor(rows[k], dtype=d) for k,d in [('features',torch.float32),('hindex',torch.long),
            ('available',torch.bool),('exact',torch.float32),('tile',torch.float32)])


def fit_gate(rows, name, seed):
    mode, enable = GATES[name]
    torch.manual_seed(seed)
    features,h,valid,exact,tile = tensors(rows)
    train = Gate(mode,enable,features.shape[1])
    train.center.copy_(features.mean(0))
    train.scale.copy_(features.std(0).clamp(min=.12))
    opt = torch.optim.AdamW(train.parameters(),lr=.004,weight_decay=.025)
    rng = np.random.default_rng(seed)
    # Balance game and horizon contributions in the fitting objective.
    weights = np.zeros(len(h),dtype=np.float32)
    for game in np.unique(rows['game']):
        for horizon in range(len(HORIZONS)):
            selected = (rows['game']==game)&(rows['hindex']==horizon)
            weights[selected] = 1./max(1,selected.sum())
    weights /= weights.mean()
    sample_weights = torch.tensor(weights)
    eps = 1e-35
    for epoch in range(12):
        for ix in np.array_split(rng.permutation(len(h)),max(1,len(h)//1024)):
            w = train(features[ix],h[ix],valid[ix])
            p = (w*exact[ix][:,train.channels]).sum(1).clamp_min(eps)
            q = (w*tile[ix][:,train.channels]).sum(1).clamp_min(eps)
            loss = -(sample_weights[ix]*(p.log()+.25*q.log())).mean()
            opt.zero_grad()
            loss.backward()
            opt.step()
    print(f'Trained {name}',flush=True)
    return train


@torch.no_grad()
def forecast_gate(model, rows):
    features,h,valid,exact,tile = tensors(rows)
    w = model(features,h,valid)
    p = (w*exact[:,model.channels]).sum(1)
    q = (w*tile[:,model.channels]).sum(1)
    return np.column_stack((p.numpy(),q.numpy())), w.numpy()


def summarize(rows, estimates):
    results, players = [], []
    group = rows['game']
    h = rows['hindex']
    once = [
        ('all', np.ones(len(h),dtype=bool)),
        ('late',rows['late']),
        ('dwell ≥8s',rows['features'][:,19]>=8/RECENT),
        ('moving ≥2 blocks/s',rows['features'][:,17]>=.5),
        ('recent tile use shifted ≥25pp',abs(rows['features'][:,25])>=.25),
    ]
    for label,scores_ in estimates.items():
        loss = -np.log(scores_.clip(min=1e-40))
        for game in np.unique(group):
            for hi,horizon in enumerate(HORIZONS):
                baseline = (group==game)&(h==hi)
                for cohort,mask in once:
                    ix = baseline & mask
                    if ix.any():
                        results.append({'model':label,'game':game,'horizon':horizon,'cohort':cohort,'n':int(ix.sum()),
                                        'block':float(loss[ix,0].mean()),'tile':float(loss[ix,1].mean())})
                ix = baseline & rows['late']
                for name in np.unique(rows['player'][ix]):
                    keep = ix & (rows['player']==name)
                    if keep.sum()>=15:
                        players.append({'model':label,'game':game,'horizon':horizon,'player':name,'n':int(keep.sum()),
                                        'block':float(loss[keep,0].mean()),'tile':float(loss[keep,1].mean())})
    return results,players


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',required=True,type=Path)
    parser.add_argument('--heatmap-checkpoint',type=Path,default=None)
    parser.add_argument('--no-cache',action='store_true')
    cli=parser.parse_args()
    torch.set_num_threads(5)
    here=Path(__file__).resolve().parent
    root=here.parents[2]
    p=json.loads((here/'protocol.json').read_text())
    track=root/p['input']
    assert hashlib.sha256(track.read_bytes()).hexdigest()==p['input_sha256']
    out=cli.out
    out.mkdir(parents=True,exist_ok=True)
    games=load_games(track)
    keyed={g['id']:g for g in games}
    dev=p['development']
    target=p['evaluation']
    data=data_for(games)
    seen=observed(data.raw)
    seed=p['seed']
    core=cli.heatmap_checkpoint or (root/'runtime/bedwars-forecast/advanced/heatmap-models.pt')
    pack=torch.load(core,map_location='cpu',weights_only=True)
    base=Heatmap()
    base.load_state_dict(pack['heatmap'])
    previous=MetaHistory()
    previous.load_state_dict(pack['meta-history'])
    cache=out/'experts-dev.npz'
    if cli.no_cache or not cache.exists():
        oof=[]
        for i, issue in enumerate(FOLDS):
            train=[g for g in dev if g not in issue]
            mask=seen&np.isin(data.raw['game'],train)
            crossfit=supervised(data,'multiscale',np.flatnonzero(mask),8,seed+i)
            crossmotion=make_motion(data,train)
            map_pool=map_only_pool(data,[keyed[g] for g in train],seed+i)
            oof.append(get_predictions(data,[keyed[g] for g in issue],crossfit,None,crossmotion,map_pool))
        fit=concat(oof)
        np.savez_compressed(cache,**fit)
    else:
        with np.load(cache) as arr: fit={k:arr[k] for k in arr.files}
    fitted={name:fit_gate(fit,name,seed) for name in GATES}
    torch.save({k:v.state_dict() for k,v in fitted.items()},out/'context-gates.pt')
    cache=out/'experts-test.npz'
    if cli.no_cache or not cache.exists():
        static=make_motion(data,dev)
        pool=map_only_pool(data,[keyed[g] for g in dev],seed)
        evaluation=get_predictions(data,[keyed[g] for g in target],base,previous,static,pool)
        np.savez_compressed(cache,**evaluation)
    else:
        with np.load(cache) as arr: evaluation={k:arr[k] for k in arr.files}
    estimates={key:evaluation['baselines'][:,i,:2] for i,key in enumerate(STANDARD)}
    means=[]
    for name,model in fitted.items():
        estimates[name],weights=forecast_gate(model,evaluation)
        for hi,horizon in enumerate(HORIZONS):
            on=evaluation['hindex']==hi
            for label,sel in (('all',on),('dwell ≥8s',on&(evaluation['features'][:,19]>=8/RECENT)),
                              ('moving ≥2 blocks/s',on&(evaluation['features'][:,17]>=.5))):
                if not sel.any():continue
                for i,col in enumerate(model.channels):
                    means.append({'model':name,'horizon':horizon,'cohort':label,'expert':EXPERTS[col],
                                  'n':int(sel.sum()),'mean_weight':float(weights[sel,i].mean())})
    summary, players=summarize(evaluation,estimates)
    write_csv(out/'game-scores.csv', summary)
    write_csv(out/'player-scores.csv', players)
    write_csv(out/'weights.csv', means)
    print(f'Saved {len(evaluation["time"])} matching test forecasts to {out}',flush=True)


if __name__=='__main__':
    main()
