package lab;

/** Known distances on the recorded MMC grid, including inset rows and travel across a cell corner. */
public final class SumoInputGeometryCheck {
    private static void expect(double actual,double expected){
        if(Math.abs(actual-expected)>1e-9)throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args){
        expect(SumoInputs.clearance(.5,.5,1,0),4);
        expect(SumoInputs.clearance(6.75,.5,1,0),.25);
        expect(SumoInputs.clearance(-5.75,.5,-1,0),.25);
        expect(SumoInputs.clearance(2.75,6.5,1,0),.25);
        expect(SumoInputs.clearance(3.5,6.5,-1,0),0);
        expect(SumoInputs.clearance(6.5,2.5,1,1),Math.sqrt(.5));
        expect(SumoInputs.clearance(6,0,-1,0),4);
        expect(SumoInputs.clearance(6.5,0,0,0),4);
    }
}
