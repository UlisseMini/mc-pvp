"""Audit the binary that will run on the real Forge JVM, including forks and actual input digests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from run import ROOT,HERE,compile_model,java,calendars
sys.path.insert(0,str(ROOT))
from forge_runtime import JAVA8

def run(d,frozen):
    classes=d/'parity-classes';classes.mkdir(exist_ok=True)
    subprocess.run(['javac','-J-XX:ActiveProcessorCount=2','--release','8','-proc:none','-cp',str(d/'bridge.jar'),'-sourcepath','','-d',str(classes),str(HERE/'RuntimeParity.java')],check=True)
    paths=sorted(frozen.glob('*.net'))+sorted((HERE/'frozen').glob('*.net'))+[HERE/'boxing-frozen/specialist.net',HERE/'human-frozen/clone.net',HERE/'robust-frozen/ppo.net']
    student=HERE/'boxing-distilled-frozen/student.net'
    if student.exists():paths.append(student)
    if len(paths)<22:raise ValueError('Incomplete final roster')
    cp=os.pathsep.join(map(str,(classes,d/'bridge.jar')))
    command=['-Xms20m','-Xmx128m','-XX:+UseSerialGC','-cp',cp,'RuntimeParity',*map(str,paths)]
    digest=[]
    for runtime in (str(JAVA8),'java'):
        result=subprocess.run([runtime,*command],capture_output=True,text=True,check=True)
        digest.append(result.stdout)
    if digest[0]!=digest[1]:raise ValueError('Java 8 and Java 17 disagree; see policy action digests')
    (d/'java8-java17.txt').write_text(digest[0]);compile_model()
    with (d/'logic-tests.txt').open('w') as f:
        for main in ('NetworkChecks','GradientChecks'):subprocess.run(java(main),stdout=f,check=True)
        for name in ('elite-m01-explicit','elite-m08-search','elite-m12-neural','elite-r03-table','elite-r08-table','elite-first-hurt','human-pressure-151121'):
            subprocess.run(java('ForkChecks',calendars('training_clocks')[0],frozen/(name+'.net')),stdout=f,check=True)
    (d/'parity-ok').write_text(json.dumps(dict(weights=len(paths),heads_per_weight=20480,digest=hashlib.sha256(digest[0].encode()).hexdigest()))+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('stage',type=Path);p.add_argument('frozen',type=Path)
    a=p.parse_args();run(a.stage,a.frozen)
