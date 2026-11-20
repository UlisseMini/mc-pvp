"""Advance one frozen generation at a time. An unresolved search never counts as a fixed point."""
import argparse
import fcntl
import subprocess
import sys
import time
from generation import initialize
from native_league import read,publish,ROOT,HERE,EVIDENCE,BUILD,write_json
from promotion import nomination,freeze_promotion_styles,publish_winner,winner_controls,native_panel,transfer20,promote_title
from train_generation import run as train_generation

def governed(*cmd):
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8',sys.executable,*cmd],check=True)
def clocks(folder):
    file=folder/'calendars/manifest.json'
    if not file.exists():
        subprocess.run([sys.executable,HERE/'calendars_holdout.py',folder],check=True)
    return read(file)['selected']
def stage(g):
    folder=EVIDENCE/f'g{g:03d}';decision=folder/'decision.json'
    if decision.exists():return read(decision)
    initialize(g);freeze_promotion_styles(g)
    train_generation(g,0)
    from secondary import declare
    declare(g)
    rank=read(folder/'tournament.json');nom=nomination(g);field=nom['field']
    write_json(folder/'nomination.json',nom,fixed=True)
    tournament_name=publish_winner(g,nom);gate=read(folder/'counter.json')
    if gate['confirmed']:
        publish(gate['challenger'],field[gate['challenger']],f'Confirmed 7 CPS best response, generation {g}')
    rounds={r['name']:r for r in rank['ranking']};c=rounds[nom['challenger']];i=rounds[nom['incumbent']]
    plausible=(c['field_mean']>i['field_mean'] and c['worst']>=i['worst']-.05 or
               c['worst']>i['worst'] and c['field_mean']>=i['field_mean']-.05) and c['vs_wtap']['score']>.75 and rank['matrix'][c['name']]['sumo-seven-first']['score']>.5
    model=False
    if plausible:
        clocks(folder);governed(HERE/'stage_task.py','model',str(g))
        model=read(folder/'promotion-model.json')['passed']
    # All learners still receive their matched development signature when the title gate is closed.
    winner_controls(g,nom)
    if not (folder/'promotion-native.json').exists():native_panel(g,nom,model)
    native=read(folder/'promotion-native.json')['passed']
    if model and native:
        governed(HERE/'stage_task.py','transfer',str(g));transfer20(g,nom);promote_title(g,nom)
    # Independently gate spending the style holdout on existence of opposite responses in development.
    governed(HERE/'stage_task.py','reversal',str(g))
    if read(folder/'adaptation-development.json')['eligible_for_sealed_test']:
        clocks(folder);governed(HERE/'assay.py',str(g))
    mem=folder/'memory/evidence.json'
    result=dict(generation=g,live_incumbent=nom['challenger'] if model and native else nom['incumbent'],
        tournament_winner=nom['winner'],published=tournament_name,confirmed_counter=gate['confirmed'],novel=gate['novel'],
        promoted=bool(model and native),model_title_gate_opened=plausible,model_passed=model,native_passed=native,
        adaptation_proven_in_assay=bool(mem.exists() and read(mem)['causal_history_helped_both_styles']),
        held_out_elite_human_matches=0)
    write_json(decision,result,fixed=True);return result

def main(attach_stage=0):
    BUILD.mkdir(exist_ok=True,parents=True)
    with (BUILD/'league.lock').open('a') as supervisor:
        fcntl.flock(supervisor,fcntl.LOCK_EX|fcntl.LOCK_NB)
        # A coordinator upgrade can adopt a live frozen stage without replaying or interrupting its work.
        if attach_stage:
            while True:
                cmd=subprocess.run(['ps','-p',str(attach_stage),'-o','command='],capture_output=True,text=True).stdout
                if str(HERE/'league.py')+' stage ' not in cmd:break
                time.sleep(5)
        g=1;idle=0
        while True:
            if (BUILD/'stop-after-current').exists():return
            decision=EVIDENCE/f'g{g:03d}'/'decision.json'
            if not decision.is_file():
                # Pick up the declared entrants and runtime for this generation at its boundary.
                # Interrupted workers resume from the immutable generation and match ledgers.
                subprocess.run([sys.executable,'-u',__file__,'stage',str(g)],check=True)
            result=read(decision);print(result,flush=True)
            idle=0 if result['promoted'] or result['novel'] else idle+1
            if idle>=3:
                folder=EVIDENCE/f'g{g:03d}'
                subprocess.run([sys.executable,HERE/'stage_task.py','plateau',str(g)],check=True)
                if read(folder/'plateau/verdict.json')['budgeted_plateau']:
                    verdict=read(folder/'plateau/verdict.json')
                    write_json(EVIDENCE/'stop.json',dict(generation=g,reason=verdict.get('reason','two independent search methods below .55 with a working positive control')),fixed=True)
                    return
                idle=0
            g+=1
if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('operation',nargs='?',choices=['stage'])
    parser.add_argument('generation',nargs='?',type=int)
    parser.add_argument('--attach-stage-pid',type=int,default=0)
    args=parser.parse_args()
    if args.operation=='stage' and args.generation is not None:stage(args.generation)
    elif args.operation is None and args.generation is None:main(args.attach_stage_pid)
    else:parser.error('stage requires a generation number')
