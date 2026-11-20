"""Score the matched target as soon as its two checkpoints exist, independently of the field panel."""
from id_evaluate import *
from league_tournament import stats
from id_runner import launch

def measure(target,update):
    early,count,_=budgets(target);matched=early if update==64 else count
    folder=DIR/('dedicated-'+target)
    shared=DIR/'informed'/f'u{update:03d}'
    arms={'informed-correct':shared/(target+'.net'),'dedicated':folder/f'n{matched:05d}.net',
          'shared-initializer':DIR/'shared-mlp/initial.net','wtap':'wtap'}
    dest=DIR/'capacity-development'/target/f'u{update:03d}.json'
    if dest.exists():return
    cells=make_panel(CLOCKS['dev'],4)
    w=oracle_worker();table={};by_donor={}
    try:
        for name,ref in arms.items():
            plan=[[ref,target,clk,s,a,b,x,y] for _,_,s,a,b,clk,x,y in cells]
            data=[]
            for j in range(0,len(plan),32):data.extend(w.run(plan[j:j+32],False))
            rows=[[i,r,s,a,b,v[0],v[1]] for (i,r,s,a,b,_,_,_),v in zip(cells,data)]
            table[name]=stats(rows);by_donor[name]=np.array(table[name]['clock'])
    finally:w.close()
    delta=by_donor['dedicated']-by_donor['informed-correct']
    write_json(dest,dict(equal_target_fixtures=matched,total_shared_duels=64*update,cells=table,
        specialist_minus_shared=float(delta.mean()),paired_bounds=lower_contrast(delta,.025),
        scope='development, eight matched clock donors, one training seed'),fixed=True)

if __name__=='__main__':
    if len(sys.argv)>1:
        measure(sys.argv[1],64)
    else:
        launch('opponent_id.py','train','informed',64)
        for target in (INCUMBENT,BASE_COUNTER):
            if (DIR/('dedicated-'+target)/f'n{budgets(target)[0]:05d}.net').exists():
                launch('id_focus.py',target)
