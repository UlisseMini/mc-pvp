package lab;

import java.util.Map;

/** Ordinary solid blocks and a fall detector; never changes native combat or velocity. */
final class SumoArena {
    // Provisional Hypixel-style footprint, not a verified MMC/Hypixel map copy.
    static final int DIAMETER=10, FLOOR_Y=11;
    static final double TOP_Y=FLOOR_Y+1, START_GAP=6, LOSS_Y=TOP_Y-.5;
    static final long COUNTDOWN_MS=2000, DURATION_MS=120_000;
    static boolean cell(int x,int z){return Math.pow(x+.5,2)+Math.pow(z+.5,2)<=DIAMETER*DIAMETER/4.0;}
    static boolean supports(int arena,double x,double z){
        // A native player can stand with part of its 0.6-wide box over an edge.
        double localX=x-4096.0*(arena+1);
        for(int bx=(int)Math.floor(localX-.3);bx<localX+.3;bx++)
            for(int bz=(int)Math.floor(z-.3);bz<z+.3;bz++)
                if(cell(bx,bz))return true;
        return false;
    }
    static boolean fallen(double y){return y<LOSS_Y;}
    /** -1: neither; 0: both fell in this server tick; 1/2: winning side. */
    static int outcome(double aY,double bY){boolean a=fallen(aY),b=fallen(bY);return !a&&!b?-1:a&&b?0:a?2:1;}
    static Map<String,Object> geometry(){return Map.of("shape","cell_center_disk_v1","diameter",DIAMETER,
        "floor_y",FLOOR_Y,"top_y",TOP_Y,"loss_y",LOSS_Y,"start_gap",START_GAP,"reference","provisional_not_verified_server_map");}
    static void build(Object world,int arena){change(world,arena,true);}
    static void clear(Object world,int arena){change(world,arena,false);}
    private static void change(Object world,int arena,boolean build){
        int center=4096*(arena+1),r=DIAMETER/2;
        // Vanilla /fill also checks its positive border chunk.
        for(int x=(center-r)>>4;x<=(center+r+16)>>4;x++)for(int z=(-r)>>4;z<=(r+16)>>4;z++)
            R.call(world,"getChunkFromChunkCoords",x,z);
        Referee.vanilla("fill "+(center-r)+" "+FLOOR_Y+" "+(-r)+" "+(center+r-1)+" "+FLOOR_Y+" "+(r-1)+" minecraft:air");
        if(build)for(int z=-r;z<r;z++){
            int lo=-r,hi=r-1;while(lo<=hi&&!cell(lo,z))lo++;while(hi>=lo&&!cell(hi,z))hi--;
            if(lo<=hi)Referee.vanilla("fill "+(center+lo)+" "+FLOOR_Y+" "+z+" "+(center+hi)+" "+FLOOR_Y+" "+z+" minecraft:bedrock");
        }
        for(int x=-r;x<r;x++)for(int z=-r;z<r;z++){
            Object expected=R.get(R.cls("net.minecraft.init.Blocks"),build&&cell(x,z)?"bedrock":"air");
            if(R.call(R.call(world,"getBlockState",R.construct("net.minecraft.util.BlockPos",center+x,FLOOR_Y,z)),"getBlock")!=expected)
                throw new IllegalStateException("Sumo platform verification failed at "+x+","+z);
        }
    }
}
