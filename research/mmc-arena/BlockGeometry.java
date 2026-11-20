import java.io.*;
import java.nio.file.*;
import java.util.*;
import lab.RecordedSumoMap;

/** Run the stock 1.8.9 block methods against recorded neighbors without starting a match or a server. */
class BlockGeometry extends adm {
    final RecordedSumoMap map;
    final Map<Integer,alz> chunkStates=new HashMap<>();
    final amz section=new amz(0,true);
    static final int X=4096,Y=11;
    BlockGeometry(RecordedSumoMap map){super(null,null,new ano(),new nt(),false);this.map=map;}
    @Override protected boolean a(int x,int z,boolean allowEmpty){return true;}
    @Override public List<pk> b(pk who,aug area){return Collections.emptyList();}
    @Override protected amv k(){throw new UnsupportedOperationException("World generation");}
    @Override protected int q(){throw new UnsupportedOperationException("Chunk lighting");}
    @Override public alz p(cj pos){
        int state=map.state(pos.n()-X,pos.o()-Y,pos.p());
        return chunkStates.computeIfAbsent(state,id->{
            section.a(0,0,0,afh.c(id>>4).a(id&15));
            return section.a(0,0,0);
        });
    }
    void export(Path output)throws IOException{
        aug mask=new aug(X-16,5,-16,X+16,20,16);
        try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))){
            out.writeUTF(map.hash);
            for(int y=map.y0;y<map.y0+map.height;y++)for(int z=map.z0;z<map.z0+map.depth;z++)for(int x=map.x0;x<map.x0+map.width;x++){
                cj pos=new cj(X+x,Y+y,z);alz state=p(pos);afh block=state.c();
                int id=(afh.a(block)<<4)|block.c(state);
                if(id!=map.state(x,y,z))throw new IOException("Block metadata does not round-trip at "+x+","+y+","+z);
                List<aug> boxes=new ArrayList<>();block.a(this,pos,state,mask,boxes,null);
                out.writeFloat(block.L);out.writeInt(boxes.size());
                for(aug b:boxes)for(double c:new double[]{b.a-X,b.b-Y,b.c,b.d-X,b.e-Y,b.f})out.writeDouble(c);
            }
        }
    }
    public static void main(String[] args)throws Exception{
        kb.c(); // Bootstrap.register()
        for(int i=1;i<args.length;i++){
            var map=RecordedSumoMap.load(args[i]);
            new BlockGeometry(map).export(Path.of(args[0]).resolve(map.name+".boxes"));
        }
    }
}
