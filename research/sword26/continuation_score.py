"""Score the continuing streak after a native W-tap acquisition, pairing the two arms by opening."""
import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

from audit import classify_contacts, rows, survival
from first_ko import adjudicate


def continuation(events, branch, name, end_tick, landmark, active):
    if branch is None:
        return None
    entry = next(i for i,e in enumerate(events) if e['tick']==branch['tick'] and e['attacker']==name and e['fresh'])
    count, last = 3, int(events[entry]['tick'])
    for i in range(entry, len(events)):
        e = events[i]
        if i!=entry:
            tick = int(e['tick'])
            if e['attacker']!=name or (active and tick-last>22):
                return None
            if not e['fresh']:
                continue
            count, last = count+1, tick
        if count==landmark:
            result = survival(events,i,name,end_tick)['active' if active else 'returns'].copy()
            result['return_upgrade'] = False
            if result['end']=='return':
                returned = next(h for h in events[i+1:] if h['attacker']!=name)
                result['return_upgrade'] = not returned['fresh']
            return result
    return None


def write_csv(path, data):
    with path.open('w') as f:
        writer=csv.DictWriter(f, data[0], lineterminator='\n')
        writer.writeheader()
        writer.writerows(data)


def score(panels, destination):
    detail = {}
    cohorts = defaultdict(lambda: defaultdict(list))
    for run, audit in panels:
        names=json.loads((run/'metadata.json').read_text())['names']
        original={r['id']:r for r in json.loads((run/'results.json').read_text())}
        for match in audit:
            key = (match['rtt'], match['pair'], match['seat'])
            arm = match['policy']
            contacts=rows(run/(match['id']+'-contacts.tsv'))
            _, cut=adjudicate(original[match['id']],contacts,rows(run/(match['id']+'-server.tsv')),names)
            if cut is not None:
                contacts=contacts[:cut+1]
            classify_contacts(contacts)
            for need in (3,5,10):
                for active in (False, True):
                    result=continuation(contacts, match['branch'], names[match['seat']], match['ticks'], need, active)
                    if need==3 and match['branch'] is not None:
                        existing=match['branch']['active' if active else 'returns']
                        if any(result[k]!=v for k,v in existing.items()):raise ValueError('Referee branch changed')
                    cell=detail.setdefault((*key,need,active),{})
                    if arm in cell:raise ValueError('Duplicate native continuation')
                    cell[arm]=result
                    cohorts[match['rtt'],need,active][arm].append(result)
    all_rows=[]
    for (rtt,opening,seat,need,active),arms in sorted(detail.items()):
        row=dict(rtt_ms=rtt,opening=opening,seat=seat,landmark=need,activity_qualified=active)
        for arm in ('learned','wtap'):
            r=arms[arm]
            for field in ('cycles','end','exposure_ticks','return_upgrade'):
                row[arm+'_'+field]=r[field] if r else ('no_entry' if field=='end' else '')
        all_rows.append(row)
    totals=[]
    for (rtt,need,active),arms in sorted(cohorts.items()):
        row=dict(rtt_ms=rtt,landmark=need,activity_qualified=active)
        for arm in ('learned','wtap'):
            entered=[r for r in arms[arm] if r is not None]
            values=dict(openings=len(arms[arm]),entries=len(entered),cycles=sum(r['cycles'] for r in entered),
                return_failures=sum(r['end']=='return' for r in entered),return_upgrades=sum(r['return_upgrade'] for r in entered),
                inactive=sum(r['end']=='inactivity' for r in entered),right_censored=sum(r['end']=='round_end' for r in entered),
                total_exposure_ticks=sum(r['exposure_ticks'] for r in entered),
                further_hits=sorted(r['cycles'] for r in entered),remaining_ticks=sorted(r['exposure_ticks'] for r in entered))
            row.update({arm+'_'+k:v for k,v in values.items()})
        totals.append(row)
    write_csv(destination/'survivor-openings.csv',all_rows)
    write_csv(destination/'survivor-summary.csv',totals)


if __name__=='__main__':
    from setup import BUILD
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence',type=Path)
    root=parser.parse_args().evidence
    panels=[]
    for record in sorted(root.glob('java-*-run.json')):
        run=BUILD/json.loads(record.read_text())['run']
        audit=json.loads(record.with_name(record.name.replace('-run.json','-audit.json')).read_text())
        panels.append((run,audit))
    score(panels,root)
