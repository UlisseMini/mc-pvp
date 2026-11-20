package sword;

import static sword.Fighter.*;

/** Fixed evaluation opponents. Training does not import these controls. */
final class Controls {
    static Model.Controller get(String name)throws java.io.IOException{
        if(!name.equals("wtap")&&!name.equals("hold"))return new Fighter(read(java.nio.file.Path.of(name)))::act;
        return new Model.Controller(){
            boolean release;
            public Keys act(View v){
                double distance=Math.hypot(v.tx()-v.x(),v.tz()-v.z());
                boolean click=v.charge()>=.9 && distance<=3.18 && Math.abs(angle(targetYaw(v)-v.yaw()))<12;
                var k=new Keys(release?0:1,0,true,false,click,0);
                release=name.equals("wtap")&&click;return k;
            }
        };
    }
}
