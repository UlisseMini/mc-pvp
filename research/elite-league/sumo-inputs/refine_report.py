import csv
import statistics
from live import D,WT,read,ping,freeze
from report import grouped,native,rank,cut,pct,wld,pt,publish
F=D/'refine'
def main():
    sel=read(F/'selection.json');parent=sel['parent'];model=grouped(csv.DictReader((F/'transfer.csv').open()))
    rows=native('refinement');winner=rank(rows)[0];publish('sumo-input-refinement',winner,'input-hard-opponent-final')
    parent_alias=next(n for n,id in read(D/'entrants.json')['representatives'].items() if id==parent)
    weak=(F/'hardest.txt').read_text().splitlines()
    scores={k:statistics.mean(v.values()) for k,v in model.items()};wt=scores['wtap']
    out=['# Continuing PPO on its weakest development matchups','',
        f'The common parent was {parent} ({parent_alias}). Its three weakest distinct development opponents were '+', '.join(weak)+'. The focused arm spent half of its draws on these three and half on the original uniform field. The control trained uniformly for the same extra 384 updates. Each arm used three paired seeds. Both policies saw the same link draws, clocks, seat and action seeds.','',
        '## Four new simulator clocks','','Every cell is a points percentage with half credit for draws. Each actor played 320 fights across ten opponents.','',
        '| Seed | Uniform | Focused | Focused change (points) | Parent | Plain W-tap |','|---|---:|---:|---:|---:|---:|']
    deltas=[]
    for s in (4711,5821,6911):
        u=scores[f'uniform-{s}'];h=scores[f'focused-{s}'];deltas.append(h-u)
        out.append(f'| {s} | {pct(u)} | {pct(h)} | {pt(h-u)} | {pct(scores[parent])} | {pct(wt)} |')
    out+=['','## Native confirmation','','Each matchup covers both seats and both asymmetric RTT pairs. Records are wins–losses–draws.','',
        '| Policy | vs W-tap | vs '+weak[0]+' | A / B | 31/109 / 109/31 | Plain W-tap overall |','|---|---:|---:|---:|---:|---:|']
    for n in rank(rows):
        v=rows[n];other=next(k[0] for k in v if k[0]!=WT)
        vals=[wld([y for k,y in v.items() if k[0]==f]) for f in [WT,other]]
        sides=' / '.join(wld([y for k,y in v.items() if k[1]==s]) for s in 'ab')
        links=' / '.join(wld([y for k,y in v.items() if k[2]==o]) for o in [31,109])
        out.append('| '+' | '.join([n,*vals,sides,links,wld(list(rows[WT].values()))])+' |')
    out+=['',f'The observed native bracket winner was {winner}. Its permanent alias is sumo-input-refinement. The uniform and focused entrants are available as sumo-input-uniform and sumo-input-focused. Selection used development scores. The named hard opponents and validation margin were frozen before comparison.','']
    (F/'results.md').write_text('\n'.join(out));freeze(F/'summary.json',dict(winner=winner,model=scores,focused_minus_uniform=deltas,weak=weak))
if __name__=='__main__':main()
