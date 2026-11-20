package lab;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Place packet-derived states into a native World and optionally export Minecraft's collision boxes. */
final class RecordedSumoArena {
    private static final Map<Integer,RecordedSumoMap> placed=new HashMap<>();
    private static final Map<Integer,Object> states=new HashMap<>();
    private static final int EXPORT_ARENA=30;
    private static long exportTick;
    private static Object nativeState(int id){
        return states.computeIfAbsent(id,k->R.call(R.call(R.cls("net.minecraft.block.Block"),"getBlockById",k>>4),"getStateFromMeta",k&15));
    }
    static void clear(Object world,int arena){
        RecordedSumoMap previous=placed.get(arena);
        if(previous!=null){
            change(world,arena,previous,false);
            placed.remove(arena);
        }
    }
    static String build(Object world,int arena,RecordedSumoMap map){
        clear(world,arena);
        placed.put(arena,map);
        return change(world,arena,map,true);
    }
    private static Object pos(int arena,int x,int y,int z){
        return R.construct("net.minecraft.util.BlockPos",4096*(arena+1)+x,SumoArena.FLOOR_Y+y,z);
    }
    private static String change(Object world,int arena,RecordedSumoMap map,boolean build){
        // Every bottom-layer sand block in this build sits on recorded sandstone at Y-3.
        // Place it before the sand can receive its scheduled falling-block update.
        if(map.name.equals("mmc-177-03")){
            for(int z=map.z0;z<map.z0+map.depth;z++)for(int x=map.x0;x<map.x0+map.width;x++){
                if((map.state(x,map.y0,z)>>4)==12)
                    R.call(world,"setBlockState",pos(arena,x,map.y0-1,z),nativeState(build?24<<4:0),2);
            }
        }
        var hash=RecordedSumoMap.digest();
        for(int y=map.y0;y<map.y0+map.height;y++)for(int z=map.z0;z<map.z0+map.depth;z++)for(int x=map.x0;x<map.x0+map.width;x++){
            int id=build?map.state(x,y,z):0;
            var p=pos(arena,x,y,z);var expected=nativeState(id);var actual=R.call(world,"getBlockState",p);
            if(!expected.equals(actual)){
                R.call(world,"setBlockState",p,expected,2);
                actual=R.call(world,"getBlockState",p);
            }
            if(!expected.equals(actual))throw new IllegalStateException("Sumo volume placement failed for "+map.name+" at "+x+","+y+","+z);
            Object block=R.call(actual,"getBlock");
            int observed=((int)R.call(R.cls("net.minecraft.block.Block"),"getIdFromBlock",block)<<4)|(int)R.call(block,"getMetaFromState",actual);
            hash.update((byte)(observed>>8));hash.update((byte)observed);
        }
        String result=RecordedSumoMap.hex(hash.digest());
        if(build&&!result.equals(map.hash))throw new IllegalStateException("Native block-state digest differs for "+map.name);
        return result;
    }
    static void prepareExport(Object world,RecordedSumoMap map)throws IOException{
        build(world,EXPORT_ARENA,map);
        exportTick=(long)R.call(world,"getTotalWorldTime");
        Path dir=R.ROOT.resolve("runtime/map-geometry");Files.createDirectories(dir);
        Files.writeString(dir.resolve(map.name+".ready"),Long.toString(exportTick));
    }
    static void export(Object world,RecordedSumoMap map) throws IOException {
        int arena=EXPORT_ARENA,center=4096*(arena+1);
        if(placed.get(arena)!=map||((long)R.call(world,"getTotalWorldTime"))-exportTick<20)
            throw new IllegalStateException("Stable geometry export requires at least twenty native ticks after placement");
        Path dir=R.ROOT.resolve("runtime/map-geometry");
        Files.createDirectories(dir);Path tmp=Files.createTempFile(dir,map.name,".tmp");
        try{
            Object mask=R.construct("net.minecraft.util.AxisAlignedBB",(double)center-16,5.,-16.,(double)center+16,20.,16.);
            try(var file=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))){
                file.writeUTF(map.hash);
                for(int y=map.y0;y<map.y0+map.height;y++)for(int z=map.z0;z<map.z0+map.depth;z++)for(int x=map.x0;x<map.x0+map.width;x++){
                    Object p=pos(arena,x,y,z);Object state=R.call(world,"getBlockState",p);Object block=R.call(state,"getBlock");
                    if(!nativeState(map.state(x,y,z)).equals(state))
                        throw new IllegalStateException("Terrain changed after placement: "+map.name+" at "+x+","+y+","+z);
                    List<Object> boxes=new ArrayList<>();R.call(block,"addCollisionBoxesToList",world,p,state,mask,boxes,null);
                    file.writeFloat((float)R.num(block,"slipperiness"));file.writeInt(boxes.size());
                    for(Object box:boxes)for(String key:List.of("minX","minY","minZ","maxX","maxY","maxZ")){
                        double origin=key.endsWith("X")?center:key.endsWith("Y")?SumoArena.FLOOR_Y:0;
                        file.writeDouble(R.num(box,key)-origin);
                    }
                }
            }
            Path out=dir.resolve(map.name+".boxes");
            if(Files.exists(out) && Files.mismatch(tmp,out)!=-1)throw new IllegalStateException("Repeated native geometry export differs: "+map.name);
            Files.move(tmp,out,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(tmp);clear(world,arena);}
    }
}
