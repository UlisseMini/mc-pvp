package lab;

import java.io.*;
import java.util.*;

/** Offline differential-test bridge into the SAME physics functions used by the planner. */
public final class NativeModelCheck {
    static double d(String[] f,int n){return Double.parseDouble(f[n]);}
    static ComboModel.Body body(String[] f,int start){return new ComboModel.Body(d(f,start),d(f,start+1),d(f,start+2),d(f,start+3),d(f,start+4),d(f,start+5),d(f,start+6)!=0,d(f,start+7)!=0);}
    public static void main(String[] args)throws Exception {
        BufferedReader reader=new BufferedReader(new InputStreamReader(System.in));String line;
        while((line=reader.readLine())!=null){
            String[] f=line.split("\\t");double[] answer;
            if(f[0].equals("move")){
                ComboModel.Body b=body(f,1);b.groundAcceleration=d(f,9);b.airAcceleration=d(f,10);
                double yaw=Math.toRadians(d(f,11)),strafe=d(f,12),forward=d(f,13),norm=Math.max(1,Math.hypot(strafe,forward));
                double ix=(strafe*Math.cos(yaw)-forward*Math.sin(yaw))/norm/.98;
                double iz=(forward*Math.cos(yaw)+strafe*Math.sin(yaw))/norm/.98;
                b=ComboModel.predictTravel(b,ix,iz);answer=new double[]{b.x,b.y,b.z,b.vx,b.vy,b.vz,b.ground?1:0};
            }else if(f[0].equals("ray")){
                answer=new double[]{ComboModel.rayDistance(d(f,1),d(f,2),d(f,3),d(f,4),d(f,5),d(f,6))};
            }else if(f[0].equals("ray-look")){
                answer=new double[]{ComboModel.rayDistanceAlong(d(f,1),d(f,2),d(f,3),d(f,4),d(f,5),d(f,6),d(f,7),d(f,8),d(f,9))};
            }else if(f[0].equals("kb")){
                ComboModel.Body a=body(f,1),b=body(f,9);
                ComboModel.View va=new ComboModel.View(a.x,a.y,a.z),vb=new ComboModel.View(b.x,b.y,b.z);
                ComboModel.State s=new ComboModel.State(0,a,b,vb,va,va,vb);s.serverMotionB=b;
                double yaw=Math.toRadians(d(f,17));s.serverA.lookX=-Math.sin(yaw);s.serverA.lookZ=Math.cos(yaw);
                answer=ComboModel.predictedKnockback(s,a.sprint,new ComboModel.Config());
            }else throw new IllegalArgumentException("Unknown native check "+f[0]);
            StringJoiner result=new StringJoiner("\t");for(double value:answer)result.add(Double.toString(value));System.out.println(result);
        }
    }
}
