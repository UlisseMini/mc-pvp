import java.io.*;
import java.util.*;
import lab.DuelNetwork;

/** Reconstruct every live decision including recurrent memory from the client observation stream. */
final class NetworkChecks {
    public static void main(String[] args)throws Exception{
        if(args.length!=0&&args[0].equals("replay")){
            BufferedReader reader=new BufferedReader(new InputStreamReader(System.in));
            String line;DuelNetwork n=null;int count=0;
            while((line=reader.readLine())!=null){
                if(line.startsWith("@")){n=DuelNetwork.decode(line.substring(1));continue;}
                if(line.startsWith("#")){n.league().seed(Long.parseLong(line.substring(1)));continue;}
                String[] s=line.split(" ");
                boolean hud=n.family()==DuelNetwork.Family.PING_MLP||n.league()!=null;
                boolean time=DuelNetwork.study(n.family())||n.league()!=null;
                int timer=hud?15:14,inputs=timer+(time?2:0);double[] v=new double[inputs];
                for(int i=0;i<v.length;i++)v[i]=Double.parseDouble(s[i]);
                if(time)n.localTiming(v[timer],v[timer+1]<0?Double.POSITIVE_INFINITY:v[timer+1]);
                int got=n.step(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9]!=0,v[10]!=0,
                    v[11]<0?Double.POSITIVE_INFINITY:v[11],v[12]<0?Double.POSITIVE_INFINITY:v[12],v[13],hud?v[14]:Double.NaN);
                if(got!=Integer.parseInt(s[inputs]))throw new AssertionError("Replayed decision differs at row "+count);
                if(s.length>inputs+2 && (n.pair()==null || n.pair().selected()!=Integer.parseInt(s[16])
                        || n.pair().switches()!=Integer.parseInt(s[17]) || Math.abs(n.pair().score()-Double.parseDouble(s[18]))>1e-7))
                    throw new AssertionError("Selected expert differs at row "+count);
                n.applied(Integer.parseInt(s[inputs+1]));count++;
            }
            System.out.println(count);return;
        }
        int checks=0;
        for(int k=0;k<4;k++)for(int s=-1;s<2;s++)for(boolean c:new boolean[]{true,false})for(boolean j:new boolean[]{true,false}){
            int a=DuelNetwork.pack(k,s,c,j);
            if(k!=DuelNetwork.key(a)||s!=DuelNetwork.side(a)||c!=DuelNetwork.click(a)||j!=DuelNetwork.jump(a))throw new AssertionError();
            checks++;
        }
        Random rng=new Random(301);
        for(var family:DuelNetwork.Family.values()){
            float[] w=new float[DuelNetwork.size(family)];for(int i=0;i<w.length;i++)w[i]=(float)rng.nextGaussian();
            if(DuelNetwork.study(family))w[w.length-1]=lab.SumoInputs.MOTION|lab.SumoInputs.TIMING;
            if(family==DuelNetwork.Family.LEAGUE_ADAPT24)w[w.length-1]=0;
            var n=new DuelNetwork(family,w);var m=DuelNetwork.decode(DuelNetwork.encode(family,w));DuelNetwork fork=null;
            for(int i=0;i<100;i++){
                if(i==35)fork=n.copy();
                double pressAge=i==0?Double.POSITIVE_INFINITY:(i%4)*37.5;
                n.localTiming(i*50.0,pressAge);
                m.localTiming(i*50.0,pressAge);
                int a=n.step(3+i*.125,1,2,4,1+i%2,2,.375,.0625,0,i%3==0,true,i*10,650,80);
                // Reverse spawn side and translate to a different arena; retain egocentric inputs.
                int b=m.step(4096-3-i*.125,12,-2,4096-4,12+i%2,-2,-.375,.0625,0,i%3==0,true,i*10,650,80);
                if(a!=b)throw new AssertionError("Egocentric action changed by seat");
                double[] x=n.features(),y=m.features();
                for(int z=0;z<x.length;z++)if(x[z]!=y[z])throw new AssertionError("Egocentric feature "+z+" differs: "+x[z]+" / "+y[z]);
                n.applied(a);m.applied(b);
                if(fork!=null){
                    fork.localTiming(i*50.0,pressAge);
                    int f=fork.step(3+i*.125,1,2,4,1+i%2,2,.375,.0625,0,i%3==0,true,i*10,650,80);
                    if(f!=a)throw new AssertionError("Unfaithful policy fork");
                    fork.applied(f);
                }
                checks++;
            }
        }
        var body=new PeriodicComboFightModel.Body(0);body.sprint=true;
        body.move(3,0,0,false);if(body.q.z()>=0||body.sprint||body.previousForward)throw new AssertionError("S did not walk backwards");
        var q=body.q;
        body.q=new lab.ExactTravel.State(q.x(),q.y(),q.z(),q.vx(),q.vy(),q.vz(),q.box(),false,true,false,true,false,false,false);
        body.sprint=true;body.move(2,0,0,false);
        if(body.sprint||body.airAcceleration!=.02f)throw new AssertionError("Previous horizontal collision must cancel sprint even with Ctrl pressed");
        System.out.println(checks+" action, serialization, translation, seat reversal and policy fork checks plus backward input passed");
    }
}
