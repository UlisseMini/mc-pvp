package lab;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Place packet-derived states into a native World and optionally export Minecraft's collision boxes. */
final class RecordedSumoArena {
    private static final Map<Integer,RecordedSumoMap> placed=new HashMap<>();
    private static final Map<Integer,Object> states=new HashMap<>();
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
    static void export(Object world,RecordedSumoMap map) throws IOException {
        // Dedicated geometry-only slot, outside both the match and replay ranges.
        int arena=30,center=4096*(arena+1);
        Path dir=R.ROOT.resolve("runtime/map-geometry");
        Files.createDirectories(dir);Path tmp=Files.createTempFile(dir,map.name,".tmp");
        try{
            build(world,arena,map);
            Object mask=R.construct("net.minecraft.util.AxisAlignedBB",(double)center-16,5.,-16.,(double)center+16,20.,16.);
            try(var file=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))){
                file.writeUTF(map.hash);
                for(int y=map.y0;y<map.y0+map.height;y++)for(int z=map.z0;z<map.z0+map.depth;z++)for(int x=map.x0;x<map.x0+map.width;x++){
                    Object p=pos(arena,x,y,z);Object state=R.call(world,"getBlockState",p);Object block=R.call(state,"getBlock");
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
