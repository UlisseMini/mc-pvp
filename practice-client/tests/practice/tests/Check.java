package practice.tests;

import practice.api.*;
import practice.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;

public final class Check {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Snapshot snapshot(long tick,boolean press,boolean screen,boolean playing){
        Snapshot.Player self=new Snapshot.Player(1,0,4,0,.2,0,0,5.62,20,0,true,false,false);
        return new Snapshot(tick*50,tick*50000000,tick,playing,screen,press,self,null,Arrays.asList("Speed II  1:20"));
    }
    private static void config(Path dir,String text)throws IOException{Files.write(dir.resolve("settings.properties"),text.getBytes("UTF-8"));}
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("practice-check-");
        Files.copy(Paths.get(args[0]),dir.resolve("practice-module.jar"));config(dir,"");
        try(ReloadHost host=new ReloadHost(dir)){
            check(host.reload(true),"load module from its jar");
            Frame f=host.tick(snapshot(1,false,false,true));
            check(f.holdSprint,"toggle sprint starts enabled");
            check(f.lines.get(0).right && f.lines.get(0).x==6 && f.lines.get(0).y==6,"sprint HUD at top right");
            check(f.lines.get(1).text.contains("Speed II"),"potion effect included");
            check(!host.tick(snapshot(2,true,false,true)).holdSprint,"press toggles off");
            check(!host.tick(snapshot(3,false,false,true)).holdSprint,"release stays off");
            check(host.tick(snapshot(4,true,false,true)).holdSprint,"next press toggles on");
            check(!host.tick(snapshot(5,true,true,true)).holdSprint,"GUI releases sprint and ignores toggles");
            check(host.tick(snapshot(6,false,false,true)).holdSprint,"closing GUI restores toggle");
            check(host.tick(snapshot(7,false,false,false)).lines.isEmpty(),"no HUD in menus");
            config(dir,"sprint.label=Reloaded live\nhud.rightInset=20\npotions.enabled=false\nmetrics.enabled=true\n");
            check(host.reload(false),"automatic config change reloads");
            f=host.tick(snapshot(8,false,false,true));
            check(f.lines.get(0).text.equals("Reloaded live")&&f.lines.get(0).x==20,"new HUD configuration takes effect");
            check(f.lines.stream().anyMatch(l->l.text.contains("4.00 m/s")),"20Hz displacement conversion");
            check(f.lines.stream().noneMatch(l->l.text.contains("Speed II")),"potion setting off");
            check(!host.reload(false),"unchanged file not reloaded");
            config(dir,"hud.color=invalid\n");check(!host.reload(false),"invalid config rejected");
            check(host.tick(snapshot(9,false,false,true)).lines.get(0).text.equals("Reloaded live"),"previous working module survives bad config");
            Files.write(dir.resolve("practice-module.jar"),new byte[]{1,2,3});
            check(!host.reload(false),"corrupt jar rejected");
            check(host.tick(snapshot(10,false,false,true)).lines.get(0).text.equals("Reloaded live"),"old code still runs after jar failure");
            Files.copy(Paths.get(args[0]),dir.resolve("practice-module.jar"),StandardCopyOption.REPLACE_EXISTING);
            config(dir,"record.enabled=true\nsprint.enabled=false\n");check(host.reload(false),"recovery after failure");
            check(!host.tick(snapshot(20,false,false,true)).holdSprint,"feature disabled does not hold sprint");
            host.tick(snapshot(21,false,false,false));
            Path csv;
            try(java.util.stream.Stream<Path> paths=Files.list(dir.resolve("recordings"))){csv=paths.findFirst().get();}
            List<String> rows=Files.readAllLines(csv);
            check(rows.size()==2,"record one tick then flush on disconnect");
            check(rows.get(0).split(",",-1).length==25 && rows.get(1).split(",",-1).length==25,"CSV columns including missing target");
            check(!rows.get(0).contains("name")&&!rows.get(0).contains("token"),"record numeric observations only");
        }
        // A fresh classloader must pick up changed bytecode, even with identical class names.
        ByteArrayOutputStream changedBytes=new ByteArrayOutputStream();
        try(JarInputStream in=new JarInputStream(Files.newInputStream(Paths.get(args[0])));JarOutputStream out=new JarOutputStream(changedBytes)){
            JarEntry entry;while((entry=in.getNextJarEntry())!=null){
                ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
                while((n=in.read(buf))!=-1)data.write(buf,0,n);
                byte[] bytes=data.toByteArray();byte[] from="[Sprinting (Toggled)]".getBytes("UTF-8"),to="[SPRINTING (TOGGLED)]".getBytes("UTF-8");
                for(int i=0;i<=bytes.length-from.length;i++){
                    boolean equal=true;for(int j=0;j<from.length;j++)if(bytes[i+j]!=from[j]){equal=false;break;}
                    if(equal)System.arraycopy(to,0,bytes,i,to.length);
                }
                out.putNextEntry(new JarEntry(entry.getName()));out.write(bytes);out.closeEntry();
            }
        }
        config(dir,"");Files.copy(Paths.get(args[0]),dir.resolve("practice-module.jar"),StandardCopyOption.REPLACE_EXISTING);
        try(ReloadHost host=new ReloadHost(dir)){
            host.reload(true);check(host.tick(snapshot(1,false,false,true)).lines.get(0).text.equals("[Sprinting (Toggled)]"),"initial code");
            Files.write(dir.resolve("practice-module.jar"),changedBytes.toByteArray());
            check(host.reload(false),"changed bytecode loaded without JVM restart");
            check(host.tick(snapshot(2,false,false,true)).lines.get(0).text.equals("[SPRINTING (TOGGLED)]"),"new code executed in same process");
        }
        try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}
        System.out.println("Practice checks passed: "+checks);
    }
}
