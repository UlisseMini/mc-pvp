"""Write relative paths so the frozen run can be replayed from the repository root."""
import json
from pathlib import Path
dest=Path(__file__).resolve().parent
root=dest.parents[2]
frozen=Path('research/elite-league/recorded-mmc-frozen')
def write(name, value):
    path=dest/name
    if path.exists() and path.read_text()!=value: raise ValueError(f'Frozen file differs: {name}')
    if not path.exists():path.write_text(value)
field=['wtap']
for path in ['range-model/v4-ppo-g0496','range-sprint-training/v4-sprint-link-jitter-15','range-adaptation/switches/shift-check-v4-4','range-responses/reply/v4-reply-g0312']:
    field.append(Path(path).name+'\t'+str(frozen/(path+'.net')))
field+=['hold','trade','zest-strafe']
write('field.tsv','\n'.join(field)+'\n')
data=json.loads((root/'research/elite-league/protocol.json').read_text())
lines=[]
for role,key in [('train','training_clocks'),('dev','development_selection_clocks')]:
    for mid in data[key]:
        path,=(root/'research/sumo-rl').glob('*clocks/'+mid+'.txt.gz')
        lines.append(role+'\t'+str(path.relative_to(root)))
# Collect four new W-tap mirror clocks after freezing the selection rule.
write('clocks.tsv','\n'.join(lines+[f'test\t{dest.relative_to(root)}/sealed-{i}.txt.gz' for i in range(4)])+'\n')
