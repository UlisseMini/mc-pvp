import java.util.*;
import lab.DuelNetwork;

final class GradientChecks{
    public static void main(String[] args){
        Random r=new Random(993);var w=Evolve.initial(DuelNetwork.Family.MLP,r).weights();
        for(int i=0;i<w.length;i++)w[i]=(float)(r.nextGaussian()*.35);
        var model=new DuelNetwork(DuelNetwork.Family.MLP,w);
        model.step(0,12,0,3,12.3,0,.1,-.03,0,true,true,100,150,80);
        var x=model.features();double[] h=new double[PPO.H],out=new double[PPO.OUT];
        PPO.forward(w,x,h,out);
        if(!Arrays.equals(out,model.scores()))throw new AssertionError("Training and exported MLP logits differ");
        var distribution=new PPO.Dist(.52);int verified=0;
        for(boolean ground:new boolean[]{false,true}){
            double sum=0;
            for(int k=0;k<4;k++)for(int side=-1;side<=1;side++)for(boolean click:new boolean[]{false,true})for(boolean jump:new boolean[]{false,true}){
                if(jump&&!ground)continue;
                int a=DuelNetwork.pack(k,side,click,jump);
                double[] ds=new double[PPO.OUT];double log=distribution.probability(out,ground,a,ds);
                sum+=Math.exp(log);
                for(int i=0;i<PPO.OUT;i++){
                    var plus=out.clone();var minus=out.clone();plus[i]+=1e-5;minus[i]-=1e-5;
                    double numerical=(distribution.probability(plus,ground,a,new double[PPO.OUT])-distribution.probability(minus,ground,a,new double[PPO.OUT]))/2e-5;
                    if(Math.abs(ds[i]-numerical)>1e-6)throw new AssertionError("Invalid action gradient");verified++;
                }
                double[] grad=new double[w.length];PPO.backward(w,x,h,ds,grad,1);
                for(int i=0;i<w.length;i+=13){
                    float[] plus=w.clone(),minus=w.clone();plus[i]+=.0003f;minus[i]-=.0003f;
                    double[] p=new double[PPO.OUT],m=new double[PPO.OUT];
                    PPO.forward(plus,x,new double[PPO.H],p);PPO.forward(minus,x,new double[PPO.H],m);
                    double derivative=(distribution.probability(p,ground,a,new double[PPO.OUT])-distribution.probability(m,ground,a,new double[PPO.OUT]))/(plus[i]-minus[i]);
                    if(Math.abs(derivative-grad[i])>5e-4)throw new AssertionError("Invalid actor weight gradient");verified++;
                }
            }
            if(Math.abs(sum-1)>1e-10)throw new AssertionError("Joint action mass "+sum);
        }
        System.out.println(verified+" finite-difference gradient comparisons and normalized joint action mass passed");
    }
}
