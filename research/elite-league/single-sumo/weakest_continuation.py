"""Continue the exact 65k counter where the enlarged, anonymous field router still loses most."""
import hashlib
import sys

from league_tournament import stats
from learner import CLOCKS, EVIDENCE, ROOT, Worker, freeze, write_json
from remote_league import OUT, read
from series_targeted import DEST as LIB
from specialist_diagnosis import DEST as STUDY
from train import fit

DEST=STUDY/'expanded-library/weakest-continuation'
BUDGETS=(65536,131072,262144)


def register():
    from id_evaluate import make_panel
    write_json(DEST/'panel.json',make_panel(CLOCKS['dev'],8),fixed=True)
    spec=read(LIB/'protocol.json')
    name='reply-to-g1'
    target=spec['field'][name]
    source=EVIDENCE/'fixed-adversaries'/target['sha256']
    canonical=read(source/'result.json')
    write_json(DEST/'protocol.json',dict(target=name,foe=target,source=str(source.relative_to(ROOT)),parent=canonical['policy'],
        model_state=hashlib.sha256((source/'trainer.pt').read_bytes()).hexdigest(),
        weak_evidence='The eleven-expert outright-win trained router won only 193/320 against the G1 reply, and the train-fitted identity router won 215/320, both on the same 27-opponent field. All other opponents did better against this learned router. The privileged dedicated MLP had already cleared the 60% simulator search gate.',
        design='Copy the exact finished 65,536-bout optimizer, model, config and cumulative step to an independent directory. Continue the original single-opponent MLP PPO without changing the reward, schedule seeds or architecture. Score 65,536, 131,072 and 262,144 on identical 512 development fixtures with W-tap. Also score public W-tap as a distinct opponent. Verify the official parent on every stored original fixture. Keep every checkpoint and reserve a small matched real Minecraft comparison for both new controls and the parent. Do not replace the canonical result or append these observations to training.',
        cumulative_fights=list(BUDGETS)),fixed=True)


def run():
    reg=read(DEST/'protocol.json');src=ROOT/reg['source'];work=DEST/'training'
    work.mkdir(exist_ok=True,parents=True)
    if not (work/'trainer.pt').exists():
        initial=(src/'trainer.pt').read_bytes()
        if hashlib.sha256(initial).hexdigest()!=reg['model_state']:raise ValueError('Canonical optimizer checkpoint changed')
        freeze(work/'trainer.pt',initial)
    freeze(work/'config.json',(src/'config.json').read_bytes())
    plan=read(DEST/'panel.json')
    rivals={'target':reg['foe']['reference'],'plain-wtap':'wtap'}
    worker=Worker()
    try:
        for budget in BUDGETS:
            receipt=DEST/f'n{budget}.json'
            if receipt.exists():continue
            fit(work,budget//64)
            current=work/'current.net'
            ref=DEST/f'n{budget}.net'
            freeze(ref,current.read_bytes())
            sha=hashlib.sha256(ref.read_bytes()).hexdigest()
            if budget==BUDGETS[0] and sha!=reg['parent']['sha256']:
                raise ValueError('Initial physical controls do not match the 65k parent')
            scores={}
            for rival,enemy in rivals.items():
                scores[rival]={}
                for label,actor in [('challenger',str(ref.relative_to(ROOT))),('wtap','wtap')]:
                    rows=[[actor,enemy,c,s,a,b,x,y] for _,_,s,a,b,c,x,y in plan]
                    games=[v for k in range(0,len(rows),32) for v in worker.run(rows[k:k+32],False)]
                    scored=[[i,r,s,a,b,v,ms] for (i,r,s,a,b,*_),(v,ms,_) in zip(plan,games,strict=True)]
                    if budget==BUDGETS[0] and rival=='target':
                        prior=src/'evaluation'/('candidate.json' if label=='challenger' else 'wtap.json')
                        if prior.exists() and scored!=read(prior)['games']:raise ValueError('65k physical fight outcomes differ')
                    scores[rival][label]=dict(aggregate=stats(scored),rows=scored)
            write_json(receipt,dict(policy=dict(reference=str(ref.relative_to(ROOT)),sha256=sha),budget=budget,scores=scores),fixed=True)
    finally:worker.close()


def native():
    from matched_native import assess
    from remote_import import wait_for
    reg=read(DEST/'protocol.json')
    actors={}
    for n in BUDGETS:
        base=str(DEST.relative_to(OUT))+f'/n{n}'
        wait_for([base+'.net',base+'.json'])
        actors[f'n{n}']=read(DEST/f'n{n}.json')['policy']
    spec=read(LIB/'protocol.json');actors['wtap']=spec['field']['wtap']
    assess(DEST/'native','weak-g1-extended',actors,{'hardest':reg['foe'],'wtap':spec['field']['wtap']},8)


if __name__=='__main__':
    if sys.argv[1]=='register':register()
    elif sys.argv[1]=='native':native()
    else:run()
