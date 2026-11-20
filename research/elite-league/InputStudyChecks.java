import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.SumoInputs;

final class InputStudyChecks {
    static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    static int step(DuelNetwork n,int i){
        n.localTiming(i*48.5,i%3==0?10:60);
        return n.step(-4+i*.08,12,0,4-i*.13,12.2,i*.02,-.65,.25,.15,true,false,20,400,31);
    }
    public static void main(String[] args)throws Exception{
        InputStudy.field();var parent=PingLeague.field.get(1).model().weights();int cases=0;
        for(var arm:InputStudy.Arm.values()){
            float[] w=InputStudy.warm(arm,parent,317);var n=new DuelNetwork(arm.family(),w);
            var ref=new DuelNetwork(DuelNetwork.Family.MLP,parent);DuelNetwork fork=null;
            for(int i=0;i<30;i++){
                int a=step(n,i),b=step(ref,i);var x=n.features();
                if((arm.mask&15)==0)check(Arrays.equals(n.scores(),ref.scores()),"Warm widening changed scores");
                if(arm==InputStudy.Arm.blind8)check(x[15]==0,"Own ping leaked");
                if(arm==InputStudy.Arm.noself8)check(x[2]==0&&x[3]==0&&x[4]==0,"Own velocity leaked");
                if(arm==InputStudy.Arm.nofoe8)check(x[5]==0&&x[6]==0&&x[7]==0,"Target motion leaked");
                if(arm==InputStudy.Arm.nohurt8)check(x[10]==0&&x[11]==0&&x[21]==0,"Hurt cue leaked");
                if((arm.mask&SumoInputs.TIMING)!=0)check(Math.abs(x[33]-(i%3==0?-.8:.2))<1e-8,"Physical press age");
                if((arm.mask&SumoInputs.MOTION)!=0&&i>0)check(x[25]!=0,"Opponent velocity filter");
                if(fork!=null){check(step(fork,i)==a&&Arrays.equals(fork.features(),x),"History fork");fork.applied(a);}
                n.applied(a);ref.applied(b);if(i==9)fork=n.copy();cases++;
            }
            var h=new double[DuelNetwork.width(arm.family())];var out=new double[5];
            var x=n.features();PPO.forward(w,x,h,out);check(Arrays.equals(out,n.scores()),"Trainer/inference scores");
            var g=new double[w.length];double[] d={.11,-.2,.3,.4,-.5};PPO.backward(w,x,h,d,g,1);
            for(int k=0;k<w.length-1;k+=3){
                float orig=w[k],lo=orig-.001f,hi=orig+.001f;double v=0;
                w[k]=hi;PPO.forward(w,x,h,out);for(int j=0;j<5;j++)v+=out[j]*d[j];
                w[k]=lo;PPO.forward(w,x,h,out);for(int j=0;j<5;j++)v-=out[j]*d[j];w[k]=orig;
                check(Math.abs(v/(hi-lo)-g[k])<.0003,"Gradient "+arm+" "+k);
            }
        }
        System.out.println(cases+" matched feature and action rows plus numerical gradients for all 11 treatments");
    }
}
