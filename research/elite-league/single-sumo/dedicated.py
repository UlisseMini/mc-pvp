"""Same-width single-opponent PPO, with both equal opponent exposure and equal total duels."""
from opponent_id import *
from league_tournament import digest

@lru_cache(maxsize=None)
def budgets(target):
    dummy={i:'unused' for i in range(N)}
    original=[]
    for step in range(256):
        rows,_=schedule(step,'informed',dummy)
        original.extend(r[1:] for r in rows if r[1]==target)
        if step==63:first=len(original)
    return first,len(original),original

def protocol():
    first,count,orig=budgets(INCUMBENT)
    d=dict(primary=INCUMBENT,equal_exposure_at_u64=first,equal_exposure_at_u256=count,total_budget=16384,
           ordinary_mlp_width=180,wide_shared_width=360,widening='same function at initialization; random extra hidden units with zero output columns',
           comparison=['blind 180','ID 180','ID 360','directly routed 180','dedicated 180 equal opponent exposure','dedicated 180 equal total matches','plain W-tap'],
           exact_target_fixture_hash=digest(json.dumps(orig,separators=(',',':'))),primary_metrics='W/D/L and paired scores by independent timing donor, seat and both RTTs',
           caveat='target-only PPO collects batches of up to 64 target duels; the mixed PPO batches also contain other opponents',
           secondary=BASE_COUNTER,secondary_rule='also test the teacher as a difficult target if the initializer already scores at least 0.90 against the primary or either final 180-unit model scores at least 0.95',
           no_title_change=True)
    write_json(DIR/'capacity-protocol.json',d,fixed=True)
    return d

def extension(target,start,count):
    options=[x for x in range(151) if x not in (31,109)];out=[]
    for i in range(start,start+count):
        rng=random.Random(6797759+i*6019)
        out.append([target,rng.choice(CLOCKS['train']),rng.choice('ab'),rng.choice(options),rng.choice(options),rng.getrandbits(60),rng.getrandbits(60)])
    return out

def train_target(target,n_updates,wins_only=False):
    early,matched,orig=budgets(target);total=16384
    folder=DIR/(('ringout-only-' if wins_only else 'dedicated-')+target);folder.mkdir(exist_ok=True,parents=True)
    with (folder/'fit.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        _train_target(target,n_updates,early,matched,orig,total,folder,wins_only)

def _train_target(target,n_updates,early,matched,orig,total,folder,wins_only):
    milestones=[early,matched,total]
    torch.manual_seed(3176221);model=Actor('mlp')
    model.load_state_dict(torch.load(DIR/'shared-mlp/trainer.pt',map_location='cpu',weights_only=False)['net'])
    opt=torch.optim.Adam(model.parameters(),lr=.00035);state=folder/'optimizer.pt';seen=0;steps=0;log=[]
    if state.exists():
        prev=torch.load(state,map_location='cpu',weights_only=False);seen=prev['seen'];steps=prev['steps'];log=prev['log']
        model.load_state_dict(prev['net']);opt.load_state_dict(prev['opt'])
    def checkpoint():
        model.export(folder/f'n{seen:05d}.net')
    if seen in milestones:checkpoint()
    w=Worker()
    try:
        for _ in range(n_updates):
            if seen==total:break
            target_stop=next(m for m in milestones if m>seen);n=min(64,target_stop-seen)
            fixtures=orig[seen:seen+n] if seen<matched else extension(target,seen-matched,n)
            weights=w.folder/'actor.net';model.export(weights)
            t=time.monotonic();rows=w.run([[weights,*r] for r in fixtures],True)
            ep=prepare([(float(v==1),duration,data) for v,duration,data in rows] if wins_only else rows)
            metrics=train_update(model,opt,ep,5176201+steps)
            seen+=len(rows);steps+=1;log.append(dict(duels=seen,update=steps,heads=sum(map(len,ep)),seconds=time.monotonic()-t,score=float(np.mean([r[0] for r in rows])),**metrics))
            buf=io.BytesIO();torch.save(dict(seen=seen,steps=steps,log=log,net=model.state_dict(),opt=opt.state_dict()),buf);atomic(state,buf.getvalue())
            if seen in milestones:checkpoint()
            write_json(folder/'progress.json',log[-1])
        if log:write_json(folder/'progress.json',log[-1])
    finally:w.close()
if __name__=='__main__':
    import sys
    protocol()
    if len(sys.argv)>1:train_target(sys.argv[1],int(sys.argv[2]),sys.argv[3:]==['wins-only'])
