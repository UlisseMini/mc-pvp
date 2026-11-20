"""Recheck 16k and 32k checkpoints on separate, previously exposed timing recordings."""
from pathlib import Path
from statistics import mean
import hashlib
from learner import CLOCKS,EVIDENCE,ROOT,write_json
from long_confirm import RUN,read,score

DEST=RUN/'midpoint-timing'
SEED=41073901
TARGETS=('seven','incumbent','g1')

def register():
    path=DEST/'protocol.json'
    manifests=[EVIDENCE/'g001/calendars/manifest.json',EVIDENCE/'id-oracle/prospective/calendars/manifest.json',
               EVIDENCE/'id-oracle/recognition/payoff/prospective/calendars/manifest.json']
    # Take every fourth donor in chronological order, without consulting any outcomes.
    donors=[clock for m in manifests for clock in read(m)['selected'][1::4]]
    names=[Path(d).name for d in donors]
    assert len(set(names))==len(donors)==24
    assert not set(names).intersection(Path(c).name for group in CLOCKS.values() for c in group)
    freeze_hashes={k:{str(n):hashlib.sha256((RUN/k/f'n{n}.net').read_bytes()).hexdigest()
                      for n in (16384,32768)} for k in TARGETS}
    write_json(path,dict(seed=SEED,donors=donors,source_manifests=[str(m.relative_to(ROOT)) for m in manifests],
        status='Retrospective timing transfer. All three periods were previously exposed in other experiments. These fixture outcomes do not select any weight or hyperparameter. The separate prospective 32-recording final stays sealed.',
        weights=freeze_hashes,geometry='original training floor',controls='Matched plain W-tap against the same foe; each specialist also plays W-tap directly.',
        cases='24 timing donors, four replications, both seats, four fixed RTT pairs and four independent uniform [0,150] RTT pairs: 1536 games per matchup, regulation 7 CPS',
        primary='Ring-out win change from 16k to 32k, W/D/L, changes in each of the three capture periods. Closely spaced donors within a recording period can be correlated.'),fixed=True)
    return donors

def main():
    clocks=register()
    for key in TARGETS:
        folder=DEST/key
        score(key,32768,clocks,SEED,4,folder)
        results={}
        for foe in ('target','wtap'):
            rows={label:read(folder/label/(foe+'.json')) for label in ('16384','32768','wtap')}
            early=rows['16384']['games'];late=rows['32768']['games']
            if any(a[:6]!=b[:6] for a,b in zip(early,late,strict=True)):raise ValueError('Unmatched fixture')
            change=[int(b[6]==1)-int(a[6]==1) for a,b in zip(early,late)]
            results[foe]=dict(early={k:rows['16384'][k] for k in ('wins','draws','losses')},
                              late={k:rows['32768'][k] for k in ('wins','draws','losses')},
                              plain_wtap={k:rows['wtap'][k] for k in ('wins','draws','losses')},delta=mean(change),
                              period_deltas=[mean(d for d,r in zip(change,early) if r[0]//8==g) for g in range(3)])
        write_json(folder/'summary.json',results,fixed=True)
        print(key,results['target'],flush=True)
    report()

def report():
    lines=['# Timing transfer after doubling specialist training','',
           'Each specialist played its frozen opponent on the original 137-block training floor in the simulator. '
           'Each cell contains 1,536 fights at seven CPS, using 24 previously exposed timing recordings from three acquisition periods. '
           'Both seats appear at four fixed RTT pairs and independently randomized pings. Entries are wins/draws/losses.',
           '','| Frozen opponent | 16k specialist | 32k specialist | Plain W-tap | Win change in each capture period |',
           '| --- | --- | --- | --- | --- |']
    for key,title in [('incumbent','Frozen best-sumo'),('seven','Sumo Seven'),('g1','League winner 001')]:
        d=read(DEST/key/'summary.json')['target']
        cell=lambda r:'/'.join(str(r[k]) for k in ('wins','draws','losses'))
        deltas=', '.join(f'{100*v:+.1f} pp' for v in d['period_deltas'])
        lines.append('| '+' | '.join([title,cell(d['early']),cell(d['late']),cell(d['plain_wtap']),deltas])+' |')
    lines.extend(['','The three periods give a check on time variation. Recordings close together can share timing conditions. '
                  'The original three specialists use only their original development panel for checkpoint selection. '
                  'The adverse-ping curriculum used this diagnostic to select a training condition, which makes these recordings development evidence for that new branch.',
                  '','[Timing fixtures and frozen network hashes](protocol.json) · [Recorded platform scores](../../../../../mmc-arena/evidence/platforms/tournaments/checkpoint32k/results.md)'])
    from learner import freeze
    freeze(DEST/'README.md',('\n'.join(lines)+'\n').encode())

if __name__=='__main__':main()
