/** Primary metric is order-sensitive even when packets share one server drain. */
public final class ComboMetricCheck {
    public static void main(String[]args){
        var config=new PeriodicComboFightModel.Config();config.defenderReach=3;
        var timing=new PeriodicComboClock.Timing();
        for(boolean ownFirst:new boolean[]{true,false}){
            var score=new ComboCycleSearch.Score(config,timing);var state=new PeriodicComboFightModel.State(0,0,6,0);
            state.hits=1;state.lastFreshOrderA=1;score.event(100,"SERVER_DRAIN",state);
            state.hits=2;state.acceptedB=1;state.lastFreshOrderA=ownFirst?2:3;state.lastAcceptedOrderB=ownFirst?3:2;
            score.event(200,"SERVER_DRAIN",state);
            if(score.freshBeforeReturn!=(ownFirst?2:1))throw new AssertionError("First-return order");
            if(score.longestUnanswered!=(ownFirst?2:1))throw new AssertionError("Run order");
            state.hits=3;state.lastFreshOrderA=4;score.event(300,"SERVER_DRAIN",state);
            if(score.freshBeforeReturn!=(ownFirst?2:1))throw new AssertionError("Count changed after return");
            if(!score.firstReturn||score.firstReturnAt!=200)throw new AssertionError("Return boundary");
        }
        System.out.println("PASS primary first-return and unanswered-run metrics preserve within-drain order");
    }
}
