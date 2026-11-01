import java.util.*;

/** Recorded scheduler adapter. Expected packet values are assertions, never state corrections. */
final class PeriodicComboTransport {
    static final class Expected implements Cloneable {
        Expected deepCopy(){try{return (Expected)super.clone();}catch(CloneNotSupportedException impossible){throw new AssertionError(impossible);}}
        int kind,packetId;double send,delivery,x,y,vx,vy,z,vz;int flag;boolean generated;
        Expected(String[] p){kind=Integer.parseInt(p[1]);send=Double.parseDouble(p[2]);delivery=Double.parseDouble(p[3]);x=Double.parseDouble(p[4]);y=Double.parseDouble(p[5]);vx=Double.parseDouble(p[6]);vy=Double.parseDouble(p[7]);flag=Integer.parseInt(p[8]);z=Double.parseDouble(p[9]);vz=Double.parseDouble(p[10]);packetId=Integer.parseInt(p[11]);}
    }
    final List<Expected>[] routes=new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
    final int[] next=new int[4];
    static int route(int k){return k==0||k==2||k==3?0:k==1||k==7||k==9?1:k==5||k==8||k==10||k==12||k==14?2:3;}
    PeriodicComboTransport deepCopy(){PeriodicComboTransport copy=new PeriodicComboTransport();for(int r=0;r<4;r++){copy.next[r]=next[r];for(Expected e:routes[r])copy.routes[r].add(e.deepCopy());}return copy;}
    void add(String[] p){Expected e=new Expected(p);routes[route(e.kind)].add(e);}
    void seal(){for(List<Expected> es:routes)es.sort(Comparator.comparingDouble(e->e.send));}
    static void check(String name,double actual,double expected,Expected e){if(!Double.isNaN(expected)&&Double.doubleToLongBits(actual)!=Double.doubleToLongBits(expected))throw new IllegalStateException("NETWORK_PAYLOAD "+name+" kind="+e.kind+" nativeSend="+e.send+" predicted="+actual+" native="+expected);}
    void emit(PeriodicComboFightModel.Packet p){
        int r=route(p.kind);if(next[r]>=routes[r].size())throw new IllegalStateException("NETWORK_EXTRA kind="+p.kind+" due="+p.due);
        Expected e=routes[r].get(next[r]++);
        if(p.kind!=e.kind)throw new IllegalStateException("NETWORK_SEQUENCE predicted="+p.kind+" native="+e.kind+" nativeSend="+e.send);
        if(p.packetId!=e.packetId)throw new IllegalStateException("NETWORK_SUBTYPE kind="+e.kind+" nativeSend="+e.send+" predicted="+p.packetId+" native="+e.packetId+" ground="+p.ground+" nativeGround="+e.flag);
        check("x",p.x,e.x,e);check("y",p.y,e.y,e);check("vx",p.vx,e.vx,e);check("vy",p.vy,e.vy,e);check("z",p.z,e.z,e);check("vz",p.vz,e.vz,e);
        if(e.flag>=0&&(p.kind==5||p.kind==6?p.ground:p.flag)!=(e.flag==1))throw new IllegalStateException("NETWORK_FLAG kind="+p.kind+" nativeSend="+e.send+" predicted="+p.flag+" native="+e.flag);
        e.generated=true;p.due=e.delivery;
    }
    void assertDeliveredBy(double t){for(List<Expected> es:routes)for(Expected e:es)if(e.delivery<t-1e-6&&!e.generated)throw new IllegalStateException("NETWORK_MISSING kind="+e.kind+" nativeSend="+e.send+" delivery="+e.delivery+" checked="+t);}
}
