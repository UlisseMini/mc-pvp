import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.Json;

/** Test prospective packet timing changes from an unchanged bilateral cut. */
final class BoxingReentryStageCheck {
    private static final class FirstRecoil extends DuelEnv.Player {
        final double anchor, factor;
        DuelEnv.Game game;
        List<Number> step=List.of();

        FirstRecoil(int rtt,double anchor,double factor) {
            super("wtap",0,rtt,0);
            this.anchor=anchor;
            this.factor=factor;
        }

        @Override public PeriodicComboClock.Control movement(PeriodicComboClock.Control issued) {
            var state=game.clock.state;
            var body=game.first?state.b:state.a;
            var fresh=game.first?state.lastFreshB:state.lastFreshA;
            var q=body.q;
            if (step.isEmpty() && fresh!=null && fresh.time()==anchor && q.vy()==fresh.y()) {
                double observed=q.vx();
                if (observed!=fresh.x() && observed!=.6*fresh.x())
                    throw new IllegalStateException("Expected full or once-braked first recoil");
                if (Double.isFinite(factor))
                    body.q=PeriodicComboFightModel.motion(q,factor*fresh.x(),q.vy(),factor*fresh.z());
                step=List.of(game.clock.time(),fresh.x(),observed,body.q.vx());
            }
            return issued;
        }
    }

    public static void main(String[] args) throws Exception {
        double anchor=Double.parseDouble(System.getProperty("lab.reentryStage.recoilAnchor","NaN"));
        double factor=Double.parseDouble(System.getProperty("lab.reentryStage.recoilFactor","NaN"));
        var recoil=new FirstRecoil(Integer.parseInt(args[0]),anchor,factor);
        var state=Double.isFinite(anchor)?BoxingReentryProtection.checkpoint(args,recoil,game->recoil.game=game):
                BoxingReentryProtection.checkpoint(args);
        var result=new LinkedHashMap<String,Object>();
        if (Boolean.getBoolean("lab.reentryStage.snapshot")) result.put("cut",snapshot(state));
        if (Double.isFinite(anchor)) {
            if (recoil.step.isEmpty()) throw new IllegalStateException("Expected first recoil movement before the cut");
            result.put("firstRecoil",recoil.step);
        }
        var baseline=run(state,args);
        BoxingMirrorForensics.loadPacketModel(state.game().clock.state,Path.of(args[8]),"raw-queue");
        var adjusted=run(state,args);
        result.putAll(Map.of("clock",state.env().clockId,"at",state.cut().time(),"older",baseline,"calibrated",adjusted));
        System.out.println(Json.encode(result));
    }

    private static Map<String,Object> snapshot(BoxingReentryProtection.Checkpoint cut) {
        var s=cut.game().clock.state;
        var view=cut.game().first?s.ba:s.ab;
        return Map.of("ownView",BoxingNaturalReentry.legal(cut.scout(),cut.cut()),
                "defenderView",List.of(view.x,view.y,view.z,view.gx,view.gy,view.gz,view.left),
                "queues",List.of(packets(s.packets),packets(s.outboundA),packets(s.outboundB)));
    }

    private static List<List<Number>> packets(List<PeriodicComboFightModel.Packet> packets) {
        return packets.stream().map(p->List.<Number>of(p.sequence,p.kind,p.packetId,p.due,p.ready,p.x,p.y,p.z,p.vx,p.vy,p.vz)).toList();
    }

    private static List<Map<String,Object>> run(BoxingReentryProtection.Checkpoint state,String[] args) {
        if (args.length==9) return BoxingReentryProtection.widen(state.env(),state.game(),state.scout(),state.cut());
        if (args.length!=13) throw new IllegalArgumentException("Expected own and defender phase and upload adjustments");
        var timing=new BoxingLandingReentry.Timing(Integer.parseInt(args[9]),Integer.parseInt(args[10]),
                Integer.parseInt(args[11]),Integer.parseInt(args[12]));
        return BoxingReentryProtection.widen(state.env(),state.game(),state.scout(),state.cut(),List.of(timing));
    }
}
