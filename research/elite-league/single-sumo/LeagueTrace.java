import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import lab.DuelNetwork;

/** Evaluate recorded local observations with the same realized control history in both Java runtimes. */
public final class LeagueTrace {
    public static void main(String[] args)throws Exception {
        String weights=new String(Files.readAllBytes(Paths.get(args[0])),"US-ASCII").trim();
        MessageDigest hash=MessageDigest.getInstance("SHA-256"); int ticks=0;
        DuelNetwork n=null;
        try(BufferedReader r=Files.newBufferedReader(Paths.get(args[1]))){
            String line;
            while((line=r.readLine())!=null){
                if(line.startsWith("#")){
                    n=DuelNetwork.decode(weights);n.league().seed(Long.parseLong(line.substring(1)));continue;
                }
                String[] s=line.split("\t");
                double[] v=new double[s.length];for(int i=0;i<v.length;i++)v[i]=Double.parseDouble(s[i]);
                n.localTiming(v[15],v[16]);
                int a=n.step(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9]>0,v[10]>0,
                         v[11],v[12],v[13],v[14]);
                hash.update((byte)a);
                for(double x:n.features()){
                    long bits=Double.doubleToLongBits(x);
                    for(int i=0;i<8;i++)hash.update((byte)(bits>>>(8*i)));
                }
                n.applied((int)v[17]);ticks++;
            }
        }
        StringBuilder s=new StringBuilder();
        for(byte v:hash.digest())s.append(String.format("%02x",v&255));
        System.out.println(ticks+" "+s);
    }
}
