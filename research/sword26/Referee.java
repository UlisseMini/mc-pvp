package sword;

import java.nio.file.*;
import java.io.*;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

public final class Referee {
    static Path dir;
    static int tick=-1;
    static String[] round;
    static PrintWriter trace,contacts;
    static boolean boostedAttack;
    static int immunity;
    static float charge;
    public static void beforeAttack(net.minecraft.world.entity.player.Player p,net.minecraft.world.entity.Entity target){
        immunity=target instanceof net.minecraft.world.entity.LivingEntity living?living.damageCooldownTime:0;charge=p.getAttackStrengthScale(.5f);
        boostedAttack=p.isSprinting()&&p.getAttackStrengthScale(.5f)>.9f;
    }
    public static void contact(net.minecraft.world.entity.player.Player attacker,net.minecraft.world.entity.Entity target,
                               boolean critical,boolean sweep,boolean strong,boolean special){
        if(round!=null&&contacts!=null&&target instanceof ServerPlayer defender)
            contacts.printf(Locale.ROOT,"%d\t%s\t%s\t%b\t%b\t%b\t%b\t%.4f\t%d\t%d\t%.4f\t%d%n",
                tick,attacker.getGameProfile().name(),defender.getGameProfile().name(),critical,sweep,strong,boostedAttack,defender.getHealth(),defender.hurtTime,immunity,charge,System.currentTimeMillis());
    }
    static boolean initialized;
    static void cmd(MinecraftServer s,String c){s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),c);}
    static void prepare(MinecraftServer s,ServerPlayer p,double x,double z,double yaw){
        String name=p.getGameProfile().name();
        cmd(s,String.format(Locale.ROOT,"tp %s %.4f 101 %.4f %.2f 0",name,x,z,yaw));
        p.getInventory().clearContent();
        p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
        p.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));
        p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));
        p.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));
        p.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));
        p.removeAllEffects();p.setHealth(20);p.getFoodData().setFoodLevel(20);p.getFoodData().setSaturation(20);
        p.setInvulnerableTime(0);p.damageCooldownTime=0;p.hurtTime=0;p.setDeltaMovement(Vec3.ZERO);p.resetFallDistance();p.setSprinting(false);
    }
    static void write(String file,String content)throws IOException{
        var root=(file.equals("A")||file.equals("B"))?Path.of(System.getProperty("sword.spool")):dir;
        var p=root.resolve(file);var tmp=root.resolve(file+".tmp");Files.writeString(tmp,content);
        Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    public static void tick(MinecraftServer s){
        String root=System.getProperty("sword.run");if(root==null)return;
        try{
            dir=Path.of(root);
            if(!initialized){
                cmd(s,"forceload add -16 -16 16 16");
                cmd(s,"fill -13 100 -13 12 100 12 minecraft:stone");
                cmd(s,"fill -13 101 -13 -13 107 12 minecraft:barrier");
                cmd(s,"fill 12 101 -13 12 107 12 minecraft:barrier");
                cmd(s,"fill -13 101 -13 12 107 -13 minecraft:barrier");
                cmd(s,"fill -13 101 12 12 107 12 minecraft:barrier");
                cmd(s,"gamerule minecraft:keep_inventory true");
                cmd(s,"setworldspawn 0 101 0");cmd(s,"gamerule minecraft:natural_health_regeneration false");
                cmd(s,"gamerule minecraft:immediate_respawn true");cmd(s,"gamerule minecraft:spawn_mobs false");
                int speed=Integer.getInteger("sword.speed",1);
                cmd(s,"tick rate "+20*speed);initialized=true;write("ready","ready");return;
            }
            if(round==null){
                Path req=dir.resolve("request");if(!Files.exists(req))return;
                var fields=Files.readString(req).strip().split("\t");
                var a=s.getPlayerList().getPlayerByName(fields[1]);var b=s.getPlayerList().getPlayerByName(fields[2]);
                if(a==null||b==null||!a.isAlive()||!b.isAlive())return;
                double angle=Double.parseDouble(fields[3]),gap=Double.parseDouble(fields[4]);
                double x=Math.cos(angle)*gap/2,z=Math.sin(angle)*gap/2,yaw=Math.toDegrees(angle)-90;
                prepare(s,a,-x,-z,yaw);prepare(s,b,x,z,yaw+180);
                long start=System.currentTimeMillis()+1600/Integer.getInteger("sword.speed",1);
                write("A",fields[0]+"\t"+fields[2]+"\t"+start+"\t"+fields[5]);
                write("B",fields[0]+"\t"+fields[1]+"\t"+start+"\t"+fields[6]);
                trace=new PrintWriter(Files.newBufferedWriter(dir.resolve(fields[0]+"-server.tsv")));
                trace.println("tick\thpA\thpB\tax\tay\taz\tbx\tby\tbz\tchargeA\tchargeB\tsprintA\tsprintB\thurtA\thurtB\tepochMs\tdamageCooldownA\tdamageCooldownB");
                contacts=new PrintWriter(Files.newBufferedWriter(dir.resolve(fields[0]+"-contacts.tsv")));
                contacts.println("tick\tattacker\tdefender\tcritical\tsweep\tstrong\tboosted\tdefenderHP\thurt\tpreDamageCooldown\tcharge\tepochMs");
                round=fields;tick=-32;Files.delete(req);return;
            }
            ServerPlayer a=s.getPlayerList().getPlayerByName(round[1]),b=s.getPlayerList().getPlayerByName(round[2]);
            if(a==null||b==null){throw new IOException("Player left during "+round[0]);}
            float ha=a.getHealth(),hb=b.getHealth();
            trace.printf(Locale.ROOT,"%d\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%b\t%b\t%d\t%d\t%d\t%d\t%d%n",tick,ha,hb,a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ(),a.getAttackStrengthScale(0),b.getAttackStrengthScale(0),a.isSprinting(),b.isSprinting(),a.hurtTime,b.hurtTime,System.currentTimeMillis(),a.damageCooldownTime,b.damageCooldownTime);
            if(ha<=0||hb<=0||++tick>=Model.LIMIT){
                trace.close();contacts.close();write("A","");write("B","");write(round[0]+".result",ha+"\t"+hb+"\t"+tick);round=null;
            }
        }catch(Exception e){throw new IllegalStateException("Sword referee",e);}
    }
}
