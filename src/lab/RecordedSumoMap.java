package lab;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** A translated fighting volume. Coordinates and directional block metadata retain the recorded orientation. */
public final class RecordedSumoMap {
    public final String name, clip, hash;
    public final int area, x0, z0, y0, width, depth, height, ax, az, bx, bz, gap;
    private final byte[] states;
    private static final Map<String,RecordedSumoMap> maps = new HashMap<>();

    public static synchronized RecordedSumoMap load(String name) {
        if (!name.matches("mmc-[0-9]{3}(-[0-9]{2})?")) throw new IllegalArgumentException("Unknown recorded sumo map " + name);
        if (maps.containsKey(name)) return maps.get(name);
        InputStream raw=RecordedSumoMap.class.getResourceAsStream("/lab/mmc-maps/"+name+".bin.gz");
        if (raw==null) throw new IllegalArgumentException("Unknown recorded sumo map "+name);
        try (DataInputStream in=new DataInputStream(new GZIPInputStream(raw))) {
            RecordedSumoMap map=new RecordedSumoMap(name,in);
            maps.put(name,map);
            return map;
        } catch(IOException ex) { throw new IllegalStateException("Invalid recorded sumo map "+name,ex); }
    }

    private RecordedSumoMap(String name,DataInputStream in) throws IOException {
        this.name=name;
        if(in.readInt()!=0x4d4d4331)throw new IOException("Unknown volume format");
        area=in.readInt(); x0=in.readInt(); z0=in.readInt(); y0=in.readInt();
        width=in.readInt(); depth=in.readInt(); height=in.readInt();
        ax=in.readInt(); az=in.readInt(); bx=in.readInt(); bz=in.readInt(); gap=in.readInt();
        if(width<=0||depth<=0||height!=7||width>21||depth>21||y0!=-2)throw new IOException("Invalid volume bounds");
        byte[] id=new byte[16],expected=new byte[32];in.readFully(id);in.readFully(expected);
        states=new byte[width*depth*height*2];in.readFully(states);
        clip=hex(id);hash=hex(expected);
        if(in.read()!=-1||!MessageDigest.isEqual(expected,digest().digest(states)))throw new IOException("Volume digest mismatch");
        if(Math.abs(ax-bx)+Math.abs(az-bz)!=gap || ax!=bx&&az!=bz || state(ax,0,az)!=656 || state(bx,0,bz)!=656)
            throw new IOException("Invalid gold spawns");
    }
    static MessageDigest digest(){
        try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    static String hex(byte[] bytes){
        StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    public int state(int x,int y,int z){
        int i=x-x0,j=y-y0,k=z-z0;
        if(i<0||i>=width||j<0||j>=height||k<0||k>=depth)return 0;
        int p=2*((j*depth+k)*width+i);
        return ((states[p]&255)<<8)|(states[p+1]&255);
    }
    public int yaw(boolean first){return ax==bx?(first?0:180):(first?-90:90);}
}
