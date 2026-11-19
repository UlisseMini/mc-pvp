package lab;
import java.nio.file.*;
public final class BoxingLandmarkCheck {
    public static void main(String[] args)throws Exception{
        Path d=Paths.get(args[0]),a=d.resolve("a"),b=d.resolve("b");
        var g=new BoxingLandmark.Session(3,"match","a",a,b,a);
        g.accepted("a",false,90);
        BoxingLandmark.click(a,"old",70);BoxingLandmark.click(b,"match",100);
        g.poll();if(g.wake!=-1)throw new AssertionError("Stale slot file started the drill");
        for(long t:new long[]{100,101})g.accepted("a",false,t);
        g.accepted("b",true,101);g.accepted("a",false,101);
        BoxingLandmark.click(a,"match",50);g.poll();
        if(g.wake!=100||g.streak!=1||g.landmark!=-1)throw new AssertionError("Wake or within-drain order");
        g.accepted("a",true,102);g.accepted("a",false,103);
        if(g.streak!=2||BoxingLandmark.started(a,"match"))throw new AssertionError("Premature release");
        g.accepted("a",false,104);g.accepted("b",false,104);g.poll();
        if(g.landmark!=104||!BoxingLandmark.started(a,"match")||BoxingLandmark.started(b,"match"))throw new AssertionError("Incorrect subject release");
        long first=g.announced;g.poll();
        if(g.announced!=first||BoxingLandmark.started(a,"other"))throw new AssertionError("Repeated release or cross-match leak");
        System.out.println("PASS actual clicks, stale IDs, damage upgrades, tied timestamps, subject-only one-shot boxing landmark");
    }
}
