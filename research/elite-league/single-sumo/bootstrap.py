"""A separate initialization experiment after the cold PPO failed the W-tap control."""
from learner import *
from assay import CP

def fit(path,kind,teacher,updates=32,teacher_map=None):
    spec=dict(kind=kind,teacher=teacher,updates=updates,duels_per_update=32,training_clocks=CLOCKS['train'],
              curriculum='12 teacher-only updates, then 35 percent teacher on mixed student trajectories',
              seed=9831717,objective='conditional physical action cross entropy',clip_grad=.6,lr=.001)
    if teacher_map is not None:spec['teacher_by_opponent']=teacher_map
    write_json(path/'spec.json',spec,fixed=True)
    torch.manual_seed(spec['seed']);model=Actor(kind);optimizer=torch.optim.Adam(model.parameters(),lr=spec['lr'])
    rng=random.Random(spec['seed']);logs=[];path.mkdir(exist_ok=True,parents=True)
    if (path/'trainer.pt').exists():return
    with tempfile.TemporaryDirectory(dir=BUILD) as temp:
        temp=Path(temp);net=temp/'actor.net';plan=temp/'plan';data=temp/'data'
        for step in range(updates):
            model.export(net);portion=1 if step<12 else .35
            rows=[]
            pings=[x for x in range(151) if x not in (31,109)]
            for _ in range(32):
                foe=rng.choice(list(teacher_map) if teacher_map is not None else FIELD)
                choice=teacher_map[foe] if teacher_map is not None else teacher
                rows.append((net,foe,rng.choice(CLOCKS['train']),rng.choice('ab'),rng.choice(pings),rng.choice(pings),
                             rng.getrandbits(60),rng.getrandbits(60),choice,portion))
            plan.write_text(''.join('\t'.join(map(str,r))+'\n' for r in rows))
            subprocess.run(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx650m','-cp',CP,'Demonstrations',plan,data],check=True)
            raw=data.read_bytes();pos=0;ep=[];scores=[]
            for row in rows:
                n,score=struct.unpack_from('>if',raw,pos);pos+=8
                v=np.frombuffer(raw,'>f4',n*26,pos).astype('f4').reshape(n,26);pos+=v.size*4
                ep.append(torch.from_numpy(v));scores.append(score)
            assert pos==len(raw);losses=[]
            for _ in range(3):
                rng.shuffle(ep)
                for start in range(0,len(ep),8):
                    seq=pack_sequence(ep[start:start+8],enforce_sorted=False);d=seq.data
                    y=model.sequences(PackedSequence(d[:,:25],seq.batch_sizes,seq.sorted_indices,seq.unsorted_indices))
                    loss=-log_prob(y,d[:,8]>0,d[:,25].long()).mean()
                    optimizer.zero_grad();loss.backward();nn.utils.clip_grad_norm_(model.parameters(),.6,error_if_nonfinite=True);optimizer.step()
                    losses.append(float(loss))
            logs.append(dict(step=step+1,loss=float(np.mean(losses)),score=float(np.mean(scores)),heads=sum(map(len,ep))))
            write_json(path/'progress.json',logs[-1])
    with torch.no_grad():
        # The supervised control loss supplies no value targets. Start the PPO critic at zero.
        model.head.weight[-1].zero_();model.head.bias[-1].zero_()
    model.export(path/'initial.net');buf=io.BytesIO();torch.save(dict(net=model.state_dict(),logs=logs),buf);atomic(path/'trainer.pt',buf.getvalue())
if __name__=='__main__':
    import sys
    fit(Path(sys.argv[1]),sys.argv[2],sys.argv[3])
