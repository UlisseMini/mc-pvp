package lab;

import java.util.Arrays;

/** Two primed policies. A scripted sparring partner or a learned selector decides whose keys to use. */
public final class PairedExperts {
    public static final DuelNetwork.Family[] EXPERTS={DuelNetwork.Family.MLP,DuelNetwork.Family.RECURRENT,
            DuelNetwork.Family.HYBRID,DuelNetwork.Family.TACTIC,DuelNetwork.Family.LINEAR};
    // mode, expert IDs, trigger, delay (heads), repeat interval, minimum dwell, hysteresis
    public static final int HEADER=8;
    private static final int[] SIGNALS={0,2,5,6,10,11,14};
    public static final int GATE=1+DuelNetwork.INPUTS+2*SIGNALS.length;
    public static final int BLOCK=Math.max(DuelNetwork.size(DuelNetwork.Family.RECURRENT),DuelNetwork.size(DuelNetwork.Family.TACTIC));
    public static final int SIZE=HEADER+GATE+2*BLOCK;
    private final float[] weights;
    private final DuelNetwork[] experts=new DuelNetwork[2];
    private final double[] fast=new double[SIGNALS.length],slow=new double[SIGNALS.length];
    private final int mode,trigger,delay,period,dwell;
    private int heads,selected,switches,lastSwitch,triggerHead=-1;
    private double score;

    static int integer(float value,int max){return Math.max(0,Math.min(max,Math.round(value)));}
    PairedExperts(float[] w){
        weights=w;mode=integer(w[0],2);trigger=integer(w[3],2);
        delay=integer(w[4],200);period=integer(w[5],200);dwell=Math.max(1,integer(w[6],40));
        for(int i=0;i<2;i++){
            DuelNetwork.Family family=EXPERTS[integer(w[1+i],EXPERTS.length-1)];
            int start=HEADER+GATE+i*BLOCK;
            experts[i]=new DuelNetwork(family,Arrays.copyOfRange(w,start,start+DuelNetwork.size(family)));
        }
    }
    private PairedExperts(PairedExperts p){
        weights=p.weights;mode=p.mode;trigger=p.trigger;delay=p.delay;period=p.period;dwell=p.dwell;
        for(int i=0;i<2;i++)experts[i]=p.experts[i].copy();
        heads=p.heads;selected=p.selected;switches=p.switches;lastSwitch=p.lastSwitch;triggerHead=p.triggerHead;score=p.score;
        System.arraycopy(p.fast,0,fast,0,fast.length);System.arraycopy(p.slow,0,slow,0,slow.length);
    }
    PairedExperts copy(){return new PairedExperts(this);}
    public int selected(){return selected;}
    public int switches(){return switches;}
    public double score(){return score;}
    void applied(int action){for(DuelNetwork expert:experts)expert.applied(action);}

    int step(double[] view,double x,double y,double z,double tx,double ty,double tz,double vx,double vy,double vz,
             boolean ground,boolean sprint,double ownAge,double targetAge,double rtt){
        int first=experts[0].step(x,y,z,tx,ty,tz,vx,vy,vz,ground,sprint,ownAge,targetAge,rtt);
        int second=experts[1].step(x,y,z,tx,ty,tz,vx,vy,vz,ground,sprint,ownAge,targetAge,rtt);
        int next=selected;
        if(mode==1){
            if(triggerHead<0 && (trigger==0 || Double.isFinite(trigger==1?ownAge:targetAge)))triggerHead=heads;
            int age=heads-triggerHead-delay;
            if(triggerHead>=0 && age>=0)next=period==0?1:1-(age/period%2);
        }else if(mode==2){
            int k=HEADER;
            score=weights[k++];
            for(double v:view)score+=weights[k++]*v;
            for(int i=0;i<SIGNALS.length;i++){
                double v=view[SIGNALS[i]];
                if(heads==0){fast[i]=v;slow[i]=v;}
                else{fast[i]+=.32*(v-fast[i]);slow[i]+=.065*(v-slow[i]);}
                score+=weights[k++]*slow[i]+weights[k++]*(fast[i]-slow[i]);
            }
            if(heads-lastSwitch>=dwell){
                double margin=Math.max(0,weights[7]);
                if(score>margin)next=1;
                else if(score< -margin)next=0;
            }
        }
        if(next!=selected){selected=next;switches++;lastSwitch=heads;}
        heads++;
        return selected==0?first:second;
    }
}
