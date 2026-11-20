package lab;

/** Optional features derived from local decisions, the physical mouse and the familiar sumo floor. */
public final class SumoInputs {
    public static final int COUNT=34, BLIND=1, NO_SELF=2, NO_FOE=4, NO_HURT=8, MOTION=16, EDGE=32, TIMING=64;
    private final int mask;
    private double previousTime=Double.NaN,still,filteredX,filteredY,filteredZ;
    public SumoInputs(int mask){this.mask=mask;}
    SumoInputs copy(){
        SumoInputs c=new SumoInputs(mask);c.previousTime=previousTime;c.still=still;
        c.filteredX=filteredX;c.filteredY=filteredY;c.filteredZ=filteredZ;return c;
    }
    private static double soft(double v){return v/(1+Math.abs(v));}
    private static double clip(double v){return Math.max(-1,Math.min(1,v));}
    // Exact point-to-first-unsupported-cell distance along a horizontal ray, capped at four blocks.
    static double clearance(double x,double z,double vx,double vz){
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z);
        if(!SumoFootprint.cell(bx,bz,13))return 0;
        double length=Math.hypot(vx,vz);
        if(length<1e-7)return 4;
        double dx=vx/length,dz=vz/length;int sx=dx>0?1:-1,sz=dz>0?1:-1;
        double tx=dx==0?Double.POSITIVE_INFINITY:((dx>0?bx+1:bx)-x)/dx;
        double tz=dz==0?Double.POSITIVE_INFINITY:((dz>0?bz+1:bz)-z)/dz;
        while(Math.min(tx,tz)<4){
            double at=Math.min(tx,tz);
            if(tx==at){bx+=sx;tx+=1/Math.abs(dx);}
            if(tz==at){bz+=sz;tz+=1/Math.abs(dz);}
            if(!SumoFootprint.cell(bx,bz,13))return Math.max(0,at);
        }
        return 4;
    }
    void fill(double[] f,double x,double y,double z,double tx,double ty,double tz,
              double vx,double vy,double vz,double ox,double oy,double oz,double fx,double fz,
              double cx,double cz,double now,double clickAge){
        if((mask&BLIND)!=0)f[15]=0;
        if((mask&NO_SELF)!=0)f[2]=f[3]=f[4]=0;
        if((mask&NO_FOE)!=0)f[5]=f[6]=f[7]=0;
        if((mask&NO_HURT)!=0)f[10]=f[11]=f[21]=0;
        if((mask&MOTION)!=0){
            double dt=Double.isFinite(previousTime)?Math.max(1,now-previousTime):50;
            double a=-Math.expm1(-dt/100),scale=50/dt;
            filteredX+=a*(ox*scale-filteredX);filteredY+=a*(oy*scale-filteredY);filteredZ+=a*(oz*scale-filteredZ);
            still=(ox==0&&oy==0&&oz==0)?still+dt:0;
            f[22]=soft(2*(vx*fx+vz*fz));f[23]=soft(2*(vx*fz-vz*fx));f[24]=soft(2*vy);
            f[25]=soft(3*(filteredX*fx+filteredZ*fz));f[26]=soft(3*(filteredX*fz-filteredZ*fx));
            f[27]=soft(3*filteredY);f[28]=clip(still/100-1);previousTime=now;
        }
        if((mask&EDGE)!=0){
            // The runner opens on the gold spawns. Reconstruct the grid about their visible midpoint.
            double ax=x-cx+.5,az=z-cz+.5,bx=tx-cx+.5,bz=tz-cz+.5;
            f[29]=clearance(ax,az,vx,vz)/2-1;f[30]=clearance(bx,bz,ox,oz)/2-1;
            f[31]=clearance(bx,bz,fx,fz)/2-1;
        }
        if((mask&TIMING)!=0){
            double nx=Math.max(0,Math.abs(tx-x)-.4),nz=Math.max(0,Math.abs(tz-z)-.4);
            double eye=y+1.62,ny=Math.max(0,Math.max(ty-.1-eye,eye-ty-1.9));
            f[32]=clip(3-Math.sqrt(nx*nx+ny*ny+nz*nz));f[33]=clip(clickAge/50-1);
        }
    }
}
