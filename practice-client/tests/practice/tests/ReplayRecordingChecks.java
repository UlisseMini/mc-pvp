package practice.tests;

import practice.core.replay.ReplayRecording;
import practice.core.replay.MmcSumoReplay;
import practice.core.replay.ReplayVisuals;
import practice.core.replay.ReplayInputs;
import practice.core.replay.ServerReplayInputs;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import static practice.core.JsonData.map;

/** Pure archive fixtures, with a caller-supplied parser and no Minecraft/Gson. */
public final class ReplayRecordingChecks {
    private static int checks;
    private interface Action {void run()throws Exception;}
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void fails(Action action,String fragment)throws Exception{checks++;try{action.run();}catch(IOException e){if(e.getMessage().contains(fragment))return;throw e;}throw new AssertionError("Expected explicit limit/failure: "+fragment);}
    private static Map<String,Object> row(String type,int ms,Map<String,Object> data){return map("type",type,"time_ms",1000L+ms,"monotonic_ns",1000000000L+ms*1000000L,"data",data);}
    private static Map<String,Object> wire(boolean in,int ms,long id,byte... bytes){return row("packet_wire",ms,map("packet",map("direction",in?"in":"out","kind","PLAY","packet_id",id),"base64",Base64.getEncoder().encodeToString(bytes)));}
    private static Map<String,Object> handler(int ms,long id,boolean main){return row("packet_handler_enter",ms,map("packet_id",id,"main_thread",main));}
    private static Map<String,Object> pose(int ms,int id){return row("tick_end",ms,map("self",map("id",id,"pos",new ArrayList<Object>(Arrays.<Object>asList(0.,4.,0.))),"entities",Arrays.asList(map("id",8,"kind","EntityOtherPlayerMP","pos",Arrays.asList(2.,4.,0.))),"ray",map("entity",8)));}
    private static void gzip(Path file,String... tokens)throws IOException{try(OutputStream out=new GZIPOutputStream(Files.newOutputStream(file))){for(String token:tokens)out.write((token+"\n").getBytes(StandardCharsets.UTF_8));}}
    private static boolean warns(ReplayRecording r,String text){for(String w:r.warnings)if(w.contains(text))return true;return false;}
    private static ReplayRecording.Mark mark(ReplayRecording r,String kind,int ordinal){for(ReplayRecording.Mark m:r.timeline)if(m.kind.equals(kind)&&ordinal--==0)return m;throw new AssertionError("No timeline mark "+kind);}
    private static ReplayRecording.Entry entry(Path p){return new ReplayRecording.Entry("fixture","Fixture","example.invalid",1000,0,p,false,null);}
    private static void serverReplayInputs(Path root)throws Exception{
        Map<String,Map<String,Object>> rows=new HashMap<>();
        rows.put("w",row("key",-10,map("key",17,"down",true)));
        rows.put("mouse",row("mouse",25,map("button",0,"down",true)));
        rows.put("mouseUp",row("mouse",55,map("button",0,"down",false)));
        rows.put("gui",row("tick_end",100,map("screen","GuiChat")));
        rows.put("botTick",row("tick_end",10,map("self",map("movement_input",map("moveForward",-1,"moveStrafe",1,"jump",true)))));
        rows.put("botStop",row("tick_end",100,map("self",map("movement_input",map("moveForward",0,"moveStrafe",0,"jump",false)))));
        rows.get("botTick").put("client_tick",41);
        rows.get("botStop").put("client_tick",42);
        rows.put("applied",map("type","duel_network","time",800L,"tick",41,"action",18,"proposed",4));
        rows.put("released",map("type","duel_network","time",850L,"tick",42,"action",5));
        rows.put("ctrl",row("tick_end",5,map("sprint_key_code",-97,"sprint_key_down",true)));
        rows.put("packet",row("packet_wire",48,map("packet",map("direction","out","kind","C0BPacketEntityAction"),"base64","CwAEAA==")));
        rows.put("inject",map("type","click","time",1040L));
        rows.put("consume",row("attack_click_before",41,map()));
        rows.put("use",row("use_click_before",46,map()));
        rows.put("raise",map("type","blockhit_plan","time",1045L,"block_key",true));
        rows.put("lower",map("type","blockhit_plan","time",1060L,"block_key",false));
        rows.put("tail",row("tick_end",1200,map("screen",null)));
        Path folder=Files.createDirectory(root.resolve("server-replay-inputs"));
        gzip(folder.resolve("a-client-fixture-events.jsonl.gz"),"w","ctrl","mouse","mouseUp","gui","tail");
        gzip(folder.resolve("b-client-fixture-events.jsonl.gz"),"applied","botTick","inject","consume","raise","use","packet","lower","released","botStop","tail");
        ServerReplayInputs human=ServerReplayInputs.read(folder,"a",false,1000,rows::get);
        check(human.at(0).down(ReplayInputs.Key.W)&&human.at(25).down(ReplayInputs.Key.LMB),"server replay preserves held human inputs across the start and at the exact click time");
        check(!human.at(55).down(ReplayInputs.Key.LMB)&&human.at(100).mask==0&&human.at(1024).leftCps==1&&human.at(1025).leftCps==0,
                "human release, menu reset and CPS expiration use recorded time");
        ServerReplayInputs bot=ServerReplayInputs.read(folder,"b",true,1000,rows::get);
        ReplayInputs.State state=bot.at(50);
        check(state.down(ReplayInputs.Key.S)&&state.down(ReplayInputs.Key.A)&&state.down(ReplayInputs.Key.SPACE),"bot keys come from its applied movement sample");
        check(state.leftCps==1&&state.rightCps==1&&state.down(ReplayInputs.Key.LMB)&&state.down(ReplayInputs.Key.RMB),
                "attack injections and block presses count once even with native consume records");
        check(!bot.at(60).down(ReplayInputs.Key.RMB)&&!bot.at(72).down(ReplayInputs.Key.LMB)&&bot.at(100).mask==0&&bot.at(1201)==null,
                "bot holds, click flash and missing archive tail are distinct");
        check(human.at(0).sprintCode==ReplayInputs.INPUT_UNKNOWN&&human.at(25).sprintCode==-97&&human.at(25).down(ReplayInputs.Key.SPRINT),"remapped mouse sprint binding, unknown before first observation");
        check(state.down(ReplayInputs.Key.SPRINT)&&state.sprintCode==ReplayInputs.BINDING_UNKNOWN&&Boolean.TRUE.equals(bot.botDetails(50).get("click_gate")),
                "legacy applied action joins by native tick despite a shifted game clock");
        check(Boolean.FALSE.equals(bot.botDetails(48).get("sent_sprint"))&&!bot.botDetails(47).containsKey("sent_sprint")&&
                !bot.at(100).down(ReplayInputs.Key.SPRINT)&&bot.at(50).down(ReplayInputs.Key.SPRINT),"seek restores sprint input and never shows future packets");
        Map<String,Object> hit=map("type","hit","rod",false,"damage_upgrade",false,"attacker_sprint",false);
        check(Boolean.FALSE.equals(practice.replay.SprintHit.classify(hit)),"base fresh hit has a distinct verdict");
        hit.put("attacker_sprint",true);check(Boolean.TRUE.equals(practice.replay.SprintHit.classify(hit)),"fresh sprint bonus");
        hit.put("damage_upgrade",true);check(practice.replay.SprintHit.classify(hit)==null,"upgrades cannot masquerade as fresh KB");
        hit.put("damage_upgrade",false);hit.put("direct_melee",false);check(practice.replay.SprintHit.classify(hit)==null,"projectiles excluded");
        check(ServerReplayInputs.read(root,"b",true,1000,rows::get).at(50)==null,"missing participant archive is unavailable");
    }
    private static void replayBrakes(Path root)throws Exception{
        Path folder=Files.createDirectory(root.resolve("brake-history"));
        Files.write(folder.resolve("manifest.json"),"manifest".getBytes(StandardCharsets.UTF_8));
        Map<String,Map<String,Object>> rows=new HashMap<>();
        rows.put("manifest",map("players",map("a",map("name","One"),"b",map("name","Two"))));
        rows.put("tick",row("tick_start",0,map("self",map("id",7),"entities",Arrays.asList(map("id",8,"name","Two")))));
        String[] kinds={"S19PacketEntityStatus","S12PacketEntityVelocity"};
        byte[][] bytes={{0x1a,0,0,0,7,2},{0x12,7,0x0f,(byte)0xa0,0,0,0,0}};
        for(int i=0;i<2;i++){
            rows.put("wire"+i,row("packet_wire",i,map("packet",map("packet_id",i,"direction","in","kind",kinds[i]),"base64",Base64.getEncoder().encodeToString(bytes[i]))));
            rows.put("apply"+i,row("packet_handler_enter",5+i,map("packet_id",i,"kind",kinds[i],"main_thread",true)));
        }
        for(int i=0;i<3;i++)for(int phase=0;phase<2;phase++){
            double v=(i==0?-.2:.2)*(phase==0?1:.6);
            String type=phase==0?"attack_click_before":"attack_click_after";
            Map<String,Object> row=row(type,20+i*60+phase,map("self",map("id",7,"sprinting",phase==0,"motion",Arrays.asList(v,0.,0.)),
                    "ray",map("type","ENTITY","entity",8),"left_click_counter",0));
            row.put("thread","Client thread");row.put("client_tick",i);rows.put(type+i,row);
        }
        gzip(folder.resolve("a-client-fixture-events.jsonl.gz"),"tick","wire0","wire1","apply0","apply1","attack_click_before0","attack_click_after0",
                "attack_click_before1","attack_click_after1","attack_click_before2","attack_click_after2");
        ServerReplayInputs history=ServerReplayInputs.read(folder,"a",false,1000,rows::get);
        check(history.coachAt(21).isEmpty(),"forward sprint drag is not a brake against this launch");
        check(history.coachAt(80).isEmpty()&&Boolean.TRUE.equals(history.coachAt(81).get("brake")),"green begins at the completed outward cut");
        check(Integer.valueOf(2).equals(history.coachAt(141).get("cuts"))&&!history.coachAt(241).containsKey("brake")&&history.coachAt(621).isEmpty(),"first flash and grouped count use separate replay durations");
        check(history.coachAt(80).isEmpty(),"backwards seek clears future brakes");
    }
    private static Map<String,Object> click(String phase,int time,double speed,int hurt){
        return row("attack_click_"+phase,time,map("self",map("id",7,"motion",Arrays.asList(speed,0.,0.),
                "pos",Arrays.asList(0.,4.,0.),"hurtTime",hurt,"sprinting",phase.equals("before")),
                "ray",map("type","ENTITY","entity",8,"point",Arrays.asList(2.,5.,0.))));
    }
    private static Map<String,Object> targetPose(int time,double z,int hurt){
        return row("tick_end",time,map("self",map("id",7,"pos",Arrays.asList(0.,4.,0.)),"opponent",
                map("id",8,"kind","EntityOtherPlayerMP","pos",Arrays.asList(2.,4.,z),"hurtTime",hurt)));
    }
    private static void replayCombat(Path root)throws Exception{
        Map<String,Map<String,Object>> data=new LinkedHashMap<>();
        data.put("start",row("capture_start",0,map("started_mid_connection",true)));
        data.put("first",targetPose(5,0,0));
        data.put("own",wire(true,10,1,(byte)0x12,(byte)7,(byte)0xf0,(byte)0x60,(byte)0,(byte)0,(byte)0,(byte)0));
        data.put("apply",handler(12,1,true));
        data.put("a1",click("before",20,-.5,9));data.put("b1",click("after",21,-.3,9));
        data.put("a2",click("before",22,-.25,8));data.put("b2",click("after",23,-.15,8));
        data.put("forward",click("before",24,.5,8));data.put("forwardEnd",click("after",25,.3,8));
        data.put("withoutHurt",click("before",26,-.2,0));data.put("withoutHurtEnd",click("after",27,-.12,0));
        data.put("hurt",targetPose(55,0,10));data.put("move1",targetPose(105,.3,9));data.put("move2",targetPose(155,.55,8));
        data.put("reset",wire(true,180,3,(byte)0x08));
        data.put("end",map("type","capture_end","writer_complete",true));
        Path file=root.resolve("recoil.jsonl.gz");
        gzip(file,data.keySet().toArray(new String[0]));
        ReplayRecording replay=ReplayRecording.load(entry(file),data::get);
        check(replay.hasAttackSamples&&replay.recoilAt(20).total==0,"recoil counts change at recorded click completion");
        check(replay.recoilAt(21).afterKnockback==1&&replay.recoilAt(23).afterKnockback==2,
                "multiple backward reductions accumulate after the same incoming knockback");
        check(replay.recoilAt(25).total==2&&replay.recoilAt(27).total==3&&replay.recoilAt(27).afterKnockback==2,
                "forward braking excluded and a later backward reduction without own hurt stays outside the per-hit count");
        check(replay.recoilAt(180).total==3&&!replay.recoilAt(180).hasKnockback,"teleport clears the last-hit grouping");
        check(!replay.attackScaleFlash(20)&&replay.attackScaleFlash(21)&&replay.attackScaleFlash(126)
                        &&!replay.attackScaleFlash(127),"flash runs only for 100 ms after each confirmed horizontal scale");
        check(replay.timeline.stream().anyMatch(m->m.atMs==25&&m.kind.equals("horizontal_attack_scale")),
                "forward braking flashes without incrementing the backward recoil counter");
        check(replay.motion.at(12).value.x==-.5&&replay.motion.at(21).value.x==-.3,
                "velocity inspector tracks exact server packet and measured local click motion");
        check(replay.motion.recent(21,5).stream().anyMatch(c->c.reason.contains("client sprint on")&&Math.abs(c.delta.x-.2)<1e-9),
                "click change shows pre-click sprint and exact motion delta");
        check(replay.motion.at(180).value==null&&replay.previousTick(155)==105&&replay.motion.step(21,false)==12,
                "position reset clears current velocity and velocity/tick navigation can rewind");
        data.put("target",wire(true,56,4,(byte)0x12,(byte)8,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0x0c,(byte)0x80));
        String[] withPacket={"start","first","hurt","target","move1","move2","end"};
        gzip(file,withPacket);
        ReplayRecording exact=ReplayRecording.load(entry(file),data::get);
        check(exact.timeline.stream().anyMatch(m->m.atMs==56&&m.kind.equals("velocity")&&m.details.get("velocity").equals(Arrays.asList(0.,0.,.4))),
                "raw opponent velocity remains available when a packet contains it");
    }
    private static void sumoBookmarks(Path root)throws Exception{
        MmcSumoReplay parser=new MmcSumoReplay();
        String capture="a-client-1795049879368-abcd-1234-5678-events.jsonl.gz";
        String intro="Unranked Match\n ● Map: TownRoad\n ● Opponent: Example (Iron II)\n ● Ping: 28 ms";
        check(MmcSumoReplay.server("geo.minemen.club.:25565")&&MmcSumoReplay.server("mineman.club")
                        &&!MmcSumoReplay.server("minemen.club.example.invalid"),
                "MMC host check accepts the real dotted hostname and checks domain boundaries");
        parser.chat(intro,"Player",1000,capture,4);
        parser.chat("Match started!","Player",6000,capture,6);
        parser.chat("Match Results (Click to view)","Player",8000,capture,7);
        check(parser.chat("Winner: Player  ▏  Loser: Example","Player",8001,capture,7)==null,
                "non-Sumo result is ignored");
        parser.chat(intro,"Player",9000,capture,8);
        parser.chat("5...","Player",9100,capture,8);
        parser.chat("Match started!","Player",14000,capture,10);
        parser.chat("§aSumo \n Knock your opponents off the platform!","Player",14001,capture,10);
        String active=parser.activeId();
        check(active!=null&&parser.combatStart()==14000,"Sumo coach uses combat start rather than countdown");
        parser.chat("Match started!","Player",14002,capture,10);
        check(active.equals(parser.activeId()),"A duplicate start does not reset the round ID");
        parser.chat("Match Results (Click to view)","Player",18000,capture,12);
        check(parser.chat("Winner: Someone  ▏  Loser: Example","Player",18001,capture,12)==null,
                "spectator result is ignored");
        MmcSumoReplay.Match match=parser.chat("Winner: Example  ▏  Loser: Player","Player",18002,capture,12);
        check(match!=null&&active.equals(match.id)&&parser.activeId()==null&&!match.won&&match.opponent.equals("Example")&&match.start==9100&&match.finish==18002&&match.part==6,
                "MMC duel bookmark uses the exact five-second countdown and keeps earlier arena packets");
        Path metadata=root.resolve("sumo-replays");
        match.save(metadata);
        MmcSumoReplay.Match saved=MmcSumoReplay.read(metadata,match.id);
        Path captures=Files.createDirectories(root.resolve("sumo-fixtures"));
        Path segment=saved.firstSegment(captures);
        gzip(segment,"end");
        ReplayRecording.Entry selected=ReplayRecording.sumoEntry(captures,saved);
        check(selected!=null&&selected.firstFile.equals(segment)&&selected.preferredStartMs==9100&&selected.finishMs==18002
                &&MmcSumoReplay.read(metadata,"../example")==null,"bookmark round trips by local ID and selects an exact match span");
        Map<String,Map<String,Object>> rows=new HashMap<>();
        rows.put("start",row("capture_start",8000,map("started_mid_connection",true)));
        rows.put("pose",row("tick_end",8110,map("self",map("id",7,"pos",Arrays.asList(0.,4.,0.),"hurtTime",0),
                "entities",Collections.singletonList(map("id",8,"name","Example","kind","EntityOtherPlayerMP","pos",Arrays.asList(2.,4.,0.))))));
        byte[] hurt={(byte)0x1a,0,0,0,7,2};
        rows.put("hit",wire(true,15000,1,hurt));
        rows.put("same-hit",wire(true,15050,2,hurt));
        rows.put("next-hit",wire(true,16150,3,hurt));
        rows.put("last-hit",wire(true,16950,4,hurt));
        rows.put("tail",row("tick_end",17002,map("screen",null)));
        rows.put("end",map("type","capture_end","writer_complete",true,"continues","currently-open.jsonl.gz"));
        Files.write(metadata.resolve(match.id+".properties"),
                ("last_part="+match.part+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
        gzip(segment,"start","pose","hit","same-hit","next-hit","last-hit","tail","end");
        ReplayRecording.Entry bounded=ReplayRecording.sumoEntry(captures,MmcSumoReplay.read(metadata,match.id));
        ReplayRecording combo=ReplayRecording.load(bounded,rows::get);
        check(!warns(combo,"Missing linked segment")&&combo.sumoMoments.impacts.size()==3,
                "a sealed clip ends before the open capture part and collapses repeated hurt cues");
        double startOfCombo=combo.sumoMoments.impacts.get(0).atMs;
        check(combo.openingTime()==startOfCombo-450&&combo.sumoMoments.after(startOfCombo)==1&&combo.playStart()<combo.openingTime(),
                "opening leads the winner's finishing combo while full countdown remains seekable");
        gzip(segment,"start","pose","last-hit","tail","end");
        ReplayRecording single=ReplayRecording.load(bounded,rows::get);
        check(single.openingTime()==single.playEnd()-3000,"a single hit opens at the last three seconds");
    }
    private static void smoothPoses(){
        Map<String,Object> from=map("id",7,"pos",Arrays.asList(0.,4.,0.),"yaw",179.);
        Map<String,Object> next=map("id",7,"pos",Arrays.asList(2.,5.,-1.),"yaw",-179.);
        Map<String,Object> visual=ReplayVisuals.between(from,next,.5);
        check(visual.get("render").equals(Arrays.asList(1.,4.5,-.5))&&visual.get("render_yaw").equals(180.),
                "render position and angle interpolate between recorded ticks");
        check(from.get("pos").equals(visual.get("pos"))&&!from.containsKey("render"),"visual interpolation leaves logical position intact");
        Map<String,Object> teleport=map("id",7,"pos",Arrays.asList(100.,4.,0.),"yaw",100.);
        check(ReplayVisuals.between(from,teleport,.5)==from,"large recorded teleport snaps without interpolation");
        check(ReplayVisuals.between(from,map("id",8,"pos",Arrays.asList(2.,5.,-1.)),.5)==from,"entity replacement never interpolates");
    }
    public static int run()throws Exception{
        checks=0;Path root=Files.createTempDirectory("replay-recording-check-");
        try{
            long mib=1024L*1024;ReplayRecording.Limits base=ReplayRecording.Limits.forHeap(512*mib),two=ReplayRecording.Limits.forHeap(2048*mib),four=ReplayRecording.Limits.forHeap(4096*mib);
            check(base.wireBytes==128*mib&&base.inflatedBytes==1024*mib&&base.poses==10000&&base.retainedPoseBytes==32*mib,"512MiB heap retains the original fixture memory bounds");
            check(two.wireBytes==4*base.wireBytes&&two.inflatedBytes==4*base.inflatedBytes&&two.rows==4*base.rows&&two.poses==4*base.poses&&two.retainedPoseBytes==4*base.retainedPoseBytes,"2GiB heap scales only retained workload budgets by four");
            check(four.wireBytes==8*base.wireBytes&&four.poses==8*base.poses&&four.parts==10000&&four.packetBytes==2*mib,"4GiB heap reaches scale eight with unchanged packet/part limits");
            check(ReplayRecording.Limits.forHeap(16*mib).wireBytes==base.wireBytes,"Small heaps retain the minimum base limits");
            check(ReplayRecording.Limits.forHeap(Long.MAX_VALUE).wireBytes==four.wireBytes,"Huge heaps cannot exceed the bounded scale cap");
            final Map<String,Map<String,Object>> fixtures=new HashMap<String,Map<String,Object>>();
            Function<String,Map<String,Object>> parser=s->{Map<String,Object> v=fixtures.get(s);if(v==null)throw new IllegalArgumentException("Fixture parse error");return v;};
            fixtures.put("start",row("capture_start",0,map("started_mid_connection",false,"server_host","example.invalid")));
            fixtures.put("join",wire(true,1,1,(byte)1,(byte)0,(byte)0,(byte)0,(byte)7));fixtures.put("joinApply",handler(2,1,true));
            fixtures.put("p5",pose(5,7));fixtures.put("attack",wire(false,10,2,(byte)2,(byte)8,(byte)1));
            fixtures.put("velocity",wire(true,11,3,(byte)0x12,(byte)7,(byte)0x0c,(byte)0x80,(byte)0xfc,(byte)0xe0,(byte)0,(byte)0));
            fixtures.put("offThread",handler(12,3,false));fixtures.put("velocityApply",handler(20,3,true));
            fixtures.put("hurt",wire(true,21,4,(byte)0x1a,(byte)0,(byte)0,(byte)0,(byte)7,(byte)2));fixtures.put("hurtApply",handler(22,4,true));
            fixtures.put("transaction",wire(false,23,5,(byte)0x0e,(byte)2,(byte)0,(byte)0,(byte)0,(byte)0,(byte)9,(byte)0));
            fixtures.put("confirm",wire(true,28,6,(byte)0x32,(byte)2,(byte)0,(byte)9,(byte)1));fixtures.put("confirmApply",handler(30,6,true));
            fixtures.put("ping",wire(true,31,7,(byte)0,(byte)42));fixtures.put("pong",wire(false,34,8,(byte)0,(byte)42));
            fixtures.put("more",map("type","capture_end","writer_complete",true,"dropped",0,"continues","record-part2.jsonl.gz"));
            fixtures.put("segment",map("type","capture_segment","first","record.jsonl.gz","part",2));
            fixtures.put("p50",pose(50,9));fixtures.put("p100",pose(100,9));
            fixtures.put("oldEntityVelocity",wire(true,55,9,(byte)0x12,(byte)7,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0));
            fixtures.put("oldApply",handler(56,9,true));fixtures.put("end",map("type","capture_end","writer_complete",true,"dropped",0,"continues",null));
            Path captures=Files.createDirectory(root.resolve("captures")),first=captures.resolve("record.jsonl.gz");
            gzip(first,"start","join","joinApply","p5","attack","velocity","offThread","velocityApply","hurt","hurtApply","transaction","confirm","confirmApply","ping","pong","more");
            gzip(captures.resolve("record-part2.jsonl.gz"),"segment","p50","oldEntityVelocity","oldApply","p100","end");
            ReplayRecording r=ReplayRecording.load(entry(first),parser);
            check(r.hasJoin&&r.selfId==7,"JOIN_GAME establishes initial own ID");check(r.startMs==1000&&r.durationMs==100,"Monotonic origin and duration cross linked parts");
            check(r.poseAt(4)==null&&r.poseAt(5).atMs==5&&r.poseAt(75).atMs==50,"Seek uses prior recorded pose without extrapolated state");
            check(r.tickStep(0)==5&&r.tickStep(5)==50&&r.tickStep(100)==100,"Tick step uses actual own end boundaries");
            check(((Number)r.poseAt(5).opponent.get("id")).intValue()==8,"Explicit ray selects recorded target entity");
            ReplayRecording.Mark velocity=mark(r,"velocity",0);List<?> v=(List<?>)velocity.details.get("velocity");
            check(velocity.atMs==20&&Math.abs(((Number)v.get(0)).doubleValue()-.4)<1e-12&&Math.abs(((Number)v.get(1)).doubleValue()+.1)<1e-12,"S12 uses exact signed short/8000 and main-thread application time");
            check(Boolean.TRUE.equals(velocity.details.get("own"))&&Boolean.FALSE.equals(mark(r,"velocity",1).details.get("own")),"Own classification follows identity at each recorded pose, not final ID");
            check(String.valueOf(velocity.details.get("association")).contains("unconfirmed"),"Attack proximity never claims causal knockback");
            check(mark(r,"attack",0).atMs==10&&mark(r,"attack",0).details.containsKey("accepted_by_server")&&mark(r,"attack",0).details.get("accepted_by_server")==null,"Outbound C02 acceptance is unknown, not assumed accepted or rejected");
            check(mark(r,"hurt",0).atMs==22&&String.valueOf(mark(r,"hurt",0).details.get("server_immunity")).contains("Unknown"),"Status2 does not manufacture server immunity knowledge");
            check(((Number)mark(r,"transaction_rtt",0).details.get("rtt_ms")).doubleValue()==5,"Exact window/action match measures outgoing C0E to incoming S32 wire RTT");
            check(((Number)mark(r,"keepalive_response",0).details.get("client_response_ms")).doubleValue()==3&&Boolean.FALSE.equals(mark(r,"keepalive_response",0).details.get("rtt")),"Keepalive echo is client response delay, not invented network RTT");
            ReplayRecording.Wire applied=null;for(ReplayRecording.Wire w:r.packets)if(w.packetId==3)applied=w;
            check(applied!=null&&applied.atMs==20&&applied.wireAtMs==11&&applied.applicationTimed,"Wire preserves receipt alongside application");
            check(r.inboundPackets().size()<r.packets.size()&&r.inboundPackets().stream().allMatch(w->w.inbound),"Playback subset contains no outbound commands");
            check(warns(r,"lack unique main-thread"),"Fallback timing is explicit");
            @SuppressWarnings("unchecked") List<Object> originalPos=(List<Object>)((Map<?,?>)((Map<?,?>)fixtures.get("p5").get("data")).get("self")).get("pos");
            originalPos.set(0,99.);check(((Number)((List<?>)r.poseAt(5).self.get("pos")).get(0)).doubleValue()==0,"Loaded poses deep-copy nested parsed observations");
            boolean immutable=false;try{r.poseAt(5).self.put("id",42);}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"Pose maps are read-only");
            Path duplicate=root.resolve("duplicate.jsonl.gz");gzip(duplicate,"start","p5","velocity","velocityApply","velocityApply","end");
            ReplayRecording duplicated=ReplayRecording.load(entry(duplicate),parser);
            check(!duplicated.packets.get(0).applicationTimed&&duplicated.packets.get(0).atMs==11,"Duplicate main-thread handlers never pick a favorable ordinal");
            check(!duplicated.hasJoin&&warns(duplicated,"Mid-connection"),"Missing JOIN_GAME is explicit and cannot claim a reconstructed world");
            fixtures.put("earlyVelocity",wire(true,3,40,(byte)0x12,(byte)7,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0));
            Path early=root.resolve("early.jsonl.gz");gzip(early,"start","earlyVelocity","p5","end");
            check(Boolean.FALSE.equals(mark(ReplayRecording.load(entry(early),parser),"velocity",0).details.get("own")),"A later pose cannot retroactively identify an earlier entity as own");
            ReplayRecording.Limits defaults=ReplayRecording.Limits.defaults();
            ReplayRecording small=ReplayRecording.load(entry(first),parser,new ReplayRecording.Limits(defaults.wireBytes,defaults.inflatedBytes,4,1000,10,100));
            check(warns(small,"Oversize wire packet skipped")&&!small.hasJoin,"Oversize packets are skipped with warnings");
            fails(()->ReplayRecording.load(entry(first),parser,new ReplayRecording.Limits(1,100000,100,1000,10,100)),"loaded-wire-byte");
            fails(()->ReplayRecording.load(entry(first),parser,new ReplayRecording.Limits(100000,3,100,1000,10,100)),"inflated-byte");
            fails(()->ReplayRecording.load(entry(first),parser,new ReplayRecording.Limits(100000,100000,100,2,10,100)),"row limit");
            fails(()->ReplayRecording.load(entry(first),parser,new ReplayRecording.Limits(100000,100000,100,1000,1,100)),"part limit");
            fails(()->ReplayRecording.load(entry(first),parser,new ReplayRecording.Limits(100000,100000,100,1000,10,1)),"pose limit");
            fixtures.put("escape",map("type","capture_end","writer_complete",true,"continues","../elsewhere.jsonl.gz"));
            Path bad=root.resolve("escape.jsonl.gz");gzip(bad,"start","escape");fails(()->ReplayRecording.load(entry(bad),parser),"Linked segment");
            fixtures.put("cycle",map("type","capture_end","writer_complete",true,"continues","cycle.jsonl.gz"));
            Path cycle=root.resolve("cycle.jsonl.gz");gzip(cycle,"start","cycle");fails(()->ReplayRecording.load(entry(cycle),parser),"segment cycle");
            fixtures.put("missing",map("type","capture_end","writer_complete",true,"continues","missing-part2.jsonl.gz"));
            Path missing=root.resolve("missing.jsonl.gz");gzip(missing,"start","join","missing");check(warns(ReplayRecording.load(entry(missing),parser),"Missing linked segment"),"Missing linked part leaves an explicit partial recording");
            fixtures.put("invalid64",row("packet_wire",10,map("packet",map("direction","in","packet_id",55),"base64","!!!")));
            fixtures.put("oversizeEvent",row("packet_oversize",11,map("bytes",3000000)));
            Map<String,Object> login=wire(true,10,56,(byte)1,(byte)0,(byte)0,(byte)0,(byte)7);@SuppressWarnings("unchecked") Map<String,Object> loginMeta=(Map<String,Object>)((Map<?,?>)login.get("data")).get("packet");loginMeta.put("state","LOGIN");fixtures.put("login",login);
            Path partial=root.resolve("partial.jsonl.gz");gzip(partial,"start","malformed","invalid64","oversizeEvent","login");ReplayRecording incomplete=ReplayRecording.load(entry(partial),parser);
            check(incomplete.packets.isEmpty()&&warns(incomplete,"Malformed JSON")&&warns(incomplete,"Invalid packet base64")&&warns(incomplete,"Non-PLAY")&&warns(incomplete,"Missing capture_end"),"Malformed lines, non-PLAY, bad base64 and absent footer stay explicit");
            check(warns(incomplete,"Capture omitted an oversize"),"An explicit capture-side packet omission cannot disappear from integrity warnings");
            Path unmatched=root.resolve("unmatched.jsonl.gz");gzip(unmatched,"start","transaction","transaction","confirm","end");
            check(ReplayRecording.load(entry(unmatched),parser).timeline.stream().noneMatch(m->m.kind.equals("transaction_rtt")),"Duplicate transaction request identity never fabricates RTT");

            // The launcher Java 8 runtime on macOS reads file mtimes at second precision.
            Files.setLastModifiedTime(first,java.nio.file.attribute.FileTime.fromMillis(2000));
            Files.setLastModifiedTime(captures.resolve("record-part2.jsonl.gz"),java.nio.file.attribute.FileTime.fromMillis(3000));
            Path archive=Files.createDirectory(root.resolve("archive")),folder=Files.createDirectory(archive.resolve("m9999")),outside=Files.createDirectory(root.resolve("other-client-captures")),full=outside.resolve("full.jsonl.gz");Files.copy(first,full);
            fixtures.put("manifest",map("start_ms",1080,"finish_ms",1100));Files.write(folder.resolve("manifest.json"),"manifest".getBytes(StandardCharsets.UTF_8));
            fixtures.put("archiveHeader",map("type","pov_archive_start","time_ms",1080,"metadata",map("full_session_capture",full.toString(),"server_host","local lab")));
            gzip(folder.resolve("a-client-fixture-events.jsonl.gz"),"archiveHeader","end");
            List<ReplayRecording.Entry> entries=ReplayRecording.catalog(captures,archive,parser);check(entries.size()==2,"Catalogue omits linked continuation parts and includes lab archives");
            ReplayRecording.Entry lab=null,local=null;for(ReplayRecording.Entry e:entries)if(e.id.equals("m9999"))lab=e;else local=e;
            check(lab!=null&&lab.firstFile.equals(full.toAbsolutePath())&&lab.archiveFolder.equals(folder),"Lab archive resolves trusted existing full-session file in another slot's local capture directory");
            check(lab.preferredStartMs==1080&&lab.finishMs==1100&&lab.startMs==1000,"Lab selection retains match bounds and full-session time origin");
            check(local!=null&&local.startMs==1000&&local.durationEstimated&&local.durationMs==2000&&local.server.equals("example.invalid"),"Estimated local duration uses the latest linked-part mtime, not just the first segment");

            StringBuilder large=new StringBuilder();for(int i=0;i<100000;i++)large.append('x');
            Map<String,Object> visualTarget=map("id",8,"kind","EntityOtherPlayerMP","pos",Arrays.asList(2.,4.,0.),"held","captured-target-item");
            Map<String,Object> unrelated=map("id",10,"kind","EntityItem","pos",Arrays.asList(5.,4.,0.),"rotationYawHead",12.,"renderYawOffset",13.,"swingProgress",.2,"prevSwingProgress",.1,"inventory_blob",large.toString());
            Map<String,Object> compactFixture=map("self",map("id",7,"pos",Arrays.asList(0.,4.,0.),"custom_own_field","kept"),"entities",Arrays.asList(visualTarget,unrelated),"ray",map("entity",8),"unused_snapshot_blob",large.toString());
            fixtures.put("heavyTick",row("tick_end",5,compactFixture));fixtures.put("heavyRender",row("render_view",6,compactFixture));
            String[] many=new String[504];many[0]="start";many[1]="heavyTick";for(int i=2;i<502;i++)many[i]="heavyRender";many[502]="p100";many[503]="end";
            Path heavy=root.resolve("heavy.jsonl.gz");gzip(heavy,many);
            ReplayRecording compacted=ReplayRecording.load(entry(heavy),parser,new ReplayRecording.Limits(100000,1000000,100,1000,10,2));
            check(compacted.poses.size()==2&&compacted.poses.get(0).atMs==5&&compacted.poses.get(1).atMs==100,"Render spam is discarded during parsing while exact native tick poses/times survive");
            check(compacted.poseAt(5).self.get("custom_own_field").equals("kept")&&compacted.poseAt(5).opponent.get("held").equals("captured-target-item"),"Compaction retains complete own and selected target observations");
            Map<?,?> compactOther=(Map<?,?>)((List<?>)compacted.poseAt(5).state.get("entities")).get(1);
            check(!compactOther.containsKey("inventory_blob")&&compactOther.get("rotationYawHead").equals(12.)&&compactOther.containsKey("prevSwingProgress")&&!compacted.poseAt(5).state.containsKey("unused_snapshot_blob"),"Unrelated entities keep visual fields without retaining giant unused snapshot data");
            check(warns(compacted,"redundant view samples omitted"),"Recorded pose sampling loss is explicit, never called interpolation");
            fails(()->ReplayRecording.load(entry(heavy),parser,new ReplayRecording.Limits(100000,1000000,100,1000,10,10,32)),"retained-pose-byte");
            List<String> remoteTokens=new ArrayList<String>();remoteTokens.add("start");
            for(int i=0;i<100;i++){String token="remote"+i;fixtures.put(token,row("remote_pose",i*10,map("view",map("self",map("id",7,"pos",Arrays.asList(i*.01,4.,0.))))));remoteTokens.add(token);}remoteTokens.add("end");
            Path remote=root.resolve("remote.jsonl.gz");gzip(remote,remoteTokens.toArray(new String[remoteTokens.size()]));
            ReplayRecording sampled=ReplayRecording.load(entry(remote),parser,new ReplayRecording.Limits(100000,1000000,100,1000,10,25));
            check(sampled.poses.size()==20&&sampled.poses.get(19).atMs==950&&sampled.durationMs==990,"Tick-free remote poses are recorded samples at <=20Hz while real recording duration stays exact");
            replayCombat(root);
            serverReplayInputs(root);
            replayBrakes(root);
            sumoBookmarks(root);
            smoothPoses();
        }finally{try(java.util.stream.Stream<Path> files=Files.walk(root)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
        return checks;
    }
}
