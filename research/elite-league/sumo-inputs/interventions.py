"""Complete the held-fixed input interventions after the independent confirmation."""
import csv
import subprocess
import sys
from live import D,B,WT,ping,wait,read,fixtures,run_rows
def main():
    wait(lambda:(D/'confirmation-audit.json').exists())
    names=['sumo-input-fixed-no'+s for s in ('motion','edge','timing')]
    rows=fixtures('m',names,[WT,'shift-check-v4-4'])
    run_rows('masks',rows)
    subprocess.run([sys.executable,D.parent/'cpu_limit.py','--audit',sys.executable,'-c',
        "import sys;sys.path.insert(0,sys.argv[1]);from audit_run import certify;certify('masks')",str(D)],check=True)
if __name__=='__main__':main()
