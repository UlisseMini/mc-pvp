import java.io.*;
import java.nio.file.*;
import java.util.*;
import lab.ExactTravel;
import lab.RecordedSumoMap;

/** Absolute native collision boxes indexed by owning voxel. Actor observations are unchanged. */
final class RecordedMapClock implements ExactTravel.Terrain {
    static final int CENTER=4096,FLOOR=11;
    private static final Map<String,RecordedMapClock> terrains=new HashMap<>();
    final RecordedSumoMap map;
    final ExactTravel.Box[][] cells;
    final float[] friction;
    private RecordedMapClock(String name)throws IOException{
        map=RecordedSumoMap.load(name);
        cells=new ExactTravel.Box[map.width*map.depth*map.height][];friction=new float[cells.length];
        try(InputStream raw=RecordedMapClock.class.getResourceAsStream("/lab/mmc-maps/"+name+".boxes");
            DataInputStream in=new DataInputStream(new BufferedInputStream(Objects.requireNonNull(raw,"Missing native collision proof for "+name)))){
            if(!in.readUTF().equals(map.hash))throw new IOException("Collision proof has different source states");
            for(int y=map.y0;y<map.y0+map.height;y++)for(int z=map.z0;z<map.z0+map.depth;z++)for(int x=map.x0;x<map.x0+map.width;x++){
                int i=index(x,y,z);friction[i]=in.readFloat();int count=in.readInt();
                if(count<0||count>32)throw new IOException("Invalid collision count for "+name);
                cells[i]=new ExactTravel.Box[count];
                for(int j=0;j<count;j++)cells[i][j]=new ExactTravel.Box(in.readDouble()+CENTER,in.readDouble()+FLOOR,in.readDouble(),
                    in.readDouble()+CENTER,in.readDouble()+FLOOR,in.readDouble());
                // World collision boxes cover geometry. Reject additional movement callbacks until modeled.
                int id=map.state(x,y,z)>>4;
                if(y>=-1 && Set.of(8,9,10,11,30,65,88,106,165).contains(id))
                    throw new IOException("Special movement block "+id+" on "+name+" at "+x+","+y+","+z);
            }
            if(in.read()!=-1)throw new IOException("Trailing collision data");
        }
    }
    int index(int x,int y,int z){
        x-=map.x0;y-=map.y0;z-=map.z0;
        return x<0||x>=map.width||y<0||y>=map.height||z<0||z>=map.depth?-1:(y*map.depth+z)*map.width+x;
    }
    public float slipperiness(ExactTravel.State s){
        int i=index((int)Math.floor(s.x())-CENTER,(int)Math.floor(s.box().minY())-1-FLOOR,(int)Math.floor(s.z()));
        return i<0?.6f:friction[i];
    }
    public List<ExactTravel.Box> boxes(ExactTravel.Box query){
        var result=new ArrayList<ExactTravel.Box>();
        for(int x=Math.max(map.x0,(int)Math.floor(query.minX()-CENTER));x<=Math.min(map.x0+map.width-1,(int)Math.floor(query.maxX()-CENTER));x++)
            for(int z=Math.max(map.z0,(int)Math.floor(query.minZ()));z<=Math.min(map.z0+map.depth-1,(int)Math.floor(query.maxZ()));z++)
                for(int y=Math.max(map.y0,(int)Math.floor(query.minY()-FLOOR)-1);y<=Math.min(map.y0+map.height-1,(int)Math.floor(query.maxY()-FLOOR));y++)
                    for(var b:cells[index(x,y,z)])if(b.maxX()>query.minX()&&b.minX()<query.maxX()&&b.maxY()>query.minY()
                        &&b.minY()<query.maxY()&&b.maxZ()>query.minZ()&&b.minZ()<query.maxZ())result.add(b);
        return result;
    }
    public boolean supports(ExactTravel.Box b){
        return !boxes(new ExactTravel.Box(b.minX(),FLOOR,b.minZ(),b.maxX(),FLOOR+1,b.maxZ())).isEmpty();
    }
    static DuelEnv open(Path clock,String name)throws IOException{
        if(!terrains.containsKey(name))terrains.put(name,new RecordedMapClock(name));
        var terrain=terrains.get(name);var m=terrain.map;
        var env=new DuelEnv(DuelEnv.Tape.read(clock,DuelEnv.LIMIT),"mmc-range",0,0,true,29,true,
                Math.max(m.width,m.depth)-2,m.gap,0,DuelEnv.LIMIT,new double[]{m.ax+.5,m.az+.5,m.bx+.5,m.bz+.5,m.yaw(true),m.yaw(false)},terrain);
        env.initial.timing.mouseInput=true;return env;
    }
}
