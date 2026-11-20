"""Test how quickly an anonymous recurrent router can recover from a change of combat policy."""
import sys

import numpy as np
import torch

from expert_population import Actor, SEED, STUDY, load
from learner import RTTS, write_json
from remote_league import read
from series_eval import tally
from series_targeted import DEST as LIB, bank
from specialist_ablation import bootstrap

DEST = STUDY / 'changed-opponent'


def register():
    write_json(DEST / 'protocol.json', dict(
        question='Does a reward-only router continue inferring what the opponent is doing after learning a confident initial response?',
        setup='Freeze the primary three-opponent PPO (512 updates) and seven-opponent chosen-reward value router (2,048 updates). On each of the same 32 development ten-game schedules, play every ordered pair of different opponents. The first five fights use the first rival, the last five use the second. Timing, pings, random physical action seeds and seats stay matched. The legal policy gets no change marker or opponent identity. Include separately the unchanged diagonal with identical code. These switches substitute a whole policy between bouts; opponents do not respond to the player.',
        controls='Score the actual router, privileged reset before fight six, history frozen after five, always cleared, strongest training-selected fixed MLP, literal dedicated by ongoing identity and W-tap. Report game six, games seven to ten, both seats, all RTTs and each ordered pair. Compare intact and privileged reset on the unchanged diagonal as well. Pair all arms using the same conditional environment fixtures and resample whole schedules within timing recordings. The first bout after the change provides the first new evidence.'),
        fixed=True)


@torch.inference_mode()
def rollout(net, x, w, p, mode):
    b, ng, na = w.shape
    h = torch.zeros(1, b, 48)
    row = torch.arange(b)
    wins, choices = [], []
    for g in range(ng):
        if (mode == 'reset-at-change' and g == 5) or mode == 'erased':
            h.zero_()
        a = net.readout(h[0], p[:,g])[0].argmax(1)
        wins.append(w[row,g,a])
        choices.append(a)
        if mode != 'frozen' or g < 5:
            event = net.event(x[row,g,a], a, na)
            h = net.memory(net.encoder(event)[:,None], h)[1]
    return torch.stack(wins,1).numpy(), torch.stack(choices,1).numpy()


def run(group, trained=None, destination=None):
    x, w, c, wt, world, fi, _, names = load('dev', group)
    n, r, ng, nf, na = w.shape
    spec = read(LIB/'protocol.json')
    training, _, _ = bank('train', 8)
    fixed = int((training[...,0][:,:,:,fi] == 1).float().mean((0,1,2,3)).argmax())
    broad = list(spec['experts']).index('original-mixture')
    net = Actor(na, False)
    if group == 'small':
        path = STUDY/group/f'seed-{SEED}'/'memory/u512.pt'
        original = read(STUDY/group/f'seed-{SEED}'/'dev-u512.json')['scores']['memory']['games']
    else:
        path = STUDY/'reward-debug'/group/f'q-{SEED}/u2048.pt'
        original = read(path.with_suffix('.json'))['scores']['real']['games']
    if trained is not None:
        path = trained
    net.load_state_dict(torch.load(path, weights_only=True))
    net.eval()
    report = {}
    for switch in (False, True):
        pairs = [(i,j) for i in range(nf) for j in range(nf) if (i!=j)==switch]
        types = np.array([[i]*5+[j]*5 for i,j in pairs])
        fixtures = np.indices((n,r,len(pairs),ng))
        ci, ri, pair, g = fixtures
        actual = types[pair,g]
        index = (ci, ri, g, actual)
        mask = torch.as_tensor(actual.reshape(-1,ng))
        samples = x[index].reshape(-1,ng,na,198)
        scores = w[index].reshape(-1,ng,na)
        context = c[ci,ri,g].reshape(-1,ng,3)
        ping = context[...,1:].float()
        arms, actions = {}, {}
        for mode in ('intact','reset-at-change','frozen','erased'):
            arms[mode], actions[mode] = rollout(net, samples, scores, ping, mode)
        if not switch and trained is None and tally(arms['intact']) != original:
            raise ValueError('Unchanged controls do not reproduce the original router score')
        arms['dedicated'] = scores.gather(-1,mask.unsqueeze(-1)).squeeze(-1).numpy()
        arms['fixed'] = world[...,fixed,0][index].reshape(-1,ng).numpy()
        arms['broad'] = world[...,broad,0][index].reshape(-1,ng).numpy()
        arms['wtap'] = wt[:,:,:,fi][index].reshape(-1,ng).numpy()
        output = {}
        for name, z in arms.items():
            output[name] = dict(games=tally(z), by_game=[tally(a) for a in z.T], after_one=tally(z[:,6:]),
                by_pair={names[i]+' -> '+names[j]:tally(z[k::len(pairs),6:]) for k,(i,j) in enumerate(pairs)},
                seats={s:tally(z[context[...,0].numpy()==i]) for i,s in enumerate('ab')},
                rtt={f'{a}/{b}':tally(z[(ping[...,0].numpy()==a)&(ping[...,1].numpy()==b)]) for a,b in RTTS})
        effects = {}
        for other in ('reset-at-change','frozen','erased','fixed','dedicated'):
            diff=((arms['intact'][:,6:]==1).astype(float)-(arms[other][:,6:]==1)).reshape(n,r,-1).mean(2)
            effects[other]=dict(mean=float(diff.mean()),conditional_95=bootstrap(diff))
        report['changed' if switch else 'stable']=dict(scores=output, fixed_expert=list(spec['experts'])[fixed], after_one_intact_minus=effects,
            action_changes_after_each_game={k:(a[:,1:]!=a[:,:-1]).sum(0).tolist() for k,a in actions.items()})
    write_json(destination or DEST/f'{group}.json',report,fixed=True)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    else:run(sys.argv[1])
