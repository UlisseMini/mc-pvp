package lab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static lab.ReplayData.*;

/** Projects selected rows from a POV's event archive, off the game thread. */
final class ClientReplaySteps {
    private final ReplaySteps index;
    private final long start,duration;
    private final Map<Long,Velocity> velocities=new HashMap<>();
    private Map<String,Object> previous=Map.of();
    private List<?> clickMotion=List.of();
    private int selfId=-1;
    private String session="";
    private record Velocity(int entity,double horizontal,double vertical) {}
    private static final Set<String> TYPES=Set.of("capture_end","tick_end","attack_click_before","attack_click_after","packet_wire","packet_handler_return","motion_jump_after");

    private ClientReplaySteps(String side,long start,long duration){
        this.index=new ReplaySteps(side);this.start=start;this.duration=duration;
    }
    static ReplaySteps read(Path folder,String side,long start,long duration)throws IOException {
        ClientReplaySteps reader=new ClientReplaySteps(side,start,duration);
        if(!Files.isDirectory(folder))return reader.index;
        List<Path> files;
        try(var stream=Files.list(folder)){files=stream.filter(p->p.getFileName().toString().matches(side+"-client-.*-events(?:-part[0-9]+)?\\.jsonl\\.gz"))
            .sorted(Comparator.comparing((Path p)->p.getFileName().toString().split("-events")[0]).thenComparingInt(ClientReplaySteps::part)).toList();}
        long chars=0;
        for(Path path:files)try(BufferedReader in=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)),StandardCharsets.UTF_8))){
            String line;boolean complete=false;
            while((line=in.readLine())!=null){
                chars+=line.length();
                if(chars>512L*1024*1024)throw new IOException("Client event archive exceeds the 512 MiB review limit.");
                // CaptureLog normally writes type first. Skip large render/network rows before allocating maps.
                if(line.startsWith("{\"type\":\"")){int end=line.indexOf('"',9);if(end>=9&&!TYPES.contains(line.substring(9,end)))continue;}
                if(line.startsWith("{\"type\":\"packet_")&&!line.contains("S12PacketEntityVelocity"))continue;
                Map<String,Object> row=parse(line);
                if("capture_end".equals(row.get("type")))complete=flag(row,"writer_complete");else reader.add(row);
            }
            if(!complete)reader.index.partial=true;
        }catch(EOFException e){reader.index.partial=true;}
        reader.index.sort();return reader.index;
    }
    private static int part(Path p){
        String name=p.getFileName().toString();int idx=name.lastIndexOf("-part");return idx<0?0:Integer.parseInt(name.substring(idx+5,name.indexOf('.',idx)));
    }
    private void add(Map<String,Object> row){
        long ms=(long)number(row,"time_ms",0)-start;if(ms<0||ms>duration)return;
        String id=string(row,"session","");if(!id.equals(session)){previous=Map.of();clickMotion=List.of();velocities.clear();selfId=-1;session=id;}
        Map<String,Object> d=map(row.get("data")),self=map(d.get("self"));
        if(self.get("id") instanceof Number n)selfId=n.intValue();
        switch(string(row,"type","")){
            case "tick_end" -> {
                index.tick(ms,"client tick "+(long)number(row,"client_tick",0));
                index.transition(ms,previous,self,"ground","landed","left ground");
                index.transition(ms,previous,self,"sprinting","sprint on","sprint off");
                Map<String,Object> old=map(previous.get("movement_input")),input=map(self.get("movement_input"));
                if(old.get("moveForward") instanceof Number a && input.get("moveForward") instanceof Number b && Math.signum(a.doubleValue())!=Math.signum(b.doubleValue()))
                    index.event(ms,b.doubleValue()>0?"forward input on":b.doubleValue()<0?"backward input on":"forward/back input released");
                previous=self;
            }
            case "motion_jump_after" -> index.event(ms,"jump");
            case "attack_click_before" -> {
                String ray=string(map(d.get("ray")),"type","unknown").toLowerCase(Locale.ROOT);
                index.event(ms,"attack click · "+ray+(number(d,"left_click_counter",0)>0?" · click delay active":""));
                clickMotion=self.get("motion") instanceof List<?> m?m:List.of();
            }
            case "attack_click_after" -> {
                if(self.get("motion") instanceof List<?> after && horizontalDrag(clickMotion,after))
                    index.event(ms,"click slowed horizontal velocity ×0.60");
                clickMotion=List.of();
            }
            case "packet_wire" -> {
                Map<String,Object> packet=map(d.get("packet"));
                if(!"S12PacketEntityVelocity".equals(packet.get("kind"))||!"in".equals(packet.get("direction")))return;
                Velocity v=decode(string(d,"base64",""));if(v!=null && v.entity()==selfId)
                    velocities.put((long)number(packet,"packet_id",-1),v);
            }
            case "packet_handler_return" -> {
                if(!"S12PacketEntityVelocity".equals(d.get("kind"))||!flag(d,"main_thread"))return;
                Velocity v=velocities.remove((long)number(d,"packet_id",-1));
                if(v!=null)index.event(ms,String.format(Locale.ROOT,"applied velocity · H %.3f · Y %+.3f",v.horizontal(),v.vertical()));
            }
        }
    }
    private static boolean horizontalDrag(List<?> before,List<?> after){
        if(before.size()!=3||after.size()!=3)return false;
        if(!(before.get(0) instanceof Number bx)||!(before.get(2) instanceof Number bz)||!(after.get(0) instanceof Number ax)||!(after.get(2) instanceof Number az))return false;
        return Math.hypot(bx.doubleValue(),bz.doubleValue())>1e-5 && Math.abs(.6*bx.doubleValue()-ax.doubleValue())<1e-7 && Math.abs(.6*bz.doubleValue()-az.doubleValue())<1e-7;
    }
    private static int varint(DataInputStream in)throws IOException {
        int value=0;
        for(int shift=0;shift<32;shift+=7){int b=in.readUnsignedByte();value|=(b&127)<<shift;if((b&128)==0)return value;}
        throw new IOException("Invalid VarInt");
    }
    private static Velocity decode(String encoded){
        if(encoded.length()>32)return null;
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))){
            if(varint(in)!=0x12)return null;int entity=varint(in);
            double x=in.readShort()/8000.,y=in.readShort()/8000.,z=in.readShort()/8000.;
            return in.available()==0?new Velocity(entity,Math.hypot(x,z),y):null;
        }catch(IOException|IllegalArgumentException ignored){return null;}
    }
}
