package practice.bridge;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import lab.Client;
import lab.ClientServices;
import net.minecraft.client.Minecraft;

/** Trusted launch configuration is the only authority that can own native inputs. */
final class BotRuntime implements ClientServices,AutoCloseable {
    static volatile BotRuntime instance;
    final String slot;
    final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final PracticeCapture capture;
    private boolean identified;
    private BotRuntime(PracticeCapture capture,Path game)throws IOException {
        this.capture=capture;slot=System.getProperty("lab.slot","");
        if(!slot.matches("[0-7]")||!"client".equals(System.getProperty("lab.role"))||!"forge".equals(System.getProperty("lab.backend")))
            throw new IllegalArgumentException("Bot mode requires a valid local slot and Forge lab launch");
        int port=Integer.parseInt(System.getProperty("lab.port","0"));
        if(port<1024||port>65535)throw new IllegalArgumentException("Invalid loopback bot proxy port");
        Path root=Paths.get(System.getProperty("lab.root","")).toRealPath();
        directory=root.resolve("runtime/bots/"+slot).toRealPath();
        if(!game.toRealPath().equals(directory)||!Files.isRegularFile(directory.resolve("control.properties")))
            throw new IllegalArgumentException("Bot role must own its existing isolated slot directory");
        lockChannel=FileChannel.open(directory.resolve("forge-role.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        lock=lockChannel.tryLock();
        if(lock==null){lockChannel.close();throw new IllegalStateException("Bot slot "+slot+" is already owned by another process");}
        Client.installServices(this);
    }
    static void start(PracticeCapture capture,Path game)throws IOException {
        String role=System.getProperty("practice.role","human");
        if(role.equals("human"))return;
        if(!role.equals("bot"))throw new IllegalArgumentException("practice.role must be human or bot");
        instance=new BotRuntime(capture,game);
        System.out.println("[PracticeBridge] Explicit bot role: LabSlot"+instance.slot);
    }
    static boolean enabled(){return instance!=null;}
    static String role(){return enabled()?"bot":"human";}
    public void tick(){
        Minecraft mc=Minecraft.func_71410_x();
        if(mc.field_71439_g!=null){
            if(!mc.field_71439_g.func_70005_c_().equals("LabSlot"+slot))throw new IllegalStateException("Bot slot username mismatch");
            identified=true;
        }else if(identified){Client.releaseInputs();identified=false;}
        System.setProperty("practice.module_sha256",capture.moduleSha256());
        capture.botRoute(Client.configuration());
    }
    public void frame(){capture.headlessFrame();}
    public void event(String encoded){capture.botEvent(encoded);}
    public boolean probe(Object mc,Object player,long elapsed){return false;}
    public void close(){Client.releaseInputs();try{lock.release();lockChannel.close();}catch(IOException ignored){}}
}
