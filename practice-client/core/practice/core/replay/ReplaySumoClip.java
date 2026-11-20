package practice.core.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import static practice.core.replay.ReplayRecording.Cursor;

/** Keeps the visible match separate from the packets needed to prepare its arena. */
public final class ReplaySumoClip {
    public final double startMs, finishMs, countdownMs;
    public final boolean capturedCountdown, capturedResult, hasArenaTerrain;
    public final List<ReplayRecording.Wire> initialChunks;
    public final List<ReplayRecording.Wire> earlierChunks;

    private ReplaySumoClip(double start,double finish,double countdown,boolean hasCountdown,boolean hasResult,
                           List<ReplayRecording.Wire> chunks,List<ReplayRecording.Wire> earlier,boolean hasTerrain){
        startMs=start;finishMs=finish;countdownMs=countdown;capturedCountdown=hasCountdown;capturedResult=hasResult;
        initialChunks=Collections.unmodifiableList(chunks);
        earlierChunks=Collections.unmodifiableList(earlier);
        hasArenaTerrain=hasTerrain;
    }

    static ReplaySumoClip read(ReplayRecording.Entry entry,long origin,double duration,List<ReplayRecording.Wire> packets,
                               List<ReplayRecording.Pose> poses,Function<String,Map<String,Object>> parser){
        if(!entry.isSumo())return null;
        double expectedStart=Math.max(0,entry.preferredStartMs-origin),expectedFinish=Math.max(expectedStart,entry.finishMs-origin);
        double countdown=Double.NaN,result=Double.NaN;
        for(ReplayRecording.Wire packet:packets){
            if(!packet.inbound||packet.protocolId!=2)continue;
            boolean atStart=Math.abs(packet.wireAtMs-expectedStart)<=2500;
            boolean atEnd=Math.abs(packet.wireAtMs-expectedFinish)<=750;
            if(!atStart&&!atEnd)continue;
            String text=chat(packet,parser);
            if(atStart&&text.equals("5...")&&(!Double.isFinite(countdown)
                    ||Math.abs(packet.wireAtMs-expectedStart)<Math.abs(countdown-expectedStart)))countdown=packet.atMs;
            if(atEnd&&MmcSumoReplay.resultLine(text))result=packet.atMs;
        }
        boolean hasCountdown=Double.isFinite(countdown),hasResult=Double.isFinite(result);
        if(!hasCountdown)countdown=expectedStart;
        double start=countdown,finish=hasResult?result:expectedFinish;
        ReplayMotion.Vector arena=arenaPosition(poses,countdown);
        List<ReplayRecording.Wire> terrain=new ArrayList<>();
        List<ReplayRecording.Wire> earlier=Collections.emptyList();
        boolean spawnChunk=false;
        if(arena!=null){
            // The last sample before a countdown can still be on the lobby side
            // of its teleport. Show the first captured pose on the arena side.
            for(ReplayRecording.Pose pose:poses){
                if(pose.atMs<countdown-100)continue;
                if(pose.atMs>countdown+600)break;
                ReplayMotion.Vector pos=ReplayMotion.vector(pose.self.get("pos"));
                if(pos!=null&&close(pos,arena,12)){
                    start=Math.max(countdown,pose.atMs);
                    break;
                }
            }
            int chunkX=(int)Math.floor(arena.x/16),chunkZ=(int)Math.floor(arena.z/16);
            for(ReplayRecording.Wire packet:packets){
                if(!packet.inbound)continue;
                if(packet.atMs>finish-500&&packet.atMs<=finish&&packet.protocolId==8&&outOfArena(packet,arena))
                    finish=Math.nextDown(packet.atMs);
                if(packet.atMs<=start+1400){
                    if(fullChunk(packet,chunkX,chunkZ,0))spawnChunk=true;
                    if(packet.atMs>start&&fullChunk(packet,chunkX,chunkZ,4))terrain.add(packet);
                }
            }
            if(!spawnChunk)earlier=ReplayRecording.earlierChunks(entry.firstFile,chunkX,chunkZ,parser);
        }
        finish=Math.max(0,Math.min(finish,duration));
        return new ReplaySumoClip(Math.min(start,finish),finish,countdown,hasCountdown,hasResult,terrain,earlier,
                spawnChunk||!earlier.isEmpty());
    }

    private static ReplayMotion.Vector arenaPosition(List<ReplayRecording.Pose> poses,double countdown){
        ReplayMotion.Vector candidate=null;
        for(ReplayRecording.Pose pose:poses){
            if(pose.atMs<countdown)continue;
            if(pose.atMs>countdown+850)break;
            ReplayMotion.Vector pos=ReplayMotion.vector(pose.self.get("pos"));
            if(pos==null)continue;
            candidate=pos;
            if(pose.atMs>=countdown+400)break;
        }
        return candidate;
    }

    private static boolean close(ReplayMotion.Vector a,ReplayMotion.Vector b,double radius){
        return Math.hypot(a.x-b.x,a.z-b.z)<radius&&Math.abs(a.y-b.y)<radius;
    }

    private static boolean outOfArena(ReplayRecording.Wire packet,ReplayMotion.Vector arena){
        try{
            Cursor cursor=new Cursor(packet.bytes);cursor.varint();
            double x=cursor.double64(),y=cursor.double64(),z=cursor.double64();
            cursor.skip(8);int flags=cursor.unsigned();
            return (flags&7)==0&&!close(new ReplayMotion.Vector(x,y,z),arena,24);
        }catch(IOException malformed){return false;}
    }

    static boolean fullChunk(ReplayRecording.Wire packet,int x,int z,int radius){
        if(packet.protocolId!=0x21&&packet.protocolId!=0x26)return false;
        try{
            Cursor cursor=new Cursor(packet.bytes);cursor.varint();
            if(packet.protocolId==0x21){
                int cx=cursor.int32(),cz=cursor.int32();
                return cursor.unsigned()!=0&&cursor.short16()!=0&&near(x,z,cx,cz,radius);
            }
            cursor.unsigned();int count=cursor.varint();
            if(count<0||count>1024)return false;
            for(int i=0;i<count;i++){
                int cx=cursor.int32(),cz=cursor.int32();
                if(cursor.short16()!=0&&near(x,z,cx,cz,radius))return true;
            }
        }catch(IOException malformed){return false;}
        return false;
    }

    private static boolean near(int x,int z,int cx,int cz,int radius){
        return Math.abs((long)x-cx)<=radius&&Math.abs((long)z-cz)<=radius;
    }

    private static String chat(ReplayRecording.Wire packet,Function<String,Map<String,Object>> parser){
        try{
            Cursor cursor=new Cursor(packet.bytes);cursor.varint();int size=cursor.varint();
            if(size<0||size>4096||size>packet.bytes.length-cursor.at-1||packet.bytes[cursor.at+size]==2)return "";
            String json=new String(packet.bytes,cursor.at,size,StandardCharsets.UTF_8);
            return plain(parser.apply(json)).replaceAll("(?i)§[0-9A-FK-OR]", "").trim();
        }catch(IOException|RuntimeException malformed){return "";}
    }

    private static String plain(Object value){
        if(value instanceof String)return (String)value;
        if(value instanceof Map){
            Map<?,?> component=(Map<?,?>)value;
            return plain(component.get("text"))+plain(component.get("extra"));
        }
        if(value instanceof List){
            StringBuilder text=new StringBuilder();
            for(Object sibling:(List<?>)value)text.append(plain(sibling));
            return text.toString();
        }
        return "";
    }
}
