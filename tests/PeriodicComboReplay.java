import java.io.*;

/** Recorded input/clock replay adapter. Later native states are never inputs. */
public final class PeriodicComboReplay {
    static PeriodicComboFightModel.State initialState(String[] p, PeriodicComboFightModel.Config c, PeriodicComboTransport transport) {
        double ax=Double.parseDouble(p[1]),bx=Double.parseDouble(p[2]);double top=p.length>28?Double.parseDouble(p[28]):4;
        double az=p.length>34?Double.parseDouble(p[33]):0,bz=p.length>34?Double.parseDouble(p[34]):0;
        var s=new PeriodicComboFightModel.State(ax,az,bx,bz,top);
        if(p.length>30){
            c.sumo=p[27].equals("1");c.rangeMmc=p[30].equals("3");c.recordedMmc=c.rangeMmc||p[30].equals("2");c.mmc=c.recordedMmc||p[30].equals("1");
            if(p.length>31)c.trackerAFirst=p[31].equals("1");
            if(c.sumo){
                if(p.length>32)c.sumoDiameter=Integer.parseInt(p[32]);
                c.sumoArena(Double.parseDouble(p[29]),top);c.solidEdge();
            }
        }
        c.ownRtt=Double.parseDouble(p[3]);c.otherRtt=Double.parseDouble(p[4]);c.defenderReach=Double.parseDouble(p[5]);PeriodicComboFightModel.defenderReach=c.defenderReach;
        s.serverTicks=Integer.parseInt(p[6]);for(int i=0;i<2;i++){PeriodicComboFightModel.Body b=i==0?s.a:s.b;int k=7+6*i;b.reportAge=Integer.parseInt(p[k]);b.reportX=Double.parseDouble(p[k+1]);b.reportY=Double.parseDouble(p[k+2]);b.reportZ=Double.parseDouble(p[k+3]);b.reportYaw=Float.parseFloat(p[k+4]);b.reportPitch=Float.parseFloat(p[k+5]);}s.lastDamageA=(int)Math.round(Double.parseDouble(p[19])*(c.sumo?2:1));s.lastDamageB=(int)Math.round(Double.parseDouble(p[20])*(c.sumo?2:1));if(p.length>22){s.teleportAgeA=Integer.parseInt(p[21]);s.teleportAgeB=Integer.parseInt(p[22]);}if(p.length>25){c.kbScale=Double.parseDouble(p[23]);c.verticalScale=Double.parseDouble(p[24]);c.hitTicks=Integer.parseInt(p[25]);s.freshThreshold=c.hitTicks;}
        // Optional trailing field preserves legacy INIT tapes' native .1 lift.
        c.sprintVertical=p.length>26?Double.parseDouble(p[26]):.1;
        if(transport!=null){transport.seal();s.transport=transport;c.deferMovementSend=false;}
        return s;
    }

    static void step(PeriodicComboFightModel.State s, PeriodicComboFightModel.Config c, String[] p,
                     double t, String kind, boolean fromA) {
        if(kind.startsWith("APPLY"))PeriodicComboFightModel.receive(s,t,fromA);
        else if(kind.startsWith("INPUT"))PeriodicComboFightModel.replayInput(s,c,fromA,Integer.parseInt(p[2]),Float.parseFloat(p[3]),Float.parseFloat(p[4]),p[5].equals("1"),t);
        else if(kind.startsWith("MOVE"))PeriodicComboFightModel.replayMove(s,c,fromA,Integer.parseInt(p[2]),Float.parseFloat(p[4]),Float.parseFloat(p[3]),p.length>5&&p[5].equals("1"),t);
        else if(kind.startsWith("INTERP")){(fromA?s.ab:s.ba).step();PeriodicComboFightModel.audit(s);}
        else if(kind.startsWith("FRAME")){if(s.transport==null&&Boolean.getBoolean("periodic.frame_drain"))PeriodicComboFightModel.receive(s,t,fromA);}
        else if(kind.startsWith("TICKEND"))PeriodicComboFightModel.replayFlush(s,c,fromA,t);
        else if(kind.equals("SERVER_APPLY"))PeriodicComboFightModel.serverDrain(s,c,t);
        else if(kind.equals("SERVER_DRAIN"))PeriodicComboFightModel.serverDrain(s,c,t);
        else if(kind.equals("SERVER_TRACK"))PeriodicComboFightModel.serverTrack(s,c,t);
        else if(kind.equals("SERVER_ENTITY"))PeriodicComboFightModel.serverEntity(s);
        else if(kind.equals("SERVER_OBSERVE")){}
        else if(kind.equals("SERVER_END"))PeriodicComboFightModel.serverEnd(s);
        else if(kind.equals("SERVER"))PeriodicComboFightModel.serverTick(s,c,t);
        else throw new IllegalArgumentException(String.join("\t",p));
    }

    public static void main(String[] args)throws Exception{
        BufferedReader r=new BufferedReader(new InputStreamReader(System.in));String line;
        PeriodicComboFightModel.Config c=new PeriodicComboFightModel.Config();c.forceContacts=false;c.deferMovementSend=true;
        PeriodicComboFightModel.State s=null;PeriodicComboTransport transport=null;
        System.out.println("time_ms\tevent\ta_x\ta_y\ta_vx\ta_vy\tb_x\tb_y\tb_vx\tb_vy\ta_view_x\ta_view_y\tb_view_x\tb_view_y\tserver_ax\tserver_ay\tserver_bx\tserver_by\tfresh_a\tfresh_b\timmune_attempts\town_range\tdefender_range\ta_z\ta_vz\tb_z\tb_vz\ta_view_z\tb_view_z\tserver_az\tserver_bz\ta_ray\tb_ray\taccepted_a\taccepted_b\tserver_a_vx\tserver_a_vy\tserver_a_vz\tserver_a_h\tserver_a_fall\tserver_a_last_damage\tserver_a_sprint\ta_ground\ta_sprint\ta_head_yaw\tserver_b_vx\tserver_b_vy\tserver_b_vz\tserver_b_h\tserver_b_fall\tserver_b_last_damage\tserver_b_sprint\tb_ground\tb_sprint\tb_head_yaw");
        while((line=r.readLine())!=null){if(line.isEmpty()||line.startsWith("#"))continue;String[] p=line.split("\\t");
            if(p[0].equals("NETWORK")){if(transport==null)transport=new PeriodicComboTransport();transport.add(p);continue;}
            if(p[0].equals("INIT")){
                s=initialState(p,c,transport);continue;
            }
            double t=Double.parseDouble(p[0]);String kind=p[1];boolean fromA=kind.endsWith("A");
            if(transport!=null)transport.assertDeliveredBy(t);
            step(s,c,p,t,kind,fromA);
            if(!kind.startsWith("FRAME"))System.out.println(t+"\t"+kind+"\t"+s.a.q.x()+"\t"+s.a.q.y()+"\t"+s.a.q.vx()+"\t"+s.a.q.vy()+"\t"+s.b.q.x()+"\t"+s.b.q.y()+"\t"+s.b.q.vx()+"\t"+s.b.q.vy()+"\t"+s.ab.x+"\t"+s.ab.y+"\t"+s.ba.x+"\t"+s.ba.y+"\t"+s.serverAx+"\t"+s.serverAy+"\t"+s.serverBx+"\t"+s.serverBy+"\t"+s.hits+"\t"+s.incomingHits+"\t"+s.immuneAttempts+"\t"+PeriodicComboFightModel.own(s)+"\t"+PeriodicComboFightModel.their(s)+"\t"+s.a.q.z()+"\t"+s.a.q.vz()+"\t"+s.b.q.z()+"\t"+s.b.q.vz()+"\t"+s.ab.z+"\t"+s.ba.z+"\t"+s.serverAz+"\t"+s.serverBz+"\t"+PeriodicComboFightModel.ray(s.a,s.ab)+"\t"+PeriodicComboFightModel.ray(s.b,s.ba)+"\t"+s.acceptedA+"\t"+s.acceptedB+"\t"+s.serverA.vx()+"\t"+s.serverA.vy()+"\t"+s.serverA.vz()+"\t"+s.ownResistant+"\t"+(double)s.fallA+"\t"+(s.lastDamageA/(c.sumo?2.0:1.0))+"\t"+(s.serverSprint?1:0)+"\t"+(s.a.q.onGround()?1:0)+"\t"+(s.a.sprint?1:0)+"\t"+(double)s.a.headYaw+"\t"+s.serverB.vx()+"\t"+s.serverB.vy()+"\t"+s.serverB.vz()+"\t"+s.resistant+"\t"+(double)s.fallB+"\t"+(s.lastDamageB/(c.sumo?2.0:1.0))+"\t"+(s.serverSprintB?1:0)+"\t"+(s.b.q.onGround()?1:0)+"\t"+(s.b.sprint?1:0)+"\t"+(double)s.b.headYaw);
        }
    }
}
