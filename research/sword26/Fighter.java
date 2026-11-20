package sword;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Shared observation encoding, learned controller and steering for both runtimes. */
public final class Fighter {
    static final int INPUTS=23, HIDDEN=12, OUTPUTS=8;
    static final int COUNT=(INPUTS+2)*HIDDEN+(HIDDEN+1)*OUTPUTS;
    public record View(double x,double y,double z,double vx,double vy,double vz,
                       double tx,double ty,double tz,double tvx,double tvy,double tvz,
                       boolean grounded,boolean sprint,double charge,double health,
                       int hurt,int otherHurt,double yaw) {}
    public record Keys(int forward,int side,boolean sprint,boolean jump,boolean click,double offset) {
        static final Keys STILL=new Keys(0,0,false,false,false,0);
    }
    final double[] weights, hidden=new double[HIDDEN], input=new double[INPUTS], output=new double[OUTPUTS];
    Keys previous=Keys.STILL;
    public Fighter(double[] w){ weights=w; }
    static double clip(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    static double angle(double d){return Math.IEEEremainder(d,360);}
    static double targetYaw(View v){return Math.toDegrees(Math.atan2(v.tz-v.z,v.tx-v.x))-90;}
    static double steer(View v,Keys k){return v.yaw+clip(angle(targetYaw(v)+k.offset-v.yaw),-45,45);}
    void reset(){Arrays.fill(hidden,0);previous=Keys.STILL;}
    void observe(View v){
        double dx=v.tx-v.x,dz=v.tz-v.z,dist=Math.max(1e-6,Math.hypot(dx,dz));
        double nx=dx/dist,nz=dz/dist;
        input[0]=Math.min(2,dist/4); input[1]=(v.ty-v.y)/2;
        input[2]=(v.vx*nx+v.vz*nz)*3; input[3]=(v.vx*nz-v.vz*nx)*3; input[4]=v.vy*2;
        input[5]=(v.tvx*nx+v.tvz*nz)*3; input[6]=(v.tvx*nz-v.tvz*nx)*3;input[7]=v.tvy*2;
        input[8]=v.grounded?1:0;input[9]=v.sprint?1:0;input[10]=v.charge;
        input[11]=v.health/20;input[12]=v.hurt/10.;input[13]=v.otherHurt/10.;
        double bearing=Math.toRadians(angle(targetYaw(v)-v.yaw));
        input[14]=Math.sin(bearing);input[15]=Math.cos(bearing);
        input[16]=previous.forward;input[17]=previous.side;input[18]=previous.sprint?1:0;
        input[19]=previous.jump?1:0;input[20]=previous.click?1:0;
        input[21]=clip(v.x/12,-1,1);input[22]=clip(v.z/12,-1,1);
    }
    Keys act(View v){
        observe(v);int off=0;
        for(int j=0;j<HIDDEN;j++){
            double sum=weights[off++]+hidden[j]*weights[off++];
            for(double f:input)sum+=f*weights[off++];
            hidden[j]=Math.tanh(sum);
        }
        for(int j=0;j<OUTPUTS;j++){
            double sum=weights[off++];for(double f:hidden)sum+=f*weights[off++];
            output[j]=sum;
        }
        previous=new Keys(choice(output[0],output[1]),choice(output[2],output[3]),output[4]>0,
            output[5]>0,output[6]>0,180*clip(output[7],-1,1));
        return previous;
    }
    static int choice(double positive,double negative){return Math.max(positive,negative)<=0?0:positive>negative?1:-1;}
    static double[] read(Path p)throws IOException{
        try(var in=new DataInputStream(Files.newInputStream(p))){
            if(in.readInt()!=0x53573236||in.readInt()!=COUNT)throw new IOException("Incompatible sword policy");
            var w=new double[COUNT];for(int i=0;i<COUNT;i++){w[i]=in.readDouble();if(!Double.isFinite(w[i]))throw new IOException("Nonfinite weight");}
            if(in.read()!=-1)throw new IOException("Trailing weight data");return w;
        }
    }
    static void write(Path p,double[] w)throws IOException{
        try(var out=new DataOutputStream(Files.newOutputStream(p,StandardOpenOption.CREATE_NEW))){
            out.writeInt(0x53573236);out.writeInt(COUNT);for(double v:w)out.writeDouble(v);
        }
    }
}
