package lab;

import java.util.Arrays;

/** Open as expert zero. On the input head after observing the first hurt cue,
 * apply the expert chosen from the saved, first-person cue observation. */
public final class ExpertRouter {
    public static final DuelNetwork.Family[] FAMILIES = {
        DuelNetwork.Family.MLP, DuelNetwork.Family.HYBRID, DuelNetwork.Family.RECURRENT, DuelNetwork.Family.MLP
    };
    public static final int INPUTS=30, HIDDEN=12, EXPERTS=4;
    public static final int PARAMETERS=HIDDEN*(INPUTS+1)+EXPERTS*(HIDDEN+1);
    public static final int SIZE=PARAMETERS+1+2*DuelNetwork.size(DuelNetwork.Family.MLP)+
            DuelNetwork.size(DuelNetwork.Family.HYBRID)+DuelNetwork.size(DuelNetwork.Family.RECURRENT);
    private final DuelNetwork[] policies = new DuelNetwork[EXPERTS];
    private final float[] weights;
    private final double[] features=new double[INPUTS], hidden=new double[HIDDEN], scores=new double[EXPERTS];
    private int heads,selected,chosen,forced=-1;
    private double lateral,closing,vertical,ownSprint,ownForward,ownJump,outgoing;
    private boolean triggered,switched;
    private boolean hadOutgoing;

    ExpertRouter(float[] weights) {
        this.weights=weights;
        int offset=PARAMETERS+1;
        for(int i=0;i<policies.length;i++){
            int end=offset+DuelNetwork.size(FAMILIES[i]);
            policies[i]=new DuelNetwork(FAMILIES[i],Arrays.copyOfRange(weights,offset,end));
            offset=end;
        }
    }
    private ExpertRouter(ExpertRouter src) {
        weights=src.weights;
        for(int i=0;i<policies.length;i++)policies[i]=src.policies[i].copy();
        heads=src.heads; selected=src.selected; chosen=src.chosen; forced=src.forced;
        lateral=src.lateral;closing=src.closing;vertical=src.vertical;ownSprint=src.ownSprint;
        ownForward=src.ownForward;ownJump=src.ownJump;outgoing=src.outgoing;hadOutgoing=src.hadOutgoing;
        triggered=src.triggered;switched=src.switched;
        System.arraycopy(src.features,0,features,0,INPUTS);
        System.arraycopy(src.hidden,0,hidden,0,HIDDEN);
        System.arraycopy(src.scores,0,scores,0,EXPERTS);
    }
    ExpertRouter copy(){return new ExpertRouter(this);}
    public int selected(){return selected;}
    public boolean triggered(){return triggered;}
    public double[] cueFeatures(){return features.clone();}

    /** Model-only intervention after copying an identical state at the first cue. */
    public void forceContinuation(int index){
        if(index<0 || index>=EXPERTS || !triggered || switched)throw new IllegalStateException("No pending first-cue choice");
        forced=index;
    }

    public static void predict(float[] w,double[] x,double[] h,double[] scores){
        int n=0;
        for(int i=0;i<HIDDEN;i++){
            double v=w[n++];for(double z:x)v+=w[n++]*z;
            h[i]=v/(1+Math.abs(v));
        }
        for(int i=0;i<EXPERTS;i++){
            double v=w[n++];for(double z:h)v+=w[n++]*z;
            scores[i]=v;
        }
    }
    public static int choose(double[] scores,double margin){
        int best=0;
        for(int i=1;i<EXPERTS;i++)if(scores[i]>scores[best])best=i;
        return scores[best]>scores[0]+margin?best:0;
    }
    int step(double x,double y,double z,double tx,double ty,double tz,double vx,double vy,double vz,
             boolean ground,boolean sprint,double ownAge,double targetAge,double rtt){
        if(triggered && !switched){selected=forced<0?chosen:forced;switched=true;}
        int action=0;
        for(int i=0;i<EXPERTS;i++){
            // Once the choice has taken effect, only the active expert needs new observations.
            if(switched && i!=selected)continue;
            int proposal=policies[i].step(x,y,z,tx,ty,tz,vx,vy,vz,ground,sprint,ownAge,targetAge,rtt);
            if(i==selected)action=proposal;
        }
        if(!triggered){
            double[] instant=policies[0].features();
            heads++;
            lateral+=Math.abs(instant[6]);closing+=instant[5];
            vertical+=instant[7];ownSprint+=sprint?1:0;ownForward+=instant[16];ownJump+=instant[19]>0?1:0;
            if(Double.isFinite(targetAge)){if(!hadOutgoing)outgoing=heads;hadOutgoing=true;}
            if(Double.isFinite(ownAge)){
                System.arraycopy(instant,0,features,0,instant.length);
                features[22]=Math.min(1,heads/40.0);
                features[23]=lateral/heads;features[24]=closing/heads;features[25]=vertical/heads;
                features[26]=ownSprint/heads;features[27]=ownForward/heads;
                features[28]=ownJump/heads;features[29]=hadOutgoing?Math.min(1,(heads-outgoing)/12.0):-1;
                predict(weights,features,hidden,scores);chosen=choose(scores,weights[PARAMETERS]);triggered=true;
            }
        }
        return action;
    }
    void applied(int action){
        for(int i=0;i<EXPERTS;i++)if(!switched || i==selected)policies[i].applied(action);
    }
}
