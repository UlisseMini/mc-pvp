package sword;

import java.lang.invoke.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Export three actual vanilla render passes per 20 Hz game tick in a scheduled exhibition. */
public final class FilmFrames {
    private static final VarHandle DELTA, FRACTION, UI_DELTA;
    static {
        try {
            var lookup=MethodHandles.privateLookupIn(DeltaTracker.Timer.class,MethodHandles.lookup());
            DELTA=lookup.findVarHandle(DeltaTracker.Timer.class,"deltaTicks",float.class);
            FRACTION=lookup.findVarHandle(DeltaTracker.Timer.class,"deltaTickResidual",float.class);
            UI_DELTA=lookup.findVarHandle(DeltaTracker.Timer.class,"realtimeDeltaTicks",float.class);
        } catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    private static int phase=2;
    private static String round="";
    private static Session session;
    private static Path frames(){return Path.of(System.getProperty("sword.spool"),"frames");}
    public static int advance(DeltaTracker.Timer timer,long realTime){
        if(!Lockstep.active)return timer.advanceGameTime(realTime);
        phase=(phase+1)%3;
        DELTA.set(timer,1f/3);FRACTION.set(timer,phase/3f);
        return phase==0?1:0;
    }
    public static void advanceUi(DeltaTracker.Timer timer,long realTime){
        if(Lockstep.active)UI_DELTA.set(timer,1f/3);
        else timer.advanceRealTime(realTime);
    }
    public static void capture(Minecraft mc){
        if(!Lockstep.active)return;
        try {
            String id=ClientKeys.command.split("\t",2)[0];
            if(!id.equals(round)){
                if(session!=null)session.close();
                round=id;session=null;
                if(!id.isEmpty() && Files.exists(frames().resolve(id+"-"+ClientKeys.control.getFileName())))
                    session=new Session(frames().resolve(id));
            }
            if(session!=null && ClientKeys.ticks>0)session.capture(mc,Lockstep.tick*3+phase);
        } catch(IOException e){throw new IllegalStateException("Could not export native film frame",e);}
    }
    private static final class Session {
        final Path folder;
        final PrintWriter pose;
        long first=-1, last=-1;
        int requested, written;
        boolean closed;
        Session(Path folder)throws IOException{
            this.folder=Files.createDirectories(folder);
            pose=SmoothAim.ENABLED?new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("sword.logs")).resolve(round+"-camera.tsv"))):null;
            if(pose!=null)pose.println("frame\ttick\tphase\tcameraYaw\tcameraPitch\tinputYaw\tinputPitch");
        }
        void capture(Minecraft mc,long frame){
            if(frame==last)return;
            if(first<0)first=frame;
            if(frame!=first+requested)throw new IllegalStateException("Missing native render pass at "+frame+" after "+last);
            last=frame;
            int index=requested++;
            if(pose!=null){
                var camera=mc.gameRenderer.mainCamera();
                pose.println(index+"\t"+(ClientKeys.ticks-1)+"\t"+phase+"\t"+camera.yRot()+"\t"+camera.xRot()+"\t"+mc.player.getYRot()+"\t"+mc.player.getXRot());
            }
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(),img->{
                try(img){
                    img.writeToFile(folder.resolve(String.format(Locale.ROOT,"%06d.png",index)));
                    synchronized(this){written++;finish();}
                }catch(IOException e){throw new IllegalStateException("Could not save native render pass",e);}
            });
        }
        synchronized void close()throws IOException{closed=true;if(pose!=null)pose.close();finish();}
        void finish()throws IOException{
            if(closed&&written==requested){
                if(requested==0)throw new IllegalStateException("Empty native film");
                Path tmp=folder.resolve("finished.tmp");
                Files.writeString(tmp,requested+"\t"+first+"\t"+last);
                Files.move(tmp,folder.resolve("finished"),StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }
}
