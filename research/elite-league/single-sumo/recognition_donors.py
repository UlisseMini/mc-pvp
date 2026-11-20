"""Change donor identity while keeping the amount of unrelated history and the recipient's last six inputs fixed."""
from recognition_payoff import FOLDER,SEED,CUTOFF,OUT,Identifier,dataset,outcomes,responders,lower_contrast
from learner import torch,np,json,write_json
def run():
    write_json(FOLDER/'same-style-protocol.json',dict(split='exposed development only',head=24,seed=SEED,
        pairing='next one of the four repetitions for the same timing recording, starting seat and both RTTs',
        intervention='recipient gets the same donor match seeds in both arms; either same opponent style or other style through decision 18; retain original observations 19–24 and physical match',
        metric='ring-out score difference clustered by original recording, with fixed and W-tap comparisons'),fixed=True)
    x,_,donor=dataset('dev');r=outcomes('dev',responders());m=Identifier(True)
    m.load_state_dict(torch.load(OUT/f'history-{SEED}.pt',weights_only=True))
    n=len(x);t=torch.tensor(x[:,:,:CUTOFF]).flatten(0,1)
    with torch.no_grad():
        _,h=m.encoder(t[:,:-6])
        # Fixtures are ordered clock, rep, seat, RTT cell, opponent.
        h=h.reshape(1,len(np.unique(donor)),4,2,4,2,32)
        rotated=h.roll(-1,2)
        def score(hidden):
            y=m.readout(m.encoder(t[:,-6:],hidden.reshape(1,-1,32).contiguous())[0][:,-1])
            choice=y.argmax(-1).numpy().reshape(n,2)
            return np.where(choice==0,r['routed-0'],r['routed-1'])
        arms={'intact':score(h),'same':score(rotated),'opposite':score(rotated.flip(5)),'opposite-same-seeds':score(h.flip(5))}
    report={name:dict(score=float(v.mean()),by_recording=[float(v[donor==i].mean()) for i in np.unique(donor)]) for name,v in arms.items()}
    gains={}
    for a,b in [('same','opposite'),('intact','same'),('intact','opposite-same-seeds')]:
        diff=np.subtract(report[a]['by_recording'],report[b]['by_recording'])
        gains[a+' minus '+b]=[float(diff.mean()),*lower_contrast(diff,.025)]
    baseline=json.loads((FOLDER/'intervals-dev.json').read_text())['absolute']
    write_json(FOLDER/'same-style-dev.json',dict(arms=report,paired=gains,plain_wtap=baseline['wtap-from-spawn'],fixed=baseline['fixed']),fixed=True)
if __name__=='__main__':run()
