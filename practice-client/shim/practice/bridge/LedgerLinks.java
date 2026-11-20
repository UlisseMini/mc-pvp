package practice.bridge;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import static practice.replay.PoseTrack.object;

/** Reads the compact local bookmark index written by match_ledger.py. */
final class LedgerLinks {
    private static final int PAGE_SIZE=8;

    static boolean validId(String id){return id.matches("m[0-9]{4,9}|[a-f0-9]{32}");}

    static void show(Path directory,Gson gson,String id,Integer requestedPage)throws IOException{
        if(!validId(id))throw new IllegalArgumentException("Use /p ledger <practice match ID or MMC bookmark ID> [page]");
        Path file=directory.resolve("match-ledgers").resolve(id+".json");
        if(!Files.isRegularFile(file))throw new IOException("Generate the ledger with match_ledger.py "+id+" first");
        if(Files.size(file)>1024*1024)throw new IOException("The ledger bookmark index is too large");
        Map<String,Object> data=object(gson.fromJson(new String(Files.readAllBytes(file),StandardCharsets.UTF_8),Map.class));
        long start=millis(data.get("start_ms"));
        Object raw=data.get("windows");
        if(!id.equals(data.get("id"))||start<=0||!(raw instanceof List))throw new IOException("Invalid ledger bookmark index");
        List<?> windows=(List<?>)raw;
        if(windows.isEmpty()){message("No exchanges identified in "+id,null);return;}
        int pages=(windows.size()+PAGE_SIZE-1)/PAGE_SIZE;
        int page=requestedPage==null?pages:requestedPage;
        if(page<1||page>pages)throw new IllegalArgumentException("Page must be between 1 and "+pages);
        message("Trade ledger "+id+" · page "+page+" of "+pages+". Click an entry to seek.",null);
        for(int index=(page-1)*PAGE_SIZE;index<Math.min(windows.size(),page*PAGE_SIZE);index++){
            Map<String,Object> row=object(windows.get(index));
            long seek=millis(row.get("seek_ms")),at=millis(row.get("start_ms"));
            if(at<0||seek<0||seek>at||at>86400000||!(row.get("title") instanceof String))continue;
            String title=((String)row.get("title")).replaceAll("[\\p{Cntrl}§]","");
            if(title.length()>90)title=title.substring(0,90);
            String confidence="measured".equals(row.get("confidence"))?"confirmed":"review";
            message(String.format(Locale.ROOT,"[%.3fs] %s · %s",at/1000.0,title,confidence),"/p replayat "+id+" "+(start+seek));
        }
        if(page>1)message("[Earlier exchanges]","/p ledger "+id+" "+(page-1));
        if(page<pages)message("[Later exchanges]","/p ledger "+id+" "+(page+1));
    }

    private static long millis(Object value){return value instanceof Number?((Number)value).longValue():-1;}

    private static void message(String text,String command){
        ChatComponentText chat=new ChatComponentText(text);
        if(command!=null)chat.func_150255_a(new ChatStyle().func_150238_a(EnumChatFormatting.AQUA)
                .func_150241_a(new ClickEvent(ClickEvent.Action.RUN_COMMAND,command)));
        Minecraft.func_71410_x().field_71456_v.func_146158_b().func_146227_a(chat);
    }
}
