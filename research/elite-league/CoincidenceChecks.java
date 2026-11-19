import java.io.*;
import java.util.*;
import lab.Json;

/** Compare fresh Hypixel-like packets with native fixtures, and the seeded overlap branch with java.util.Random. */
final class CoincidenceChecks {
    static void reference(){
        for(int seed=0;seed<512;seed++){
            var canonical=new Random(seed);var cfg=BoxingModel.config("hypixel",0);
            var s=new PeriodicComboFightModel.State(0,0,0,0);s.seedCoincidence(seed);
            var copy=s.deepCopy();PeriodicComboFightModel.hit(s,cfg,true,1);PeriodicComboFightModel.hit(copy,cfg,true,1);
            double x,z;
            do{
                x=(canonical.nextDouble()-canonical.nextDouble())*.01;
                z=(canonical.nextDouble()-canonical.nextDouble())*.01;
            }while(x*x+z*z<1.0e-4);
            double d=(float)Math.sqrt(x*x+z*z);var impulse=s.lastFreshB;
            if(s.coincidenceHits!=1||s.coincidenceRandom!=copy.coincidenceRandom||impulse.x()!=copy.lastFreshB.x()||
               impulse.z()!=copy.lastFreshB.z()||impulse.x()!=PeriodicComboFightModel.vel(-x/d*(double).4f)||
               impulse.z()!=PeriodicComboFightModel.vel(-z/d*(double).4f)||s.tieDouble()!=canonical.nextDouble())
                throw new AssertionError("Native random direction or fork state "+seed);
        }
    }
    public static void main(String[] args)throws Exception{
        reference();var cfg=BoxingModel.config("hypixel",0);cfg.sumo=true;
        var input=new BufferedReader(new InputStreamReader(System.in));String line;
        int ordinary=0,exact=0,ties=0,compatible=0;var failures=new ArrayList<Object>();var tied=new ArrayList<Object>();
        while((line=input.readLine())!=null){
            var v=line.split(" ");double[] n=Arrays.stream(v,2,v.length).mapToDouble(Double::parseDouble).toArray();
            double dx=n[0],dz=n[1];var s=new PeriodicComboFightModel.State(0,0,dx,dz);s.seedCoincidence(17);
            s.serverB=PeriodicComboFightModel.motion(s.serverB,n[2],n[3],n[4]);s.serverYawA=n[5];s.serverSprint=n[6]!=0;
            PeriodicComboFightModel.hit(s,cfg,true,1);var impulse=s.lastFreshB;
            if(impulse==null)throw new AssertionError("Fresh input yielded no impulse "+v[0]);
            if(s.coincidenceHits==0){
                ordinary++;
                if(impulse.x()==n[7]&&impulse.y()==n[8]&&impulse.z()==n[9])exact++;
                else if(failures.size()<12)failures.add(List.of(v[0],v[1],List.of(impulse.x(),impulse.y(),impulse.z()),List.of(n[7],n[8],n[9])));
            }else{
                ties++;float angle=(float)n[5]*(float)Math.PI/180f;double sprint=n[6]*.5f;
                double rx=n[7]-n[2]*.5+PeriodicComboFightModel.sin(angle)*sprint;
                double rz=n[9]-n[4]*.5-PeriodicComboFightModel.cos(angle)*sprint;
                // Each quantized axis is within 1/8000 of the continuous radial push.
                double radial=Math.hypot(rx,rz);
                boolean ok=Math.abs(radial-(double).4f)<=Math.sqrt(2)/8000.+1e-7 && impulse.y()==n[8];
                if(ok)compatible++;
                tied.add(List.of(v[0],v[1],Math.hypot(dx,dz),radial,impulse.y(),n[8],ok));
            }
        }
        System.out.println(Json.encode(Map.of("ordinary",ordinary,"exact",exact,"coincident",ties,"compatible",compatible,
            "observed_ties",tied,"ordinary_mismatches",failures,"java_random_and_fork_seeds",512)));
    }
}
