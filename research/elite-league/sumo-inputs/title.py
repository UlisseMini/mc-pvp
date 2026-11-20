import subprocess
import sys
from live import D,WT,ping,read,wait,freeze,run_rows,fixtures
from report import native,rank,publish,score
from reigning import publish as publish_title
def main():
    wait(lambda:(D/'summary.json').exists() and (D/'refinement-audit.json').exists())
    s=read(D/'summary.json');pick=read(D/'confirmation-selection.json');actor=pick['challenger'];n=pick['model_id']
    passed=s['confirmation_winner']==actor and s['archive'][n]['mean']>=s['archive'][ping.OLD]['mean']
    evidence=D/'historical-title.json'
    if not passed:
        freeze(evidence,dict(eligible=False,challenger=actor,confirmation_winner=s['confirmation_winner'],archive_score=s['archive'][n]['mean'],incumbent_archive=s['archive'][ping.OLD]['mean']))
        return
    foes=[WT,'shift-check-v4-4','v4-sprint-link-jitter-15','v4-reply-g0312',ping.OLD,actor]
    run_rows('title',fixtures('t',[actor,WT,ping.OLD,'sumo-two-ping'],foes))
    subprocess.run([sys.executable,D.parent/'cpu_limit.py','--audit',sys.executable,'-c',
        "import sys;sys.path.insert(0,sys.argv[1]);from audit_run import certify;certify('title')",str(D)],check=True)
    rows=native('title');ordered=rank(rows);winner=ordered[0]
    def metric(n):
        v=rows[n];rates=[score([y for k,y in v.items() if k[0]==f]) for f in foes]
        return (sum(rates)/len(rates)+min(rates))/2,rates[0]
    if metric(winner)==metric(ping.OLD):winner=ping.OLD
    freeze(evidence,dict(eligible=True,winner=winner,ranking=ordered,challenger=actor,metrics={n:metric(n) for n in rows},audit='title-audit.json'))
    publish('sumo-input-history',winner,'input-historical-final')
    publish_title('best-sumo',winner,'Audited historical MMC sumo input-study winner ('+winner+')',evidence)
if __name__=='__main__':main()
