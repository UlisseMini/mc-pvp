"""Run a matched seven-CPS native comparison on the frozen main-server pair in fair batches."""
import sys
from learner import ROOT, RTTS, write_json
from native_league import alias, fair_cohort, fixtures
from id_native import native_name


def live(reference):
    if 'native_alias' in reference:
        from native_league import props
        saved=props(reference['native_alias'])
        if saved.get('league_source_sha256')!=reference['sha256'] or saved.get('click_cps')!='7':
            raise ValueError('Published native alias and frozen actor disagree')
        return reference['native_alias']
    name=reference['reference']
    return native_name(ROOT/name) if name.endswith('.net') else alias(name)


def assess(folder,tag,actors,opponents,blocks=8):
    sys.path.insert(0,str(ROOT/'research/mmc-arena'))
    from validation import assessment_bridge
    arms={label:live(ref) for label,ref in actors.items()}
    foes={label:live(ref) for label,ref in opponents.items()}
    plan=fixtures(tag,list(dict.fromkeys(arms.values())),list(dict.fromkeys(foes.values())),blocks,7)
    bridge,stamp=assessment_bridge()
    results=fair_cohort(folder,plan,bridge,server=stamp['server'])
    def count(qs):
        vals=[results[q]['score'] for q in qs]
        return dict(wins=vals.count(1),draws=vals.count(.5),losses=vals.count(0))
    cells={}
    for label,arm in arms.items():
        cells[label]={}
        for foe,target in foes.items():
            qs=[q for q,t in plan.items() if (t['arm'],t['opponent'])==(arm,target)]
            cells[label][foe]=dict(**count(qs),seat={s:count([q for q in qs if plan[q]['side']==s]) for s in 'ab'},
                rtt={f'{a}/{b}':count([q for q in qs if (plan[q]['rtt'],plan[q]['opponent_rtt'])==(a,b)]) for a,b in RTTS})
    write_json(folder/'summary.json',dict(cells=cells,scope=f'independent main-server matches, {blocks} seat/RTT blocks per cell'),fixed=True)
    return cells
