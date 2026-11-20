package sword;

/** A respawn can replace the server player before the next referee tick. A lethal contact is final. */
final class RoundOutcome {
    private boolean finished;
    private float healthA, healthB;

    void reset() { finished=false; }
    boolean finished() { return finished; }
    void contact(boolean attackerIsA, float attackerHealth, float defenderHealth) {
        if(finished || defenderHealth>0)return;
        healthA=attackerIsA?attackerHealth:0;
        healthB=attackerIsA?0:attackerHealth;
        finished=true;
    }
    float healthA(float sampled) { return finished?healthA:sampled; }
    float healthB(float sampled) { return finished?healthB:sampled; }
}
