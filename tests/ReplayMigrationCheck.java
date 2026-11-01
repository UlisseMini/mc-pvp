package lab;

/** Opens real existing recordings through the production replay reader, without a server. */
public final class ReplayMigrationCheck {
    public static void main(String[] args)throws Exception{
        for(String id:args){
            ReplayData replay=new ReplayData(id);
            if(replay.frames.isEmpty()||replay.duration<=0)throw new AssertionError("No server timeline: "+id);
            for(String source:new String[]{"a","b"}){
                if(!replay.pov.available(source))throw new AssertionError("Missing source: "+id+" "+source);
                if(replay.pov.at(source,replay.duration/2.0).isEmpty())throw new AssertionError("Missing mid-match poses: "+id+" "+source);
            }
            System.out.println("PASS historical/new replay "+id+": server and both client tracks; "+replay.pov.quality);
        }
    }
}
