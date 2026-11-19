import java.io.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import lab.DuelNetwork;

/** Validate duel files using the actual Forge client class in the installed bridge. Java 8 compatible. */
final class PlayableCheck {
    public static void main(String[] args)throws Exception{
        Method validator=Class.forName("lab.Client").getDeclaredMethod("validateControls",Properties.class);
        validator.setAccessible(true);
        for(String name:Files.readAllLines(Paths.get(args[0]))){
            Properties p=new Properties();
            try(InputStream stream=Files.newInputStream(Paths.get("policies",name+".properties"))){p.load(stream);}
            p.setProperty("enabled","true");p.setProperty("match","m9000");p.setProperty("opponent","LabSlot1");
            p.setProperty("reaction_ms","0.0");p.setProperty("click_cps","20");p.setProperty("attack_reach","3.0");
            p.setProperty("aim_input","mouse");p.setProperty("aim_delay_ms","0");p.setProperty("aim_noise_deg","0");p.setProperty("aim_turn_rate_dps","0");
            try{
                validator.invoke(null,p);
                if(p.containsKey("duel_network"))DuelNetwork.decode(p.getProperty("duel_network"));
                System.out.println(name+" ok");
            }catch(InvocationTargetException bad){
                System.out.println(name+" rejected "+bad.getCause().toString());
            }catch(IllegalArgumentException bad){System.out.println(name+" rejected "+bad);}
        }
    }
}
