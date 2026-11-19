"""Compare installed Forge policy decisions and forked state with the simulator JVM."""
import argparse
from pathlib import Path
import subprocess
import tempfile

from adaptation import dump, sha
from native_trials import active_bridge
from run import HERE, ROOT, compile_model
from runtime_checks import JAVA8


def run(out, weights):
    jar=ROOT/'runtime/bots/0/mods/practice-bridge.jar'
    if sha(jar)!=active_bridge():
        raise ValueError('The loaded client bridges differ')
    model=compile_model()
    with tempfile.TemporaryDirectory() as folder:
        subprocess.run(['javac','-J-XX:ActiveProcessorCount=2','--release','8','-proc:none',
                        '-sourcepath','','-cp',str(jar),'-d',folder,str(HERE/'RuntimeParity.java')],check=True)
        def decisions(jvm,implementation):
            return subprocess.check_output([str(jvm),'-XX:+UseSerialGC',
                    '-Xms12m','-Xmx96m','-cp',folder+':'+str(implementation),'RuntimeParity',
                    *map(str,weights)],text=True).splitlines()
        live=decisions(JAVA8,jar)
        simulated=decisions('java',model)
    if live!=simulated or len(live)!=len(weights):
        raise ValueError('Different applied action streams')
    dump(out,dict(native_bridge=sha(jar),weights={p.stem:sha(p) for p in weights},results=live))


if __name__=='__main__':
    from cpu_limit import supervise_script
    supervise_script()
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    parser.add_argument('weights',type=Path,nargs='+')
    args=parser.parse_args()
    run(args.out,args.weights)
