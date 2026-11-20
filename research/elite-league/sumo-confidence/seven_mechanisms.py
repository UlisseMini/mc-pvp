import time
import subprocess
from seven_study import D,F,S,PRIOR,java,wait
def run():
    selected=wait(S/'ready.json');src=F/'fits'/(selected['methods'][selected['primary']]['id']+'.net')
    previous=F/'fits'/(selected['old_ppo']+'.net');arm='sumo-seven-'+selected['primary']
    for path,tag in [(F/'selection-clocks.txt','development'),(S/'model/clocks.txt','prospective')]:
        while not path.exists():time.sleep(5)
        folder=S/tag;folder.mkdir(exist_ok=True)
        for cap in (7,20):
            dest=folder/f'mechanisms-{cap}.jsonl'
            if not dest.exists():
                subprocess.run(java(f'-Dlab.responseResearchCps={cap}','FrozenResponseMechanics',path,dest,arm,src,PRIOR,previous),check=True)
if __name__=='__main__':run()
