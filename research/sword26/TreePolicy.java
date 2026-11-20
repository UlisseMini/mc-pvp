package sword;

import java.util.*;
import static sword.Fighter.*;

/** Six small decision trees, one per control. Splits, thresholds and leaves are all learned. */
final class TreePolicy {
    static final int SENSORS=25;
    static final int HEADS=6,NODES=7,LEAVES=8,SIZE=NODES*2+LEAVES,COUNT=HEADS*SIZE;
    static final int ACTIVE_HEADS=PerfectAim.ENABLED?HEADS-1:HEADS;
    static int key(double v){return v<-.33?-1:v>.33?1:0;}
    static Keys decide(double[] genes,double[] input){
        int f=0,s=0;boolean run=false,jump=false,press=false;double turn=0;
        for(int h=0;h<ACTIVE_HEADS;h++){
            int node=0,base=h*SIZE;
            while(node<NODES){
                int off=base+2*node;
                node=2*node+(input[(int)genes[off]]<genes[off+1]?1:2);
            }
            double v=genes[base+2*NODES+node-NODES];
            switch(h){
                case 0 -> f=key(v);case 1 -> s=key(v);case 2 -> run=v>0;
                case 3 -> jump=v>0;case 4 -> press=v>0;
                case 5 -> turn=heading(v*1.5);
            }
        }
        return new Keys(f,s,run,jump,press,turn);
    }
    static double gene(int pos,Random r){
        int slot=pos%SIZE;
        if(slot>=2*NODES)return r.nextDouble(-1,1);
        if(slot%2==0)return r.nextInt(SENSORS);
        return r.nextDouble(-1.5,1.5);
    }
    static double[] random(Random r){
        double[] g=new double[COUNT];for(int i=0;i<ACTIVE_HEADS*SIZE;i++)g[i]=gene(i,r);return g;
    }
    static double[] child(double[] a,double[] b,Random r){
        double[] g=a.clone();
        if(r.nextBoolean()){
            int head=r.nextInt(ACTIVE_HEADS);System.arraycopy(b,head*SIZE,g,head*SIZE,SIZE);
        }
        int changes=1+r.nextInt(12);
        for(int k=0;k<changes;k++){
            int p=r.nextInt(ACTIVE_HEADS*SIZE);
            if(p%SIZE<2*NODES&&p%2==1&&r.nextBoolean())g[p]+=r.nextGaussian()*.12;
            else g[p]=gene(p,r);
        }return g;
    }
}
