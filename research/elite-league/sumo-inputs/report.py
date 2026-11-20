"""Render matched seed effects, prospective outcomes and audited native cross-checks."""
import csv
import gzip
import statistics
from collections import defaultdict
from pathlib import Path
from live import D,ROOT,B,WT,ping,read,freeze,champions
from train import ARMS,SEEDS
def score(v):return sum(v)/len(v)
def wld(v):return '–'.join(str(v.count(x)) for x in (1,0,.5))
def pct(v):return f'{v:.1%}'
def pt(v):return f'{v*100:+.1f}'
def grouped(rows):
    out=defaultdict(dict)
    for r in rows:
        key=r['foe'],r['calendar'],r['seat'].lower(),int(r['own_rtt']),int(r['opponent_rtt'])
        assert key not in out[r['arm']];out[r['arm']][key]=float(r['reward'])
    return out
def cut(row,foe=None,pairs=None):
    return [v for k,v in row.items() if (foe is None or k[0]==foe) and (pairs is None or k[3:] in pairs)]
def native(stage):
    rows=read(D/(stage+'-audit.json'))['rows']
    a=defaultdict(dict)
    for r in rows.values():a[r['arm']][r['foe'],r['seat'],r['own'],r['other']]={'W':1,'L':0,'D':.5}[r['outcome']]
    return a
def rank(rows):
    def key(n):
        v=rows[n];foes=set(k[0] for k in v)
        rates={f:score([y for k,y in v.items() if k[0]==f]) for f in foes}
        return (-(statistics.mean(rates.values())+min(rates.values()))/2,-rates[WT],n)
    return sorted(rows,key=key)
def publish(alias,name,stage):
    champions.install(alias,ROOT/'policies'/(name+'.properties'),'Winner of the matched MMC '+stage+' ('+name+')',stage,'winner')
def main():
    models=grouped(csv.DictReader((D/'transfer.csv').open()));ent=read(D/'entrants.json')
    scr=native('screen');final=native('confirmation');confirm=read(D/'confirmation-selection.json')
    screen_winner=rank(scr)[0];winner=rank(final)[0]
    assert screen_winner==confirm['screen_winner']
    publish('sumo-input-screen',screen_winner,'input-screen');publish('sumo-input-final',winner,'input-confirmation')
    native_rows={}
    for stage in ('screen','confirmation','masks'):
        a=read(D/(stage+'-audit.json'));rs=list(a['rows'].values());native_rows[stage]=dict(n=len(rs),invalid=a['invalid'],
          checked_heads=sum(x['checked_heads'] for x in rs),local_timing_heads=sum(sum(x['local_timing'].values()) for x in rs),
          min_click_ms=min(x['click_min_ms'] for x in rs if x['click_min_ms'] is not None),max_gap_ms=max(x['max_server_gap_ms'] for x in rs))
    out=['# MMC sumo input and capacity experiment','',
        'Three matched training seeds per variant. Each learner started from v4-ppo-g0496, ran 320 PPO updates of 32 fights, and retained the checkpoint with the best development score. Every run saw the same scenarios and exploration seeds as the other variants of that seed. The 32-unit actor starts with the same logits as the eight-unit actor before any feature removal. Opponent ping was hidden for both widths.','',
        'All outcomes below use half credit for draws. Records are wins–losses–draws. Prospective evaluation used six new native timing calendars, ten opponents, both seats, and four independently specified ping pairs. Each trained checkpoint played 480 model bouts. The ten opponents include both previous ping policies, the incumbent, W-tap and shift-check-v4-4. The native screen used the median development seed of each variant.','',
        '## Retraining each variant','',
        'The change is in percentage points against base8 on the same bouts in each training seed. The W-tap and shift columns pool all three seeds. The last column is the plain W-tap field score on the identical panel.','',
        '| Variant | Field score | Change for seeds 7181 / 29137 / 66047 | vs W-tap | vs shift-check | W-tap field score |','|---|---:|---:|---:|---:|---:|']
    full={};baseline=score(list(models['wtap'].values()))
    for arm in ARMS:
        scores=[];changes=[];vswt=[];shift=[]
        for s in SEEDS:
            m=models[f'{arm}-{s}'];base=models[f'base8-{s}']
            assert m.keys()==base.keys();scores.append(score(list(m.values())))
            changes.append(statistics.mean(m[k]-base[k] for k in m))
            vswt+=cut(m,'wtap');shift+=cut(m,'shift-check-v4-4')
        full[arm]=dict(mean=statistics.mean(scores),seed_points=scores,delta=changes,wtap=wld(vswt),shift=wld(shift))
        out.append(f"| {arm} | {pct(statistics.mean(scores))} | {' / '.join(map(pt,changes))} | {wld(vswt)} | {wld(shift)} | {pct(baseline)} |")
    out+=['','base8 uses the original inputs; blind8 fixes own ping at 80 ms. noself8 removes own XYZ velocity, nofoe8 removes immediate visible opponent XYZ displacement, and nohurt8 removes the two hurt-cue ages and their difference. motion8 adds a wider velocity encoding, a time-normalized smoothed XYZ opponent estimate, and elapsed time since visible movement. edge8 adds point-to-edge distances. timing8 adds reach margin and physical click age. full combines the three additions. wide32 has the original inputs. Group effects do not identify an individual channel inside a group.','',
          '## Is the incumbent helped by reading its own ping?','']
    original=models[ping.OLD];blind=models['incumbent-blind'];diff=sum(original[k]!=blind[k] for k in original)
    out.append(f'With identical incumbent weights and physical links, the accurate reading earned {sum(original.values()):g}/480 points and a constant 80 ms reading earned {sum(blind.values()):g}/480. Altering only that feature changed {diff} outcomes. The retrained blind8 comparison above tests whether learning can compensate.')
    for stage,rows in [('Native screen',scr),('Independent native confirmation',final)]:
        foes=list(dict.fromkeys(k[0] for v in rows.values() for k in v))
        foes=[WT]+sorted(set(foes)-{WT})
        out+=['','## '+stage,'','Each foe has one bout per combination of seats A/B and added RTT 31/109 or 109/31 ms. Seat and link totals combine the opponents.','',
              '| Actor | '+' | '.join('vs '+f for f in foes)+' | Seat A / B | RTT 31/109 / 109/31 | W-tap overall |',
              '|---|'+'---:|'*(len(foes)+3)]
        for n in rank(rows):
            v=rows[n];sides=[wld([y for k,y in v.items() if k[1]==s]) for s in 'ab']
            links=[wld([y for k,y in v.items() if k[2]==o]) for o in (31,109)]
            out.append('| '+' | '.join([n,*[wld([y for k,y in v.items() if k[0]==f]) for f in foes],' / '.join(sides),' / '.join(links),wld(list(rows[WT].values()))])+' |')
    mask_native=native('masks');mask_effect={}
    out+=['','## Turning off a group in the frozen 32-unit actor','',
          'All trainable weights and physical delays are held fixed. A positive change means removing the input helped. Native records combine W-tap and shift-check; all retained base and W-tap controls came from the original matched screen.','',
          '| Feature removed | Model change by seed (points) | Model outcomes changed / 1,440 | Native vs W-tap | Native vs shift-check | Plain W-tap vs same foes |',
          '|---|---:|---:|---:|---:|---|']
    for group in ('intact','motion','edge','timing'):
        deltas=[];changed=0
        for seed in SEEDS:
            original_full=models[f'full32-{seed}'];m=original_full if group=='intact' else models[f'fixed-no{group}-{seed}']
            deltas.append(statistics.mean(m[k]-original_full[k] for k in m));changed+=sum(m[k]!=original_full[k] for k in m)
        v=scr['sumo-input-full32'] if group=='intact' else mask_native['sumo-input-fixed-no'+group]
        foes=[WT,'shift-check-v4-4']
        native_vals=[wld([y for k,y in v.items() if k[0]==f]) for f in foes]
        wt_vals=[wld([y for k,y in scr[WT].items() if k[0]==f]) for f in foes]
        out.append('| '+' | '.join([group,' / '.join(map(pt,deltas)),str(changed),*native_vals,' / '.join(wt_vals)])+' |')
        mask_effect[group]=dict(deltas=deltas,changed=changed,native=native_vals)
    out+=['','## Historical opponent stress test','','Both asymmetric ping pairs, both seats and six clocks, giving 24 fights per archive entry. Each listed opponent receives equal weight. This secondary test uses the same six prospective clocks. No native score over the entire archive was collected.','',
          '| Actor | Opponents | Field score | Ahead / tied / behind | Weakest opponent (record) | Plain W-tap field |','|---|---:|---:|---:|---|---:|']
    with gzip.open(D/'archive.csv.gz','rt') as fp:arc=grouped(csv.DictReader(fp))
    arch={}
    for n,vs in arc.items():
        foes=defaultdict(list)
        for k,v in vs.items():foes[k[0]].append(v)
        rates=[score(v) for v in foes.values()];weak=min(foes,key=lambda f:(score(foes[f]),f))
        arch[n]=dict(mean=score(list(vs.values())),foes=len(foes),weakest=weak,weakest_record=wld(foes[weak]),
                     ahead=sum(x>.5 for x in rates),tied=rates.count(.5),behind=sum(x<.5 for x in rates))
        a=arch[n];out.append(f"| {n} | {len(foes)} | {pct(a['mean'])} | {a['ahead']} / {a['tied']} / {a['behind']} | {weak} ({wld(foes[weak])}) | {pct(score(list(arc['wtap'].values())))} |")
    out+=['','## Qualification','',f'The native screen winner was {screen_winner}. The trained challenger selected for confirmation was {confirm["challenger"]}. The independent confirmation winner was {winner}. Both winners have permanent aliases, sumo-input-screen and sumo-input-final. All native representatives can be dueled by the names above.','']
    for stage,r in native_rows.items():out.append(f"- {stage} covered {r['n']} valid bouts, {r['checked_heads']:,} verified movement decisions, {r['local_timing_heads']:,} reconstructed feature timing records, and a minimum physical press interval of {r['min_click_ms']:.3f} ms. Largest observed server tick gap {r['max_gap_ms']} ms. Retained {len(r['invalid'])} invalid attempts.")
    out+=['','The score spread across three training seeds reflects optimization variability. Both seats and real timing donors are reused across policies in simulation. Native games are independent. Four native bouts per matchup give coarse resolution. Historical opponents and timing clocks are proxies for play against independent elite humans.','']
    (D/'results.md').write_text('\n'.join(out))
    freeze(D/'summary.json',dict(retraining=full,baseline=baseline,incumbent_ping=dict(true=sum(original.values()),neutral=sum(blind.values()),changed=diff),
        native=native_rows,archive=arch,mask_effect=mask_effect,screen_winner=screen_winner,confirmation_winner=winner,confirmed_challenger=confirm['challenger']))
if __name__=='__main__':main()
