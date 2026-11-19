package lab;

import java.util.Map;

/** Ordinary solid blocks and a fall detector; never changes native combat or velocity. */
final class SumoArena {
    // The default is the most frequent complete footprint in the recorded MMC survey.
    static final int DIAMETER=SumoFootprint.DEFAULT, MAX_DIAMETER=16, FLOOR_Y=11;
    static final double TOP_Y=FLOOR_Y+1, START_GAP=8, LOSS_Y=TOP_Y-.5;
    static final long COUNTDOWN_MS=2000, DURATION_MS=120_000;
    static boolean cell(int x,int z){return cell(x,z,DIAMETER);}
    static boolean cell(int x,int z,int diameter){return SumoFootprint.cell(x,z,diameter);}
    static boolean supports(int arena,double x,double z){return supports(arena,x,z,DIAMETER);}
    static boolean supports(int arena,double x,double z,int diameter){
        // A native player can stand with part of its 0.6-wide box over an edge.
        double localX=x-4096.0*(arena+1);
        for(int bx=(int)Math.floor(localX-.3);bx<localX+.3;bx++)
            for(int bz=(int)Math.floor(z-.3);bz<z+.3;bz++)
                if(cell(bx,bz,diameter))return true;
        return false;
    }
    static boolean fallen(double y){return y<LOSS_Y;}
    /** -1: neither; 0: both fell in this server tick; 1/2: winning side. */
    static int outcome(double aY,double bY){boolean a=fallen(aY),b=fallen(bY);return !a&&!b?-1:a&&b?0:a?2:1;}
    static Map<String,Object> geometry(){return geometry(DIAMETER,START_GAP);}
    static Map<String,Object> geometry(int diameter,double gap){
        var g=new java.util.LinkedHashMap<String,Object>();
        g.put("shape",diameter==DIAMETER?SumoFootprint.SHAPE:"cell_center_disk_v1");g.put("diameter",diameter);
        g.put("floor_y",FLOOR_Y);g.put("top_y",TOP_Y);g.put("loss_y",LOSS_Y);g.put("start_gap",gap);
        g.put("reference",diameter==DIAMETER?"mmc_capture_"+SumoFootprint.CLIP:"provisional_not_verified_server_map");
        if(diameter==DIAMETER){g.put("center_offset",.5);g.put("floor_blocks",137);}
        return g;
    }
    static void build(Object world,int arena){build(world,arena,DIAMETER);}
    static String build(Object world,int arena,int diameter){return change(world,arena,diameter,true);}
    static void clear(Object world,int arena){change(world,arena,MAX_DIAMETER,false);}
    private static String change(Object world,int arena,int diameter,boolean build){
        int center=4096*(arena+1),r=MAX_DIAMETER/2;
        Object blocks=R.cls("net.minecraft.init.Blocks");var states=new java.util.HashMap<Integer,Object>();
        for(int state:new int[]{0,7<<4,16,98<<4,(98<<4)|1,41<<4}){
            String name=switch(state>>4){case 0->"air";case 7->"bedrock";case 1->"stone";case 98->"stonebrick";case 41->"gold_block";default->throw new AssertionError();};
            states.put(state,R.call(R.get(blocks,name),"getStateFromMeta",state&15));
        }
        for(int cx=(center-r)>>4;cx<=(center+r-1)>>4;cx++)for(int cz=-r>>4;cz<=(r-1)>>4;cz++)
            R.call(world,"getChunkFromChunkCoords",cx,cz);
        var nativeIds=new java.util.HashMap<Object,Integer>();
        states.forEach((id,state)->nativeIds.put(state,id));
        java.security.MessageDigest hash;
        try{hash=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
        for(int z=-r;z<r;z++)for(int x=-r;x<r;x++){
            Object pos=R.construct("net.minecraft.util.BlockPos",center+x,FLOOR_Y,z);
            Object expected=states.get(build?SumoFootprint.state(x,z,diameter):0);
            Object actual=R.call(world,"getBlockState",pos);
            if(!expected.equals(actual)){
                R.call(world,"setBlockState",pos,expected,2);
                actual=R.call(world,"getBlockState",pos);
            }
            if(!expected.equals(actual))throw new IllegalStateException("Sumo block state differs at "+x+","+z);
            int id=nativeIds.get(actual);hash.update((byte)(id>>8));hash.update((byte)id);
        }
        return java.util.HexFormat.of().formatHex(hash.digest());
    }
}
