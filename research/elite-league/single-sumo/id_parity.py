"""Check unequal referee branches and the widened network across Python, the roller and native Java 8."""
from opponent_id import *
from build import sim_paths
import sys
sys.path.insert(0,str(ROOT))
from forge_runtime import JAVA8

def run():
    narrow=architecture('informed')
    wide=architecture('wide')
    probe=pack_sequence([torch.randn(90,25)])
    labels=torch.arange(90)%N
    initial_error=float((narrow.sequences(probe,labels)-wide.sequences(probe,labels)).detach().abs().max())
    assert initial_error<1e-5
    with torch.no_grad():
        # Exercise the additional columns and two deliberately different referee assignments.
        wide.base.head.weight[:,180:].normal_(0,.04)
        wide.context.normal_(0,.25)
    pin=runtime();bridge=pin['forge']
    fixture=BUILD/'native-trace.tsv'
    w=oracle_worker()
    try:
        results={}
        for idx in (-1,FIELD.index(INCUMBENT),FIELD.index(BASE_COUNTER)):
            net=w.folder/f'wide-{idx}.net'
            wide.export(net,idx)
            rows=w.run([[net,INCUMBENT,CLOCKS['dev'][0],'a',31,109,193,29]],True)[0][2]
            v=torch.from_numpy(rows[:,:25])
            y=wide.sequences(pack_sequence([v]),torch.full((len(v),),idx,dtype=torch.long)).detach()
            error=float(abs(y-torch.from_numpy(rows[:,26:32])).max())
            assert error<1e-5
            hashes=[]
            for j,cp in [(JAVA8,[BUILD/bridge['jar']]),('java',[BUILD/pin['simulator']])]:
                hashes.append(subprocess.check_output([j,'-XX:+UseSerialGC','-cp',
                    os.pathsep.join(map(str,[BUILD/'trace-classes',*cp])),'LeagueTrace',net,fixture],text=True).strip())
            assert hashes[0]==hashes[1]
            results[str(idx)]=dict(model_max_abs=error,native_heads_and_feature_action_digest=hashes[0])
        write_json(DIR/'wide-parity.json',dict(same_initial_function_max_abs=initial_error,
            bridge=bridge,source_trace_sha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),checks=results),fixed=True)
    finally:w.close()
if __name__=='__main__':run()
