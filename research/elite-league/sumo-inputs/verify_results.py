"""Cross-check result files against the frozen rosters, physical games and playable policy store."""
import csv
import gzip
from collections import Counter
from live import D, ROOT, B, read, freeze, sha
from report import grouped


def policy(name):
    return dict(line.split('=',1) for line in (ROOT/'policies'/(name+'.properties')).read_text().splitlines() if '=' in line)


def verify():
    representatives=read(D/'entrants.json')
    for model, digest in representatives['weights'].items():
        assert sha(D/'fits'/(model+'.net'))==digest
    for alias, model in representatives['representatives'].items():
        assert policy(alias)['duel_network']==(D/'fits'/(model+'.net')).read_text().strip()
    for alias, model in read(D/'refine/selection.json')['checkpoints'].items():
        assert policy('sumo-input-'+alias)['duel_network']==(D/'refine'/(model+'.net')).read_text().strip()
    finals=read(D/'summary.json')
    historical=read(D/'historical-title.json')
    titles={'sumo-input-screen':finals['screen_winner'],'sumo-input-final':finals['confirmation_winner'],
            'sumo-input-refinement':read(D/'refine/summary.json')['winner'],
            'sumo-vote-final':read(D/'ensemble/summary.json')['winner']}
    if historical['eligible']:titles['sumo-input-history']=historical['winner']
    for alias, winner in titles.items():
        left,right=policy(alias),policy(winner)
        keys=set(left)|set(right)
        assert all(left.get(k)==right.get(k) for k in keys if not k.startswith(('league_','description')))
    if historical['eligible']:
        assert policy('best-sumo')['title_source']==historical['winner']

    stages=['screen','confirmation','masks','refinement','axes','ensemble']+(['title'] if historical['eligible'] else [])
    outcomes={};seen=set();invalid=[];checked=0;presses=0;local=0;smallest_interval_ms=1e6;bridges=set()
    for stage in stages:
        docket=read(D/(stage+'.json'))
        evidence=read(D/(stage+'-audit.json'))
        assert evidence['rows'].keys()==docket['plan'].keys()
        assert evidence['original']==docket['matches']
        groups=Counter()
        for request,r in evidence['rows'].items():
            assert r['valid'] and r['mid'] not in seen
            seen.add(r['mid'])
            record=read(ROOT/'runtime/results'/(r['mid']+'.json'))
            raw=read(B/'scores'/(r['mid']+'.json'))
            assert raw['rtt']==r['own'] and raw['opponent_rtt']==r['other']
            assert raw['arm']==r['arm'] and raw['opponent']==r['foe']
            assert record['request']==request or record['request'].startswith(request+'-r')
            for seat in 'ab':
                bridges.add(record['client_'+seat]['bridge_sha256'])
                assert record['parameters_'+seat]['click_cps']=='20'
                presses+=raw['press'][seat][0]
            if r['click_min_ms'] is not None:
                assert r['click_min_ms']>=50-1e-6
                smallest_interval_ms=min(smallest_interval_ms,r['click_min_ms'])
            checked+=r['checked_heads'];local+=sum(r['local_timing'].values())
            groups[r['outcome']]+=1
        invalid+=evidence['invalid']
        outcomes[stage]=dict(groups)
    assert len(bridges)==1
    counts={}
    for path,per in [(D/'transfer.csv',480),(D/'refine/transfer.csv',320),(D/'axes/transfer.csv',320),
                     (D/'archive.csv.gz',257*24),(D/'ensemble/transfer.csv',320),(D/'ensemble/archive.csv.gz',257*16)]:
        with (gzip.open(path,'rt') if path.suffix=='.gz' else path.open()) as file:
            entrants=grouped(csv.DictReader(file))
        assert 'wtap' in entrants and all(len(v)==per for v in entrants.values())
        keys=entrants['wtap'].keys()
        assert all(row.keys()==keys for row in entrants.values())
        counts[str(path.relative_to(D))]=len(entrants)*per
    clocks=[set(read(file)['matches'].values()) for file in (D/'donors.json',D/'refine/donors.json',D/'axes/donors.json',D/'ensemble/donors.json')]
    assert list(map(len,clocks))==[6,4,4,4] and len(set.union(*clocks))==18
    training=33*320*32+6*384*32
    freeze(D/'verification.json',dict(training_bouts=training,prospective_model_bouts=counts,
        unique_native_bouts=len(seen),clock_only_native_bouts=len(clocks[0]),stages=outcomes,
        invalid_attempts=invalid,verified_movements=checked,local_feature_timing_records=local,
        physical_presses=presses,min_press_interval_ms=smallest_interval_ms,bridge_sha256=bridges.pop(),
        published=titles,best_sumo=policy('best-sumo')['title_source']))

if __name__=='__main__':
    verify()
