import lab.Json;
import java.nio.file.*;
import java.util.*;

/** Re-score a saved oracle prefix over the SAME full horizon as baselines.
 * A fixed, predeclared W-tap fallback handles the tail after a pruned search.
 * Does not run a new search or claim the prefix is observation-only feedback.
 */
public final class ComboWitnessCheck {
    public static void main(String[]args)throws Exception{
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("Refuse overwrite");
        List<PeriodicComboClock.Control> prefix=new ArrayList<>();
        Scanner input=new Scanner(System.in);
        while(input.hasNextInt()){int key=input.nextInt(),strafe=input.nextInt(),click=input.nextInt();prefix.add(new PeriodicComboClock.Control(key,strafe*.98f,click!=0));}
        double reach=Double.parseDouble(args[1]),h=Double.parseDouble(args[2]),v=Double.parseDouble(args[3]),seconds=Double.parseDouble(args[5]),ping=Double.parseDouble(args[6]),b=Double.parseDouble(args[7]),server=Double.parseDouble(args[8]),seed=Double.parseDouble(args[9]);int ticks=Integer.parseInt(args[4]),burst=Integer.parseInt(args[10]);
        var row=ComboBaselineCheck.trial("oracle-prefix-then-wtap",0,reach,h,v,ticks,seconds,ping,b,server,seed,burst,prefix);
        Files.writeString(out,Json.encode(row)+"\n");
    }
}
