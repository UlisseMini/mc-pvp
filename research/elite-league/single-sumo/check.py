from train import *

def check():
    for kind in ("gru","mlp"):
        torch.manual_seed(117)
        net=Actor(kind);x=torch.randn(3,25)
        from torch.autograd import gradcheck
        if kind=="gru":
            fixture=net.encoder.double(); xx=x.double().unsqueeze(1).requires_grad_()
            h=torch.zeros(1,1,32,dtype=torch.float64,requires_grad=True)
            assert gradcheck(lambda a,b:fixture(a,b)[0].sum(),(xx,h),fast_mode=True)
            fixture.float()
        actions=torch.arange(48)
        for ground in (True,False):
            a=actions if ground else actions[:24];y=torch.randn(1,6).repeat(len(a),1)
            lp=log_prob(y,torch.full((len(a),),ground),a)
            assert abs(float(lp.exp().sum())-1)<2e-6
        with tempfile.TemporaryDirectory(dir=BUILD) as t:
            folder=Path(t);model=folder/"model.net";net.export(model)
            w=Worker()
            try:
                scenario=(model,INCUMBENT,CLOCKS["train"][0],"a",30,100,17,29)
                ground,again=w.run([scenario,scenario],True)
                assert ground[:2]==again[:2] and np.array_equal(ground[2],again[2])
                seq=prepare([ground]);d=seq[0]
                x=pack_sequence([d[:,:25]]); y=net.sequences(x)
                error=float((y-d[:,29:35]).abs().max())
                assert error<.0001,(kind,error)
                print(kind,len(d),"ticks, score/value max error",error,flush=True)
            finally:w.close()
if __name__=="__main__":check()
