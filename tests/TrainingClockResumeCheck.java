package lab;

/** A sleep gap between wall time and nanoTime must not leave a click timer on the old rate. */
public final class TrainingClockResumeCheck {
    public static void main(String[] args) {
        if(System.getProperty("lab.trainingWall")!=null){
            long wall=Long.getLong("lab.trainingWall"),next=Long.getLong("lab.trainingNextWall");
            if(TrainingClock.at(wall).game(wall)!=Long.getLong("lab.trainingGameNs") ||
               TrainingClock.at(next).rate!=Double.parseDouble(System.getProperty("lab.trainingNextSpeed")))
                throw new AssertionError("Rejoining bot lost the server clock plan");
        }
        long start=TrainingClock.EPOCH+10_000L;
        long mono=5_000_000_000L;
        long change=start+3_600_000L;
        long after=change+1_000L;
        TrainingClock.synchronize(start,1_000_000_000L,.3,change,1);
        long before=TrainingClock.gameNanos(mono,mono+1_000_000_000L,start+1000);
        long next=TrainingClock.gameNanos(mono+500_000_000L,mono+1_000_000_000L,start+1000);
        if(next-before!=150_000_000L)throw new AssertionError("Incorrect 0.3x pacing");
        // A sleeping host advances the wall clock by an hour, while the process timer advances two seconds.
        long now=mono+2_000_000_000L;
        long first=TrainingClock.gameNanos(now,now,after);
        long second=TrainingClock.gameNanos(now+142_857_143L,now+142_857_143L,after+143);
        if(second-first!=142_857_143L)throw new AssertionError("Seven-CPS interval at 1x");
        long prior=TrainingClock.gameNanos(now-1_100_000_000L,now,after);
        if(first-prior!=1_030_000_000L)throw new AssertionError("Delayed event across the rate change");
        TrainingClock.synchronize(change,TrainingClock.at(change).game(change),1,after+500,.5);
        long later=TrainingClock.gameNanos(now+1_500_000_000L,now+1_500_000_000L,after+1500);
        if(later-first!=1_000_000_000L)throw new AssertionError("Live 1x to 0.5x change");
        System.out.println("Sleep, 0.3x to 1x, 1x to 0.5x and seven-CPS pacing verified.");
    }
}
