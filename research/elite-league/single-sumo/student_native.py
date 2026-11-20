"""Run and audit native transfer for the legal students, their teachers, and plain W-tap."""
import json
import random
import time
from student_evaluation import OUT,STUDY,ROLES,NEW,models,opponents
from native_league import INCUMBENT,BASE_COUNTER,WT,RTTS,fixtures,publish,alias,cohort,write_json,ROOT
from id_native import native_name
from forge_window import cohort_window
from league_tournament import pin_model
from oracle_student import sha

def run():
    p=STUDY/'native'
    if (p/'summary.json').exists():return
    for role in ROLES:
        folder=OUT/role/'ppo'
        while not (folder/'progress.json').exists() or json.loads((folder/'progress.json').read_text())['update']<256:
            time.sleep(8)
        pin_model(folder)
    while not (STUDY/'ppo/development/summary.json').exists():time.sleep(8)
    dev=json.loads((STUDY/'ppo/development/summary.json').read_text())
    foes=list(dict.fromkeys(['wtap',BASE_COUNTER,INCUMBENT,NEW[0],dev['worst']['gru-id-loss']]))
    plan={}; labels={}; full=opponents()
    for j,foe in enumerate(foes):
        if foe==NEW[0]:foe_name='sumo-league-001'
        else:foe_name=alias(foe)
        sources=models('ppo',foe);actors={}
        for role,model in sources.items():
            if role in ROLES:
                ref=dict(reference=str(model.relative_to(ROOT)),sha256=sha(model))
                actors[role]=publish('sumo-student-'+role,ref,'Observation-only PPO student '+role+' at 7 CPS')
            else:actors[role]=native_name(model)
        if len(set(actors.values()))!=len(actors):raise ValueError('Expected physically distinct evaluation arms')
        portion=fixtures(f'sg-orstu-f{j}',list(actors.values()),[foe_name],8,7)
        lookup={v:k for k,v in actors.items()}
        for q,v in portion.items():
            plan[q]=v;labels[q]=(foe,lookup[v['arm']])
    order=list(plan);random.Random(471858).shuffle(order);plan={q:plan[q] for q in order}
    write_json(p/'design.json',dict(roles=labels,blocks=8,settings='single-match MMC 7/7; both seats and all four native RTT pairs',
        foe_references={x:full[x] for x in foes},scope='independent regulation bouts and physical-button audit'),fixed=True)
    with cohort_window(p) as bridge:played=cohort(p,plan,bridge,block_barrier=True)
    def tally(qs):
        vals=[played[q]['score'] for q in qs]
        return dict(wins=vals.count(1),draws=vals.count(.5),losses=vals.count(0),score=sum(vals)/len(vals))
    cells={}
    for foe,role in sorted(set(labels.values())):
        qs=[q for q in plan if labels[q]==(foe,role)]
        cells.setdefault(foe,{})[role]=dict(**tally(qs),seat={s:tally([q for q in qs if plan[q]['side']==s]) for s in 'ab'},
            rtt={f'{a}/{b}':tally([q for q in qs if (plan[q]['rtt'],plan[q]['opponent_rtt'])==(a,b)]) for a,b in RTTS})
    write_json(p/'summary.json',dict(cells=cells,held_out_human_matches=0),fixed=True)

if __name__=='__main__':run()
