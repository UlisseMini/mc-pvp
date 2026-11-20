package sword;

import java.io.*;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.AbstractClientPlayer;
import static sword.Fighter.*;

public final class ClientKeys {
    static final Path control=Path.of(System.getProperty("sword.control"));
    static String command="";
    static Model.Controller controller;
    static Model.Controller opening;
    static Path branchSignal,firstPress;
    static double lx,ly,lz;
    static boolean primed;
    static String target;
    static long start;
    static int ticks;
    static long now(){return System.getProperty("sword.clock")==null?System.currentTimeMillis():Lockstep.time();}
    static PrintWriter trace;
    static void apply(Minecraft mc,Keys k){
        var o=mc.options;
        o.keyUp.setDown(k.forward()>0);o.keyDown.setDown(k.forward()<0);
        o.keyRight.setDown(k.side()<0);o.keyLeft.setDown(k.side()>0);
        o.keySprint.setDown(k.sprint());o.keyJump.setDown(k.jump());
        if(k.click())KeyMapping.click(o.keyAttack.getDefaultKey());
    }
    public static void tick(Minecraft mc){
        if(mc.player==null||mc.level==null)return;
        try{
            mc.options.pauseOnLostFocus=false;
            String line;
            try{line=Files.readString(control).trim();}
            catch(NoSuchFileException pending){apply(mc,Keys.STILL);return;}
            if(!line.equals(command)){
                command=line;controller=null;opening=null;branchSignal=null;firstPress=null;primed=false;ticks=0;
                if(trace!=null){trace.close();trace=null;}
                if(!line.isEmpty()){
                    var fields=line.split("\t");target=fields[1];start=Long.parseLong(fields[2]);
                    controller=Controls.get(fields[3]);
                    if(fields.length>4){
                        if(fields[4].equals("opening")){
                            opening=Controls.get("wtap");
                            branchSignal=control.resolveSibling(fields[0]+"-branch");
                        }else firstPress=control.resolveSibling(fields[0]+"-defender-press");
                    }
                    trace=new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("sword.logs")).resolve(fields[0]+"-"+control.getFileName()+".tsv")));
                    trace.println("tick\tx\ty\tz\ttargetX\ttargetY\ttargetZ\tvx\tvy\tvz\tground\tsprint\tcharge\thp\thurt\totherHurt\tforward\tside\trunKey\tjump\tpress\tyaw\tpitch\tepochMs\tassignedPolicyActive");
                }
            }
            if(controller==null||now()<start){apply(mc,Keys.STILL);return;}
            AbstractClientPlayer other=null;
            for(var p:mc.level.players())if(p.getGameProfile().name().equals(target))other=p;
            if(other==null||!mc.player.isAlive()||!other.isAlive()){apply(mc,Keys.STILL);return;}
            var p=mc.player;var vel=p.getDeltaMovement();
            if(ticks==0&&Lockstep.ENABLED){
                Path initial=Path.of(System.getProperty("sword.logs")).resolve(command.split("\t")[0]+"-"+control.getFileName()+".initial");
                Files.writeString(initial,p.getYRot()+"\t"+LockstepClient.localTicksFirst(mc,other)+"\n");
            }
            var v=new View(p.getX(),p.getY(),p.getZ(),vel.x,vel.y,vel.z,
                other.getX(),other.getY(),other.getZ(),primed?other.getX()-lx:0,primed?other.getY()-ly:0,primed?other.getZ()-lz:0,
                p.onGround(),p.isSprinting(),p.getAttackStrengthScale(0),p.getHealth(),p.hurtTime,other.hurtTime,p.getYRot());
            lx=other.getX();ly=other.getY();lz=other.getZ();primed=true;
            if(opening!=null&&Files.exists(branchSignal))opening=null;
            Keys k;
            if(opening==null)k=controller.act(v);
            else{k=opening.act(v);controller.prime(v,k);}
            long stamp=now();
            if(firstPress!=null&&k.click()){
                Path temp=firstPress.resolveSibling(firstPress.getFileName()+".tmp");
                Files.writeString(temp,Long.toString(stamp));Files.move(temp,firstPress,StandardCopyOption.ATOMIC_MOVE);
                firstPress=null;
            }
            p.setYRot((float)steer(v,k));
            double dy=clip(p.getEyeY(),other.getY()+.1,other.getY()+1.7)-p.getEyeY();
            p.setXRot((float)-Math.toDegrees(Math.atan2(dy,Math.hypot(lx-p.getX(),lz-p.getZ()))));
            apply(mc,k);
            trace.printf(java.util.Locale.ROOT,"%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%b\t%b\t%s\t%s\t%d\t%d\t%d\t%d\t%b\t%b\t%b\t%s\t%s\t%d\t%b%n",
                ticks++,p.getX(),p.getY(),p.getZ(),lx,ly,lz,vel.x,vel.y,vel.z,v.grounded(),v.sprint(),v.charge(),v.health(),v.hurt(),v.otherHurt(),k.forward(),k.side(),k.sprint(),k.jump(),k.click(),p.getYRot(),p.getXRot(),stamp,opening==null);
            if(ticks%20==0)trace.flush();
        }catch(Exception e){throw new IllegalStateException("Sword input callback",e);}
    }
}
