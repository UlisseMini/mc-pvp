"""Plot paired training-seed effects on prospective simulator fights."""
import csv
from collections import defaultdict
from pathlib import Path
from statistics import mean
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.ticker import MultipleLocator, FuncFormatter

DIR = Path(__file__).resolve().parent
LABELS = [
    ('noself8','Remove own velocity'), ('nohurt8','Remove hurt timing'),
    ('nofoe8','Remove opponent motion'), ('blind8','Remove own ping'),
    ('motion8','Add richer motion estimates'), ('timing8','Add reach and click timing'),
    ('edge8','Add edge distances'), ('wide32','Widen from 8 to 32 units'),
    ('full8','Add all input groups, 8 units'), ('full32','Add all input groups, 32 units'),
]
SEEDS = [7181,29137,66047]
scores = defaultdict(list)
with (DIR/'transfer.csv').open() as data:
    for row in csv.DictReader(data):
        scores[row['arm']].append(float(row['reward']))
base = [mean(scores[f'base8-{seed}']) for seed in SEEDS]
fig, ax = plt.subplots(figsize=(9.2,5.3), dpi=170)
fig.subplots_adjust(left=.31,right=.965,top=.78,bottom=.19)
fig.text(.035,.935,'Which changes helped the sumo policy?',fontsize=19,weight='bold',color='#202b36')
fig.text(.035,.875,'Change in simulator score vs the 8-unit baseline, in percentage points',fontsize=11,color='#4a5663')
ax.set_xlim(-22,6.7)
ax.set_ylim(-.8,len(LABELS)-.3)
ax.axvline(0,color='#485463',lw=1.15,zorder=1)
ax.grid(axis='x',color='#e3e6e9',lw=.75)
for i,(group,label) in enumerate(LABELS):
    y=len(LABELS)-1-i
    values=[100*(mean(scores[f'{group}-{seed}'])-b) for seed,b in zip(SEEDS,base)]
    ax.hlines(y,min(values),max(values),color='#9aa8b3',lw=1.3,zorder=2)
    ax.scatter(values,[y]*3,s=31,facecolor='white',edgecolor='#2a6785',linewidth=1.3,zorder=3)
    ax.scatter([mean(values)],[y],s=33,marker='D',color='#163d51',zorder=4)
ax.set_yticks(range(len(LABELS)),labels=[label for _,label in reversed(LABELS)],fontsize=9.5)
ax.xaxis.set_major_locator(MultipleLocator(5))
ax.xaxis.set_major_formatter(FuncFormatter(lambda x,pos:f'{x:+g}' if x else '0'))
ax.tick_params(length=0,pad=8)
for spine in ax.spines.values():spine.set_visible(False)
fig.text(.035,.078,'Circles show the three training seeds. Diamonds show their means.',fontsize=9,color='#44515e')
fig.text(.035,.035,f'480 matched fights per seed and policy. Baseline {mean(base):.1%} across ten opponents. Plain W-tap {mean(scores["wtap"]):.1%}.',fontsize=9,color='#44515e')
fig.savefig(DIR/'feature-effects.png',facecolor='white',dpi=170)
