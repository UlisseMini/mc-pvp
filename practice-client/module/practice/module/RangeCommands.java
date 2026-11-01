package practice.module;

import practice.api.Frame;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Module-owned commands go through the bridge's existing /practice (or /p) dispatcher. */
final class RangeCommands {
    private Path directory;private String notice="";private long until;private boolean error;
    void configure(Properties p,Path directory){this.directory=directory;notice=p.getProperty("range.notice","");if(notice.length()>180)notice=notice.substring(0,180);
        try{until=Math.min(Long.parseLong(p.getProperty("range.notice_until_ms","0")),System.currentTimeMillis()+6000);}catch(NumberFormatException e){until=0;}error=Boolean.parseBoolean(p.getProperty("range.notice_error","false"));}
    boolean handle(String[] args,RangeIndicators indicators){
        if(args.length==0||!args[0].equals("range"))return false;
        try{
            if(args.length<3||args.length>4||!Arrays.asList("yours","theirs","both").contains(args[1]))throw new IllegalArgumentException("/p range yours|theirs|both on|off|crosshair|edge|screen; or color HEX / opacity 0–100|auto / pulse on|off");
            Path file=directory.resolve("settings.properties");Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}
            List<String> sides=args[1].equals("both")?Arrays.asList("yours","theirs"):Collections.singletonList(args[1]);
            for(String side:sides){String prefix="range."+side+".";
                if(args.length==3){String value=args[2].toLowerCase(Locale.ROOT);
                    if(value.equals("on")||value.equals("off"))p.setProperty(prefix+"enabled",String.valueOf(value.equals("on")));
                    else{p.setProperty(prefix+"style",RangeIndicators.styleName(value));p.setProperty(prefix+"enabled","true");}
                }else if(args[2].equals("color")){String value=args[3].replaceFirst("^#","");if(!value.matches("[0-9a-fA-F]{6}"))throw new IllegalArgumentException("Color needs six hex digits, e.g. FF5555");p.setProperty(prefix+"color",value.toUpperCase(Locale.ROOT));}
                else if(args[2].equals("opacity")){String value=args[3];if(!value.equals("auto"))RangeIndicators.percent(value);p.setProperty(prefix+"opacity",value);}
                else if(args[2].equals("pulse")){if(!Arrays.asList("on","off").contains(args[3]))throw new IllegalArgumentException("Pulse: on or off");p.setProperty(prefix+"pulse",String.valueOf(args[3].equals("on")));}
                else throw new IllegalArgumentException("Use color HEX, opacity 0–100|auto, or pulse on|off");
            }
            // Validate before replacing the file; bad input cannot disable a running module.
            new RangeIndicators().configure(p);
            notice="Range "+String.join(" ",Arrays.copyOfRange(args,1,args.length));until=System.currentTimeMillis()+5000;error=false;
            p.setProperty("range.notice",notice);p.setProperty("range.notice_until_ms",Long.toString(until));p.setProperty("range.notice_error","false");
            Path temp=Files.createTempFile(directory,"range-settings-",".tmp");try{try(OutputStream out=Files.newOutputStream(temp)){p.store(out,"PvP Practice: reloads automatically");}Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
            indicators.configure(p);
        }catch(IllegalArgumentException|IOException e){notice=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();until=System.currentTimeMillis()+6000;error=true;}
        return true;
    }
    Frame feedback(Frame base){
        if(System.currentTimeMillis()>until)return base;
        List<Frame.Line> lines=new ArrayList<>(base.lines);
        // Temporary command feedback, placed away from both the crosshair and bottom match stats.
        String[] words=notice.split(" ");StringBuilder row=new StringBuilder();int y=50;
        for(String word:words){if(row.length()+word.length()>66){lines.add(new Frame.Line(row.toString(),0,y,error?0xFF8888:0xAAFFDD,false,1,true));row.setLength(0);y+=11;}if(row.length()>0)row.append(' ');row.append(word);}
        if(row.length()>0)lines.add(new Frame.Line(row.toString(),0,y,error?0xFF8888:0xAAFFDD,false,1,true));
        return new Frame(base.holdSprint,lines,base.edgeColor,base.edgeWidth,base.rectangles);
    }
}
