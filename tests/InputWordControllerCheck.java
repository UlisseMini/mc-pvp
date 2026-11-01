import lab.InputWordController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Plain Java8 callback/recipe contract tests; no Minecraft or combat simulation. */
public final class InputWordControllerCheck {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    interface Checked {void run()throws Exception;}
    static void rejects(Checked action,Class<? extends Throwable> type,String why)throws Exception{
        try{action.run();}catch(Throwable error){check(type.isInstance(error),why+": "+error);return;}
        throw new AssertionError("Expected rejection: "+why);
    }
    static Path file(Path directory,String text)throws IOException{
        Path path=Files.createTempFile(directory,"word-",".tsv");Files.write(path,text.getBytes(StandardCharsets.UTF_8));return path;
    }
    static InputWordController word(Path directory,String rows)throws IOException{
        return new InputWordController(file(directory,"# index\tkey\tnative_strafe\tmode\tclick\n"+rows));
    }
    static void parsing(Path directory)throws Exception{
        rejects(()->new InputWordController(directory.resolve("missing.tsv")),IOException.class,"Missing word");
        for(String text:new String[]{"","# only comment\n","0 1 0 gate\n","1 1 0 gate 1\n","0 1 0 gate 1\n0 2 0 pulse 0\n","0 3 0 gate 1\n","0 1 2 gate 1\n","0 1 0 other 1\n","0 1 0 pulse 2\n","x 1 0 gate 1\n"})
            rejects(()->word(directory,text),IllegalArgumentException.class,"Invalid recipe "+text);
        StringBuilder large=new StringBuilder();for(int i=0;i<4097;i++)large.append(i).append(" 1 0 gate 0\n");
        rejects(()->word(directory,large.toString()),IllegalArgumentException.class,"4097 rows");
        Path original=file(directory,"index\tkey\tnative_strafe\tmode\tclick\n0 2 -1 gate 1\n");
        InputWordController frozen=new InputWordController(original);Files.write(original,"broken".getBytes(StandardCharsets.UTF_8));
        InputWordController.Decision decision=frozen.decide(20,0,false);
        check(decision.key==2&&decision.side== -1&&decision.click,"Parsed native sign and immutable rows");
    }
    static void denseAndLifecycle(Path directory)throws Exception{
        InputWordController p=word(directory,"0 1 -1 pulse 0\n1 2 1 pulse 1\n2 0 0 pulse 1\n3 1 0 pulse 0\n");
        p.movementCompleted(499);check(!p.allowInjection(499,false),"Countdown callbacks cannot arm");
        check(p.decide(500,2,false).index==0,"Anchor first valid HEAD, not process ticks");
        check(!p.allowInjection(500,false)&&!p.allowInjection(500,true),"No admission before movement");
        p.movementCompleted(500);check(!p.allowInjection(500,true),"No synthetic tick-head admission");
        check(p.allowInjection(500,false),"Real frame after same-head movement");
        check(!p.queued()&&p.pendingAdmissions()==1,"One admitted pulse closes gate");
        check(!p.allowInjection(500,false),"Repeated frames cannot duplicate");
        check(p.decide(501,2,false).click,"Next request arms before previous consumption");
        InputWordController.Consumption first=p.consumed(501); // Native MISS is still an invocation.
        check(first.matched&&first.admissionId==1&&first.desiredNativeTick==501&&first.index==1,"MISS acknowledgment targets prior frame FIFO");
        p.movementCompleted(501);check(p.allowInjection(501,false),"Consumption did not clear newly armed request");p.queued();
        p.decide(502,2,false);InputWordController.Consumption second=p.consumed(502);
        check(second.matched&&second.admissionId==2&&second.desiredNativeTick==502,"Consecutive50ms consumption slots");
        p.movementCompleted(502);check(!p.allowInjection(502,false),"No unrequested press");
        p.decide(503,2,false);InputWordController.Decision end=p.decide(504,2,false);
        check(end.active&&end.key==0&&end.side==0&&!end.click&&end.mode.equals("end"),"Full end remains safely active idle");
        check(p.mismatchCount()==0&&p.missedDeadlineCount()==0&&p.pendingAdmissions()==0,"Dense exact contract");
        rejects(()->p.decide(504,2,false),IllegalArgumentException.class,"Duplicate own HEAD");
        p.reset();check(p.decide(9000,2,false).index==0&&p.admissionCount()==0,"Reset starts a new match without countdown advancement");
    }
    static void deadlineAndLate(Path directory)throws Exception{
        String text="0 1 0 pulse 0\n1 1 0 pulse 1\n2 1 0 pulse 0\n3 0 0 pulse 0\n";
        InputWordController missed=word(directory,text);missed.decide(100,0,false);missed.movementCompleted(100);
        check(missed.allowInjection(100,false),"Permission does not bypass caller CPS gate");
        // Normal shared CPS limiter refuses admission: queued() is NOT called.
        check(!missed.decide(101,0,false).click&&missed.missedDeadlineCount()==1,"Cap-deferred request expires, no catch-up");
        missed.movementCompleted(101);check(!missed.allowInjection(101,false)&&missed.pendingAdmissions()==0,"Expired request is never queued later");
        InputWordController late=word(directory,text);late.decide(100,0,false);late.movementCompleted(100);late.queued();
        late.decide(101,0,false);late.movementCompleted(101);late.decide(102,0,false);
        check(late.pendingAdmissions()==1&&late.missedDeadlineCount()==0,"Queued-late ticket is retained, not counted unadmitted");
        check(!late.consumed(102).matched&&late.mismatchCount()==1&&late.missedDeadlineCount()==0,"Late consume is mismatch only");
        InputWordController skipped=word(directory,"0 1 0 pulse 0\n1 1 0 pulse 1\n2 1 0 pulse 1\n3 0 0 pulse 0\n");
        skipped.decide(10,0,false);skipped.movementCompleted(10);skipped.queued();skipped.decide(12,0,false);
        check(skipped.skippedTickCount()==1&&skipped.missedDeadlineCount()==1,"Skipped HEAD does not pause rows and unadmitted slot is counted");
        check(skipped.pendingAdmissions()==1&&!skipped.consumed(12).matched,"Skipped HEAD does not cancel admitted older press");
    }
    static void gateFifoAndPrefix(Path directory)throws Exception{
        InputWordController gate=word(directory,"0 2 0 gate 1\n1 1 0 gate 0\n");gate.decide(70,1,false);
        check(gate.allowInjection(70,true)&&gate.allowInjection(70,false),"Gate preserves normal tick/frame opportunities");
        check(gate.queued()&&gate.queued()&&gate.pendingAdmissions()==2,"Gate admissions retain normal FIFO; caller enforces cap");
        gate.decide(71,1,false);check(gate.pendingAdmissions()==2,"Gate-off does not cancel already admitted inputs");
        check(gate.consumed(71).admissionId==1&&gate.consumed(71).admissionId==2,"Two native invocations consume in order");
        check(!gate.consumed(71).matched&&gate.unexpectedConsumptionCount()==1&&gate.mismatchCount()==1,"Unexpected callback is flagged, not invented admission");
        InputWordController prefix=word(directory,"0 1 0 pulse 0\n1 1 0 pulse 1\n2 2 0 pulse 1\n");
        prefix.decide(30,2,true);prefix.movementCompleted(30);prefix.queued();
        check(!prefix.decide(31,2,true).click,"Last prefix row cannot prequeue beyond baseline handoff");prefix.consumed(31);prefix.movementCompleted(31);
        check(!prefix.allowInjection(31,false)&&!prefix.decide(32,2,true).active,"Opening-only end hands back to Wtap");
    }
    static void actualSource()throws Exception{
        InputWordController p=new InputWordController(Paths.get("research/poc-20261101/anneal/native-source51-word.tsv"));
        check(p.size()==1232,"Frozen actual1232 row recipe");
        List<Integer> expected=new ArrayList<Integer>();
        for(String line:Files.readAllLines(Paths.get("research/poc-20261101/anneal/astra-boundary/source51-own-consumption-program.tsv"),StandardCharsets.UTF_8))
            if(!line.startsWith("time_ms"))expected.add(Integer.parseInt(line.split("\\t")[1])+18);
        List<Integer> actual=new ArrayList<Integer>();long anchor=10000;
        for(int index=0;index<p.size();index++){
            InputWordController.Decision d=p.decide(anchor+index,18,false);
            if(p.pendingAdmissions()>0){InputWordController.Consumption c=p.consumed(anchor+index);check(c.matched,"Actual source consume deadline");actual.add(c.actualIndex);}
            p.movementCompleted(anchor+index);
            // Prefix gate injections belong to ordinary acquisition/CPS handling;
            // this contract test isolates the418 maintenance source pulse slots.
            if(index>=18&&p.allowInjection(anchor+index,false)){check(d.mode.equals("pulse"),"Maintenance is pulse mode");p.queued();}
        }
        check(actual.equals(expected)&&actual.size()==418&&actual.get(0)==21,"All418 source slots exact including prefix18");
        check(p.pendingAdmissions()==0&&p.missedDeadlineCount()==0&&p.mismatchCount()==0,"Real source no backlog or callback mismatch");
        check(p.decide(anchor+p.size(),18,false).active,"Real source full-word end safely idle");
    }
    public static void main(String[] args)throws Exception{
        Path directory=Files.createTempDirectory("input-word-check-");
        try{parsing(directory);denseAndLifecycle(directory);deadlineAndLate(directory);gateFifoAndPrefix(directory);actualSource();}
        finally{try(java.util.stream.Stream<Path> paths=Files.list(directory)){for(Path path:(Iterable<Path>)paths::iterator)Files.delete(path);}Files.delete(directory);}
        System.out.println("InputWordControllerCheck: "+checks+" checks, source418 exact; Java8 callback contract only, no combat/native result");
    }
}
