package sword;

import java.nio.file.*;
import java.util.*;
import static sword.Fighter.*;

/** One-step travel check using recorded local position, velocity, orientation and actual keys.
 * Also replay both clients from their opening positions, generating all subsequent motion and hits. */
public final class Parity {
    record Row(double x,double y,double z,double vx,double vy,double vz,boolean ground,boolean sprint,double charge,double hp,Keys keys,double yaw){}
    static List<Row> readTrace(Path file)throws Exception{
        var rows=new ArrayList<Row>();for(var s:Files.readAllLines(file).stream().skip(1).toList()){
            var c=s.split("\t");double[] n=new double[14];for(int i:new int[]{1,2,3,7,8,9,12,13})n[i]=Double.parseDouble(c[i]);
            var k=new Keys(Integer.parseInt(c[16]),Integer.parseInt(c[17]),Boolean.parseBoolean(c[18]),Boolean.parseBoolean(c[19]),Boolean.parseBoolean(c[20]),0);
            rows.add(new Row(n[1],n[2]-101,n[3],n[7],n[8],n[9],Boolean.parseBoolean(c[10]),Boolean.parseBoolean(c[11]),n[12],n[13],k,Double.parseDouble(c[21])));
        }return rows;
    }
    static void load(Model.Body b,Row r){b.x=r.x;b.y=r.y;b.z=r.z;b.vx=r.vx;b.vy=r.vy;b.vz=r.vz;b.ground=r.ground;b.sprint=r.sprint;b.serverSprint=r.sprint;b.age=(int)Math.round(12.5*r.charge);b.hp=r.hp;b.keys=r.keys;b.yaw=r.yaw;}
    static double error(Model.Body b,Row r){return Math.sqrt(Math.pow(b.x-r.x,2)+Math.pow(b.y-r.y,2)+Math.pow(b.z-r.z,2));}
    public static void main(String[] args)throws Exception{
        var left=readTrace(Path.of(args[0]));var right=readTrace(Path.of(args[1]));int lag=Integer.parseInt(args[2]);
        var errors=new ArrayList<Double>();var free=new ArrayList<Double>();var random=new Random(12345);
        var a=new Model.Body();var b=new Model.Body();load(a,left.getFirst());load(b,right.getFirst());
        for(int i=0;i<12;i++){a.snapshot(i);b.snapshot(i);}
        int firstDivergence=-1,firstModel=-1,firstNative=-1;
        int sprintTrials=0,sprintErrors=0;
        for(int t=0;t+1<Math.min(left.size(),right.size());t++){
            var ra=left.get(t);var rb=right.get(t);
            for(var side:List.of(left,right)){
                var local=new Model.Body();var row=side.get(t);load(local,row);Model.move(local,t);
                var next=side.get(t+1);
                errors.add(error(local,next));
                if(row.sprint&&row.keys.forward()>0&&row.keys.click()&&row.charge>=.9){
                    sprintTrials++;
                    if(local.sprint!=next.sprint)sprintErrors++;
                }
            }
            var va=Model.view(a,b,t,2*lag+1+Model.EXTRA_DISPLAY,lag);var vb=Model.view(b,a,t,2*lag+1+Model.EXTRA_DISPLAY,lag);
            // Replay the logged look direction together with the physical controls.
            a.yaw=ra.yaw;b.yaw=rb.yaw;
            var ka=withYaw(ra,va);var kb=withYaw(rb,vb);
            Model.input(a,va,ka,t,lag);Model.input(b,vb,kb,t,lag);
            if(random.nextBoolean()){Model.hit(a,b,t,lag);if(b.hp>0)Model.hit(b,a,t,lag);}
            else{Model.hit(b,a,t,lag);if(a.hp>0)Model.hit(a,b,t,lag);}
            Model.move(a,t);Model.move(b,t);a.snapshot(t+1);b.snapshot(t+1);
            double e=Math.max(error(a,left.get(t+1)),error(b,right.get(t+1)));free.add(e);
            if(firstDivergence<0&&e>.25)firstDivergence=t+1;
            if(firstModel<0&&(a.hp<20||b.hp<20))firstModel=t;
            if(firstNative<0&&(ra.hp<20||rb.hp<20))firstNative=t;
        }
        Collections.sort(errors);Collections.sort(free);
        System.out.printf(Locale.ROOT,"steps=%d travel_median=%.5f travel_p95=%.5f replay_median=%.3f first_quarter_block=%d first_model_damage=%d first_observed_damage=%d sprint_trials=%d sprint_errors=%d%n",
            errors.size(),errors.get(errors.size()/2),errors.get((int)(.95*errors.size())),free.get(free.size()/2),firstDivergence,firstModel,firstNative,sprintTrials,sprintErrors);
    }
    static Keys withYaw(Row r,View v){var k=r.keys;return new Keys(k.forward(),k.side(),k.sprint(),k.jump(),k.click(),angle(r.yaw-targetYaw(v)));}
}
