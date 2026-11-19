package lab;
import java.util.*;

/** Ordinary hotbar items + bounded actions, active only inside a private replay session. */
public final class ReplayToolbar {
    private static final String[] ITEMS={"clock","blaze_rod","stick","arrow","feather","compass","paper","book","nether_star"};
    private static final String[] NAMES={"§aPlay / Pause","§eEvent §7(L: previous, R: next)","§eRecorded tick §7(L: previous, R: next)",
        "§eSeek §7(L: -1s, R: +1s)","§bSpeed §7(L: slower, R: faster)","§6Viewpoint §7(L: previous, R: next)",
        "§bSettings §7(camera & ghosts)","§6Combo §7(L: previous, R: next)","§cExit replay §7(right-click)"};
    private static final double[] SPEEDS={.05,.1,.25,.5,1,1.5,2,4};
    static void equip(Object viewer,ServerReplay.Session s){
        Object inv=R.get(viewer,"inventory");s.savedInventory=R.call(inv,"writeToNBT",R.construct("net.minecraft.nbt.NBTTagList"));s.savedSlot=(int)R.num(inv,"currentItem");
        Referee.vanilla("clear "+s.viewer);Referee.vanilla("gamemode 2 "+s.viewer);
        Object caps=R.get(viewer,"capabilities");R.set(caps,"allowFlying",true);R.set(caps,"isFlying",true);R.set(caps,"disableDamage",true);R.call(viewer,"sendPlayerAbilities");
        for(int i=0;i<9;i++){Object item=ServerReplay.namedItem(ITEMS[i]);R.call(item,"setStackDisplayName",NAMES[i]);R.call(inv,"setInventorySlotContents",i,item);}
        select(viewer,inv,0);R.call(viewer,"sendContainerToPlayer",R.get(viewer,"inventoryContainer"));
        Referee.message(viewer,"§bReplay hotbar §7· §f1 play/pause · 2 event · 3 tick · 4 seek · 5 speed · 6 POV · 7 settings · 8 combo · 9 exit. §7Left goes back, right goes forward.");
    }
    private static void select(Object viewer,Object inv,int slot){R.set(inv,"currentItem",slot);ServerReplay.send(viewer,R.construct("net.minecraft.network.play.server.S09PacketHeldItemChange",slot));}
    static void restore(Object viewer,ServerReplay.Session s){if(s.savedInventory!=null){Object inv=R.get(viewer,"inventory");R.call(inv,"readFromNBT",s.savedInventory);select(viewer,inv,s.savedSlot);R.call(viewer,"sendContainerToPlayer",R.get(viewer,"inventoryContainer"));}}
    public static boolean intercept(Object handler,Object packet){
        if(Referee.server==null||!(Boolean)R.call(Referee.server,"isCallingFromMinecraftThread"))return false;
        Object player=R.get(handler,"playerEntity");ServerReplay.Session s=ServerReplay.sessions.get(R.name(player));if(s==null)return false;
        String kind=packet.getClass().getName();if(kind.equals("ja")||kind.equals("iy")){
            long now=System.currentTimeMillis();if(now-s.lastToolbar>=180){s.lastToolbar=now;try{use(player,s,(int)R.num(R.get(player,"inventory"),"currentItem"),kind.equals("ja"));}catch(Exception e){Referee.message(player,"§c"+e.getMessage());}}
        }else if((kind.equals("ir")||kind.equals("ik")))R.call(player,"sendContainerToPlayer",R.get(player,"inventoryContainer"));
        return true;
    }
    static void use(Object player,ServerReplay.Session s,int slot,boolean right)throws Exception{
        String command;
        switch(slot){
            case 0:command=s.clock.paused()?"play":"pause";break;
            case 1:command=right?"event next":"event prev";break;
            case 2:command=right?"tick next":"tick prev";break;
            case 3:command=right?"forward 1":"back 1";break;
            case 4:{double target=right?4:.05;for(double speed:SPEEDS)if(right&&speed>s.clock.speed()+1e-6){target=speed;break;}else if(!right&&speed<s.clock.speed()-1e-6)target=speed;command="speed "+target;break;}
            case 5:{List<String> sources=new ArrayList<>(List.of("server"));for(String source:List.of("a","b"))if(s.data.pov.available(source))sources.add(source);command="pov "+cycle(sources,s.source,right?1:-1);break;}
            case 6:command="menu";break;
            case 7:{int index=-1;double at=s.clock.position();if(right){for(int i=0;i<s.combos.size();i++)if(s.combos.get(i).seekMs()>at+100){index=i;break;}}else{for(int i=0;i<s.combos.size();i++)if(s.combos.get(i).seekMs()<at-100)index=i;}
                if(index<0){Referee.message(player,"§7No "+(right?"later":"earlier")+" combo.");return;}command="combo "+s.data.id+" "+(index+1);break;}
            case 8:if(!right)return;command="stop";break;
            default:return;
        }
        ServerReplay.command(player,("replay "+command).split(" "));
    }
    private static String cycle(List<String> values,String current,int d){return values.get(Math.floorMod(values.indexOf(current)+d,values.size()));}
}
