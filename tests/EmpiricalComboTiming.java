import java.util.Arrays;

/** A frozen, correlated timing tape, sampled by generated send time, never packet
 * kind/payload/count. Route order: A uplink, B uplink, A downlink, B downlink.
 * Residuals end at network receipt, not application. Native/client/server task
 * waits belong to PeriodicComboClock's independently supplied drain events. */
public final class EmpiricalComboTiming implements PeriodicComboFightModel.PacketTiming {
    private final double[][] times,residuals;
    private final double coverageEndMs;
    private final double[] lastSend,lastArrival;
    private final long[] fifoClamps;

    public EmpiricalComboTiming(double[][] times,double[][] residuals,double coverageEndMs){
        if(times==null||residuals==null||times.length!=4||residuals.length!=4
                ||!Double.isFinite(coverageEndMs)||coverageEndMs<0)
            throw new IllegalArgumentException("Four timing routes and finite nonnegative coverage required");
        this.times=new double[4][];this.residuals=new double[4][];this.coverageEndMs=coverageEndMs;
        for(int route=0;route<4;route++){
            if(times[route]==null||residuals[route]==null||times[route].length==0
                    ||times[route].length!=residuals[route].length)
                throw new IllegalArgumentException("Each route needs matching nonempty time/residual arrays");
            this.times[route]=times[route].clone();this.residuals[route]=residuals[route].clone();
            double previous=Double.NEGATIVE_INFINITY;
            for(int i=0;i<times[route].length;i++){
                double time=times[route][i];
                if(!Double.isFinite(time)||time<=previous||time>coverageEndMs||!Double.isFinite(residuals[route][i]))
                    throw new IllegalArgumentException("Timing knots must be finite, increasing and within coverage");
                previous=time;
            }
            if(times[route][0]>0)throw new IllegalArgumentException("Every route must cover send time zero");
        }
        lastSend=new double[4];lastArrival=new double[4];fifoClamps=new long[4];
        Arrays.fill(lastSend,Double.NEGATIVE_INFINITY);Arrays.fill(lastArrival,Double.NEGATIVE_INFINITY);
    }
    private EmpiricalComboTiming(EmpiricalComboTiming source){
        // Arrays are privately immutable; only route watermarks/counters are mutable.
        times=source.times;residuals=source.residuals;coverageEndMs=source.coverageEndMs;
        lastSend=source.lastSend.clone();lastArrival=source.lastArrival.clone();fifoClamps=source.fifoClamps.clone();
    }
    @Override public EmpiricalComboTiming deepCopy(){return new EmpiricalComboTiming(this);}
    @Override public double arrival(int route,double generatedSendMs,double nominalHalfRttMs){
        if(route<0||route>=4||!Double.isFinite(generatedSendMs)||generatedSendMs<0||generatedSendMs>coverageEndMs
                ||!Double.isFinite(nominalHalfRttMs)||nominalHalfRttMs<0)
            throw new IllegalArgumentException("Generated send outside empirical timing coverage or invalid route/RTT");
        if(generatedSendMs<lastSend[route])throw new IllegalStateException("Generated route send time moved backwards");
        int index=Arrays.binarySearch(times[route],generatedSendMs);
        if(index<0)index=-index-2;
        if(index<0)throw new IllegalStateException("No empirical timing sample before generated send");
        double delay=nominalHalfRttMs+residuals[route][index];
        double arrival=generatedSendMs+delay;
        if(delay<0||!Double.isFinite(arrival))throw new IllegalStateException("Empirical residual implies invalid network delay");
        if(arrival<lastArrival[route]){arrival=lastArrival[route];fifoClamps[route]++;}
        lastSend[route]=generatedSendMs;lastArrival[route]=arrival;
        return arrival;
    }
    public long[] fifoClamps(){return fifoClamps.clone();}
    public double coverageEndMs(){return coverageEndMs;}
}
