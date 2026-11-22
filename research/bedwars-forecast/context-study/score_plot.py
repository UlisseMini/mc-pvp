"""Plot matched held-out scores and the learned mixture's weights."""
import argparse
import csv
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np


HORIZONS = (5, 10, 30, 60, 120)


def rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream))


def scores(data, model, horizon, cohort='all', metric='block'):
    return {r['game']:float(r[metric]) for r in data if r['model']==model and r['horizon']==str(horizon)
            and r['cohort']==cohort}


def main(path):
    data = rows(path/'game-scores.csv')
    weights = rows(path/'weights.csv')
    plt.rcParams.update({'font.family':'DejaVu Sans', 'axes.labelcolor':'#c0cbd6',
                         'xtick.color':'#a8b6c8','ytick.color':'#a8b6c8','axes.titlecolor':'#e7eef8'})
    fig, axes = plt.subplots(2,2,figsize=(13,8.75),layout='constrained',facecolor='#0b1520')
    fig.suptitle('Predicting BedWars locations from player history',fontsize=23,color='#e7eef8',fontweight='bold')
    for ax in axes.ravel():
        ax.set_facecolor('#121d29')
        for spine in ax.spines.values():
            spine.set_color('#344657')
        ax.grid(axis='y',alpha=.8,color='#2f4054')
        ax.set_axisbelow(True)
    a,b,c,d = axes.ravel()
    palette = [('map only','#8d9eb8'),('frozen motion','#ffcb77'),('frozen heatmap','#a9a2ff'),
               ('old meta history','#62bbde'),('full personal gate','#6be4be')]
    for name,col in palette:
        all_ = [np.mean(list(scores(data,name,h).values())) for h in HORIZONS]
        a.plot(range(len(HORIZONS)),all_,marker='o',color=col,linewidth=2,label=name.title())
    a.set_title('Exact foot-block forecasting',loc='left',pad=10)
    a.set_ylabel('Log loss (nats · lower is better)')
    a.legend(labelcolor='#e7eef8',facecolor='#172638',edgecolor='#344657',fontsize=8,ncol=2)
    a.set_xticks(range(5),[f'{h}s' for h in HORIZONS])

    plans = [('frozen heatmap','Frozen heatmap','#a9a2ff'),('current-state gate','Current-state gate','#ffcb77'),
             ('old meta history','Previous adaptation','#62bbde')]
    for i,(name,label,col) in enumerate(plans):
        diff = []
        for h in HORIZONS:
            p = scores(data,name,h,'late')
            q = scores(data,'full personal gate',h,'late')
            diff.append(np.mean([p[g]-q[g] for g in p]))
        b.bar(np.arange(5)+.23*(i-1),diff,width=.21,color=col,label=label)
    b.axhline(0,color='#9aaabd',linewidth=.8)
    b.set_title('What personal context adds late in a match',loc='left',pad=10)
    b.set_ylabel('Nats saved by full model')
    b.set_xticks(range(5),[f'{h}s' for h in HORIZONS])
    b.legend(labelcolor='#e7eef8',facecolor='#172638',edgecolor='#344657',fontsize=8)

    p = scores(data,'frozen heatmap',60,'late')
    q = scores(data,'full personal gate',60,'late')
    r = scores(data,'old meta history',60,'late')
    for i,(ref,label,col) in enumerate(((p,'Versus frozen heatmap','#a9a2ff'),(r,'Versus previous adaptation','#62bbde'))):
        c.bar(np.arange(len(q))+.26*(i-.5),[ref[g]-q[g] for g in q],width=.24,color=col,label=label)
    c.axhline(0,color='#9aaabd',linewidth=.8)
    c.set_xticks(range(len(q)),[g.replace('game-','Match ') for g in q])
    c.set_title('60-second gains by evaluation match',loc='left',pad=10)
    c.set_ylabel('Nats saved · late-game cohort')
    c.legend(labelcolor='#e7eef8',facecolor='#172638',edgecolor='#344657',fontsize=8)

    expert_keys = ['frozen heatmap','frozen motion','stay tightly','recent trail','older locations','route matches','home']
    colors = ['#91a7cf','#b7a3ec','#fece7f','#64dfba','#67b2e4','#ff9474','#89c17e']
    labels = ('dwell ≥8s','moving ≥2 blocks/s')
    targets = [next((float(t['mean_weight']) for t in weights if t['model']=='full personal gate' and
                    t['horizon']=='60' and t['cohort']==case and t['expert']==expert),0)
               for case in labels for expert in expert_keys]
    xs = np.array(targets).reshape(len(labels),-1)
    left = np.zeros(len(labels))
    for i,(name,col) in enumerate(zip(expert_keys,colors)):
        d.barh(range(len(labels)),xs[:,i],left=left,color=col,label=name.title())
        left += xs[:,i]
    d.set_xlim(0,1)
    d.set_yticks(range(len(labels)),['Stopped ≥8s','Moving ≥2 blocks/s'])
    d.invert_yaxis()
    d.set_xlabel('Mean share of forecast probability')
    d.set_title('What the learned gate trusted at 60 seconds',loc='left',pad=10)
    d.legend(labelcolor='#e7eef8',facecolor='#172638',edgecolor='#344657',fontsize=8,ncol=3,
             bbox_to_anchor=(0,-.17),loc='upper left')
    fig.savefig(path/'context-results.png',dpi=150,facecolor=fig.get_facecolor())


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results',type=Path)
    args=parser.parse_args()
    main(args.results)
