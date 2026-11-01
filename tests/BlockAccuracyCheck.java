import lab.BlockAccuracy;

public final class BlockAccuracyCheck {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        var b=new BlockAccuracy();check(b.state().get("accuracy_percent")==null);
        b.update(true);b.update(true);b.update(false); // Miss, repeated native use is not a new attempt.
        b.update(true);b.damageReduced();b.damageReduced();b.update(false); // Two hits, one successful attempt.
        check(b.state().get("attempts").equals(2));check(b.state().get("successful_attempts").equals(1));
        check(b.state().get("accuracy_percent").equals(50.0));
        b.update(true); // An attempt interrupted by death/duel end still counts.
        check(b.state().get("attempts").equals(3));b.update(false);
        b.damageReduced();check(b.state().get("successful_attempts").equals(2));
        System.out.println("PASS block attempts, misses, multiple hits per hold, interrupted guard and empty denominator");
    }
}
