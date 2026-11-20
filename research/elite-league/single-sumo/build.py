"""Build the simulator and the three-class Forge bridge overlay from the frozen active base."""
import json
import os
from pathlib import Path
import subprocess
import sys
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
BUILD=ROOT/'build/elite-league/single-sumo'
sys.path.insert(0,str(HERE.parent))
from stage_runtime import patched,sha
def run():
    frozen=json.loads((HERE.parent/'sumo-confidence/pins.json').read_text())
    base=ROOT/'build/elite-league/sumo-inputs-runtime/bridge.jar'; assert sha(base)==frozen['bridge']
    old=[ROOT/'build/elite-league/sumo-confidence/classes',ROOT/'build/elite-league/sumo-inputs/classes']
    if not all(p.is_dir() for p in old):raise RuntimeError('Build the frozen sumo-confidence and sumo-inputs simulator dependencies first')
    sim=BUILD/'classes';sim.mkdir(exist_ok=True,parents=True)
    cp=os.pathsep.join(map(str,[sim,*old]));lab=ROOT/'src/lab'
    shared=[lab/'DuelNetwork.java',lab/'SumoLeaguePolicy.java'];worker=[p for p in sorted(HERE.glob('*.java')) if p.name!='LeagueTrace.java']
    subprocess.run(['javac','-J-XX:ActiveProcessorCount=1','-proc:none','-cp',cp,'-d',sim,*shared,HERE.parent/'Arena.java',HERE.parent/'NetworkChecks.java',*worker],check=True)
    bridge=BUILD/'bridge.jar'
    if not bridge.exists():
        classes=BUILD/'bridge-classes';classes.mkdir(exist_ok=True)
        subprocess.run(['javac','-J-XX:ActiveProcessorCount=1','--release','8','-proc:none','-sourcepath','','-cp',base,'-d',classes,*shared,lab/'DuelHudPing.java'],check=True)
        patched(base,classes,bridge)
    print('Forge base',sha(base),'overlay',sha(bridge),flush=True)
if __name__=='__main__':run()
