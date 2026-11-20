package lab;

import java.io.*;
import java.util.*;

/** Stock 1.8.9 collision boxes and friction, positioned at the absolute coordinates of any lab arena. */
public final class RecordedCollisions implements ExactTravel.Terrain {
    public final RecordedSumoMap map;
    private final int centerX, floorY;
    final ExactTravel.Box[][] cells;
    final float[] friction;
    public RecordedCollisions(String name,int center,int floor)throws IOException{
        centerX=center;floorY=floor;
        map=RecordedSumoMap.load(name);
        cells=new ExactTravel.Box[map.width*map.depth*map.height][];friction=new float[cells.length];
        try(InputStream raw=RecordedCollisions.class.getResourceAsStream("/lab/mmc-maps/"+name+".boxes");
            DataInputStream in=new DataInputStream(new BufferedInputStream(Objects.requireNonNull(raw,"Missing native collision proof for "+name)))){
            if(!in.readUTF().equals(map.hash))throw new IOException("Collision proof has different source states");
            for(int y=map.y0;y<map.y0+map.height;y++)for(int z=map.z0;z<map.z0+map.depth;z++)for(int x=map.x0;x<map.x0+map.width;x++){
                int i=index(x,y,z);friction[i]=in.readFloat();int count=in.readInt();
                if(count<0||count>32)throw new IOException("Invalid collision count for "+name);
                cells[i]=new ExactTravel.Box[count];
                for(int j=0;j<count;j++)cells[i][j]=new ExactTravel.Box(in.readDouble()+centerX,in.readDouble()+floorY,in.readDouble(),
                    in.readDouble()+centerX,in.readDouble()+floorY,in.readDouble());
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
        int i=index((int)Math.floor(s.x())-centerX,(int)Math.floor(s.box().minY())-1-floorY,(int)Math.floor(s.z()));
        return i<0?.6f:friction[i];
    }
    public List<ExactTravel.Box> boxes(ExactTravel.Box query){
        var result=new ArrayList<ExactTravel.Box>();
        for(int x=Math.max(map.x0,(int)Math.floor(query.minX()-centerX));x<=Math.min(map.x0+map.width-1,(int)Math.floor(query.maxX()-centerX));x++)
            for(int z=Math.max(map.z0,(int)Math.floor(query.minZ()));z<=Math.min(map.z0+map.depth-1,(int)Math.floor(query.maxZ()));z++)
                for(int y=Math.max(map.y0,(int)Math.floor(query.minY()-floorY)-1);y<=Math.min(map.y0+map.height-1,(int)Math.floor(query.maxY()-floorY));y++)
                    for(var b:cells[index(x,y,z)])if(b.maxX()>query.minX()&&b.minX()<query.maxX()&&b.maxY()>query.minY()
                        &&b.minY()<query.maxY()&&b.maxZ()>query.minZ()&&b.minZ()<query.maxZ())result.add(b);
        return result;
    }
    public boolean supports(ExactTravel.Box b){
        return !boxes(new ExactTravel.Box(b.minX(),floorY,b.minZ(),b.maxX(),floorY+1,b.maxZ())).isEmpty();
    }
}
