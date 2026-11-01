import java.io.*;

/** Isolated C03 latent-motion replay; no world/server is started. */
public final class PeriodicComboServerReplay {
    public static void main(String[] args)throws Exception{
        BufferedReader r=new BufferedReader(new InputStreamReader(System.in));
        PeriodicComboFightModel.State s=null;String line;
        while((line=r.readLine())!=null){String[] p=line.split("\\t");
            if(p[0].equals("INIT")){
                s=new PeriodicComboFightModel.State(Double.parseDouble(p[1]));s.serverSprintB=true;s.serverBx=Double.parseDouble(p[1]);s.serverBy=Double.parseDouble(p[2]);
                s.serverB=PeriodicComboFightModel.state(s.serverBx,s.serverBy,Double.parseDouble(p[3]),Double.parseDouble(p[4]),Boolean.parseBoolean(p[5]));
            }else if(p[0].equals("MOVE")){
                boolean moving=Boolean.parseBoolean(p[4]);
                PeriodicComboFightModel.serverMoveB(s,new PeriodicComboFightModel.Packet(1,0,moving?Double.parseDouble(p[1]):s.serverBx,moving?Double.parseDouble(p[2]):s.serverBy,90,0,Boolean.parseBoolean(p[3]),0));
            }else if(!p[0].equals("READ"))throw new IllegalArgumentException(line);
            System.out.println(s.serverB.vx()+"\t"+s.serverB.vy()+"\t"+s.serverB.onGround()+"\t"+s.serverBx+"\t"+s.serverBy);
        }
    }
}
