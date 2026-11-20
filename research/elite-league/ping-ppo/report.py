"""Render the frozen training, matched transfer and input-intervention results."""
import csv
import json
from pathlib import Path
D=Path(__file__).resolve().parent
def record(v):return '–'.join(map(str,v))
def main():
    data=json.loads((D/'results.json').read_text())
    model,native=data['model'],data['native']
    learn=list(csv.DictReader((D/'learning.csv').open()))
    ablation=list(csv.DictReader((D/'ping-ablation.csv').open()))
    output=['# Sumo with independently randomized player pings','',
        'Both PPO actors started at the deployed v4-ppo-g0496 weights. Both saw the same independent per-match draws, seats, opponents and random action seeds. Added RTT was uniformly sampled at whole milliseconds from 0 to 150 except 23 and 97. Those two values were reserved for transfer. The actor has eight softsign hidden units and five movement and click outputs. The two-ping version adds opponent RTT and an availability indicator to the original 22 inputs.','',
        'The deterministic development score gives equal weight to field average, W-tap and the weakest opponent, with half credit for draws. Each check used 256 fights. After at least 256 updates, six checks without a half-point gain triggered a fourfold learning-rate reduction. Six further checks without a half-point gain ended training.','']
    for name in ['two-ping','own-ping']:
        matches=[r for r in learn if r['arm']==name];last=matches[-1];best=max(matches,key=lambda r:float(r['validation']))
        output.append(f"- {name} peaked at update {best['update']} with score {float(best['validation']):.1%}. It stopped at update {last['update']} after {int(last['update'])*32:,} training bouts.")
    output+=['','The incumbent scored 66.5% on development and plain W-tap scored 32.5%. The first four new W-tap mirrors supplied the sealed host clocks. Test rows within each host clock share timing. The native matches below are independent real games.','',
             '## Transfer','', 'All records show wins–losses–draws from the column bot’s perspective.','',
             '| Environment and opponent | Two-ping PPO | Own-ping PPO | Incumbent | Plain 20-CPS W-tap |',
             '|---|---:|---:|---:|---:|']
    weakest=next(f for f in native['sumo-two-ping']['versus'] if f!='research-wtap20')
    for label,src,keys,field,foe in [
        ('Simulator, eight opponents',model,['two-ping','own-ping','v4-ppo-g0496','wtap'],'total',''),
        ('Simulator, W-tap',model,['two-ping','own-ping','v4-ppo-g0496','wtap'],'versus','wtap'),
        ('Simulator, '+weakest,model,['two-ping','own-ping','v4-ppo-g0496','wtap'],'versus',weakest),
        ('Minecraft, W-tap',native,['sumo-two-ping','sumo-own-ping','v4-ppo-g0496','research-wtap20'],'versus','research-wtap20'),
        ('Minecraft, '+weakest,native,['sumo-two-ping','sumo-own-ping','v4-ppo-g0496','research-wtap20'],'versus',weakest)]:
        scores=[src[n][field][foe] if foe else src[n][field] for n in keys]
        output.append('| '+' | '.join([label,*map(record,scores)])+' |')
    output+=['','### Native seat and RTT spread','',
        'Each cell below combines the two scheduled opponents. The JSON results also break each opponent out by seat and link. The first RTT belongs to the column bot.','',
        '| Slice | Two-ping PPO | Own-ping PPO | Incumbent | Plain 20-CPS W-tap |','|---|---:|---:|---:|---:|']
    for prefix,key in [('Seat','seat'),('RTT in ms','links')]:
        for value in sorted(native['sumo-two-ping'][key]):
            out=[record(native[n][key][value]) for n in ['sumo-two-ping','sumo-own-ping','v4-ppo-g0496','research-wtap20']]
            output.append('| '+' | '.join([f'{prefix} {value}',*out])+' |')
    unequal=[r for r in ablation if r['own']!=r['other']]
    truth=sum(float(r['truth']) for r in unequal)
    same=sum(float(r['opponent_set_to_own']) for r in unequal)
    changed=sum(r['truth']!=r['opponent_set_to_own'] for r in unequal)
    decisions=sum(v['correct'] for sides in data['public_hud'].values() for v in sides.values())
    missing=sum(v.get('unknown',0) for sides in data['public_hud'].values() for v in sides.values())
    output+=['','## What did the opponent ping contribute?','',
             f'On {len(unequal)} asymmetric simulated fights, replacing only the observed opponent ping with the actor’s own ping changed {changed} results. The physical pings, both seats, host timings and both policies’ weights stayed fixed. The actor scored {truth:g} points with the real value and {same:g} with the substituted value. The network uses the extra input, but the correct value gave no aggregate advantage in this panel.','',
             f"The native audit verified {data['physical_movement_heads']:,} movement decisions across both players in {data['native_verified']} bouts. The two-ping actor received the correct HUD value on {decisions:,} scored decisions, with {missing:,} unknown. The shortest recorded physical click interval was {data['min_physical_click_gap_ms']:.3f} ms. The initial parser and intermittent-HUD attempts are archived separately. The final comparison retained every original control and replayed all eight two-ping bouts with match-local HUD memory. No elite human bouts were scored.",'']
    (D/'results.md').write_text('\n'.join(output))
if __name__=='__main__':main()
