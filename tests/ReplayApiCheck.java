package lab;

/** Resolve packet names, JSON decoding and historical recordings with the pinned server jar. */
public final class ReplayApiCheck {
    public static void main(String[] args)throws Exception {
        for(String name:new String[]{"S0CPacketSpawnPlayer","S38PacketPlayerListItem","S18PacketEntityTeleport","S1CPacketEntityMetadata",
            "S04PacketEntityEquipment","S0BPacketAnimation","S19PacketEntityStatus","S19PacketEntityHeadLook","S0EPacketSpawnObject","S13PacketDestroyEntities","S43PacketCamera"})
            R.cls("net.minecraft.network.play.server."+name);
        ServerReplay.listAction("ADD_PLAYER");ServerReplay.listAction("REMOVE_PLAYER");
        R.call(R.cls("net.minecraft.init.Bootstrap"),"register");
        if(ServerReplay.namedItemId("wooden_sword")!=268)throw new AssertionError("Item ID");
        for(String invalid:new String[]{"../server/ops","m1","m0001/../m0002","m0001.json"}){
            boolean rejected=false;try{ReplayData.resultPath(invalid);}catch(IllegalArgumentException e){rejected=true;}
            if(!rejected)throw new AssertionError("Unsafe replay ID");
        }
        ReplayData data=new ReplayData(args[0]);
        if(data.frames.size()<5||data.duration<=0)throw new AssertionError("Historical recording load");
        if(data.frame(0)!=data.frames.get(0)||data.frame(Long.MAX_VALUE)!=data.frames.get(data.frames.size()-1))throw new AssertionError("Replay endpoints");
        System.out.println("PASS pinned replay packet APIs, JSON, item lookup, path validation, historical recording "+args[0]+" ("+data.frames.size()+" frames)");
    }
}
