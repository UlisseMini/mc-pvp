import java.io.*;
import lab.ExactTravel;

/** Exercise the simulator's actual movement and arena collision path on native snapshots
 * and on a continuous trajectory seeded once per POV. Inputs never contain later corrections. */
public final class NativeTravel {
    static double d(String[] p,int i){return Double.parseDouble(p[i]);}
    static boolean b(String[] p,int i){return Boolean.parseBoolean(p[i]);}
    static ExactTravel.State state(String[] p,int i){
        return new ExactTravel.State(d(p,i),d(p,i+1),d(p,i+2),d(p,i+3),d(p,i+4),d(p,i+5),
            new ExactTravel.Box(d(p,i+6),d(p,i+7),d(p,i+8),d(p,i+9),d(p,i+10),d(p,i+11)),
            b(p,i+12),b(p,i+13),b(p,i+14),b(p,i+15),false,false,false);
    }
    public static void main(String[] args)throws Exception{
        var input=new BufferedReader(new InputStreamReader(System.in));String line;
        var config=BoxingModel.config("mmc",0);config.sumo=true;
        var body=new PeriodicComboFightModel.Body(0);
        while((line=input.readLine())!=null){
            var p=line.split("\t");
            switch(p[0]){
                case "BEGIN" -> {config.sumoArena(d(p,1),12);config.solidEdge();body=new PeriodicComboFightModel.Body(0);
                    body.q=state(p,3);body.sprint=b(p,19);body.airAcceleration=Float.parseFloat(p[20]);}
                case "S12" -> body.velocity(d(p,1),d(p,2),d(p,3));
                case "CONTACT" -> {if(b(p,1)){body.velocity(body.q.vx()*.6,body.q.vy(),body.q.vz()*.6);body.sprint=false;}}
                case "MOVE" -> {
                    float yaw=Float.parseFloat(p[1]),strafe=Float.parseFloat(p[2]),forward=Float.parseFloat(p[3]);
                    var before=state(p,7);var expected=state(p,23);
                    var env=PeriodicComboFightModel.floor(config,before);
                    var step=ExactTravel.predict(before,new ExactTravel.Input(strafe,forward,yaw,
                        Float.parseFloat(p[4]),Float.parseFloat(p[5])),env);
                    body.move(forward==0?0:2,strafe,yaw,b(p,6),PeriodicComboFightModel.floor(config,body.q));
                    System.out.println((step.equals(expected)?1:0)+" "+(body.q.equals(expected)?1:0)+" "+
                        (PeriodicComboFightModel.sumoSupport(before.box(),10,config.centerX)?1:0)+" "+
                        (body.q.x()-expected.x())+" "+(body.q.y()-expected.y()));
                }
                default -> throw new IOException("Unknown travel input");
            }
        }
    }
}
