package lab;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Immutable ordinary own-input recipe, with optional next-input frame prequeue.
 * Java8; no Minecraft, server state, wall clock, cap override or queue cancellation.
 * Call decide once per valid own input HEAD, never during countdown. A separate
 * own movement RETURN establishes the frame-only pulse admission barrier.
 */
public final class InputWordController {
    private static final int MAX_ROWS=4096;
    private static final long NONE=Long.MIN_VALUE;
    private static final class Row {
        final int key,side;final String mode;final boolean click;
        Row(int key,int side,String mode,boolean click){this.key=key;this.side=side;this.mode=mode;this.click=click;}
    }
    public static final class Decision {
        public final int key,side,index;
        public final boolean click,active;
        public final String mode;
        private Decision(int key,int side,int index,boolean click,boolean active,String mode){
            this.key=key;this.side=side;this.index=index;this.click=click;this.active=active;this.mode=mode;
        }
    }
    public static final class Consumption {
        public final long admissionId,desiredNativeTick,actualNativeTick;
        public final int index,actualIndex;
        public final boolean matched;
        public final String mode;
        private Consumption(long id,long desired,long actual,int index,int actualIndex,boolean matched,String mode){
            admissionId=id;desiredNativeTick=desired;actualNativeTick=actual;this.index=index;this.actualIndex=actualIndex;this.matched=matched;this.mode=mode;
        }
    }
    private static final class Admission {
        final long id,desired;final int index;final String mode;
        Admission(long id,long desired,int index,String mode){this.id=id;this.desired=desired;this.index=index;this.mode=mode;}
    }
    private final Row[] rows;
    private final ArrayDeque<Admission> admitted=new ArrayDeque<Admission>();
    private final BitSet accounted=new BitSet(MAX_ROWS);
    private long anchor=NONE,lastTick=NONE,movedTick=NONE,requestTick=NONE;
    private int lastIndex=-1,requestIndex=-1,limit;
    private boolean openingOnly,pulseRequest,active;
    private Row current;
    private long admissions,consumptions,mismatches,missedDeadlines,unexpected,skippedTicks;

    /** TSV columns: index,key,native_strafe(-1/0/1),mode(gate/pulse),click(0/1).
     * Blank/comment lines and an optional exact header are allowed. Everything
     * else is validated before any row can control inputs; at most4096 rows.
     */
    public InputWordController(Path path)throws IOException {
        List<Row> parsed=new ArrayList<Row>();int lineNumber=0;
        try(BufferedReader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){
            String line;
            while((line=reader.readLine())!=null){
                lineNumber++;line=line.trim();if(line.isEmpty()||line.startsWith("#"))continue;
                String[] cells=line.split("\\s+");
                if(parsed.isEmpty()&&line.equals("index\tkey\tnative_strafe\tmode\tclick"))continue;
                if(cells.length!=5)throw invalid(lineNumber,"Expected five columns");
                int index,key,side,click;
                try{index=Integer.parseInt(cells[0]);key=Integer.parseInt(cells[1]);side=Integer.parseInt(cells[2]);click=Integer.parseInt(cells[4]);}
                catch(NumberFormatException error){throw invalid(lineNumber,"Invalid integer field");}
                if(parsed.size()>=MAX_ROWS)throw invalid(lineNumber,"Input word exceeds4096 rows");
                if(index!=parsed.size())throw invalid(lineNumber,"Indices must be contiguous starting0");
                if(key<0||key>2||side< -1||side>1||click<0||click>1)throw invalid(lineNumber,"Invalid key, native strafe or click");
                if(!cells[3].equals("gate")&&!cells[3].equals("pulse"))throw invalid(lineNumber,"Mode must be gate or pulse");
                parsed.add(new Row(key,side,cells[3],click==1));
            }
        }
        if(parsed.isEmpty())throw invalid(lineNumber,"Input word is empty");
        rows=parsed.toArray(new Row[parsed.size()]);reset();
    }
    private static IllegalArgumentException invalid(int line,String why){return new IllegalArgumentException("Input word line"+line+": "+why);}
    public int size(){return rows.length;}

    /** Match-lifecycle reset only. Caller owns any invalid-setup native key reset;
     * this class neither changes nor cancels the real KeyBinding press FIFO.
     */
    public void reset(){
        anchor=lastTick=movedTick=requestTick=NONE;lastIndex=requestIndex=-1;current=null;
        pulseRequest=active=openingOnly=false;limit=0;admitted.clear();accounted.clear();
        admissions=consumptions=mismatches=missedDeadlines=unexpected=skippedTicks=0;
    }
    private int index(long tick){
        if(anchor==NONE||tick<anchor)return -1;
        long value=tick-anchor;return value>Integer.MAX_VALUE?Integer.MAX_VALUE:(int)value;
    }
    private void missed(int index){if(index>=0&&!accounted.get(index)){accounted.set(index);missedDeadlines++;}}

    /** Pulse rows describe DESIRED consumption at that row's input HEAD. While
     * processing row i, request row i+1's click; only a later frame after this
     * row's movement may admit it. Gate rows retain ordinary persistent polling.
     * openingOnly hands back to Wtap after openingTicks; full-word end stays
     * active but safely idle, so a generic default policy cannot take over.
     */
    public Decision decide(long nativeTick,int openingTicks,boolean openingOnly){
        if(nativeTick<0||openingTicks<0||openingTicks>rows.length)throw new IllegalArgumentException("Invalid input tick or opening length");
        int requestedLimit=openingOnly?openingTicks:rows.length;
        if(anchor==NONE){anchor=nativeTick;this.openingOnly=openingOnly;limit=requestedLimit;}
        else{
            if(this.openingOnly!=openingOnly||limit!=requestedLimit)throw new IllegalArgumentException("Reset before changing word scope");
            if(nativeTick<=lastTick)throw new IllegalArgumentException("One increasing own input HEAD per decide");
            if(nativeTick>lastTick+1){skippedTicks+=nativeTick-lastTick-1;mismatches++;}
        }
        int next=index(nativeTick);
        if(pulseRequest&&requestTick<=nativeTick)missed(requestIndex);
        pulseRequest=false;requestTick=NONE;requestIndex=-1;
        // Missing input heads do not pause or replay the word. Count desired but
        // unadmitted pulse slots; admitted-late tickets remain in their FIFO.
        for(int i=Math.min(lastIndex,limit-1)+1;i<=next&&i<limit;i++)if(rows[i].mode.equals("pulse")&&rows[i].click)missed(i);
        lastTick=nativeTick;lastIndex=next;movedTick=NONE;
        if(next>=limit){
            current=null;active=!openingOnly;
            return new Decision(0,0,next,false,active,openingOnly?"handoff":"end");
        }
        current=rows[next];active=true;
        if(current.mode.equals("pulse")&&next+1<limit&&rows[next+1].click){
            pulseRequest=true;requestTick=nativeTick+1;requestIndex=next+1;
        }
        boolean gate=current.mode.equals("gate")?current.click:pulseRequest;
        return new Decision(current.key,current.side,next,gate,true,current.mode);
    }

    /** Call only for this controller's own local player, after native travel. */
    public void movementCompleted(long nativeTick){if(anchor!=NONE&&nativeTick==lastTick)movedTick=nativeTick;}

    /** Consult after normal GUI/readiness/CPS checks. Deferral changes nothing.
     * Pulse admission must be a real frame after movement, never the synthetic
     * frame poll at native input HEAD. Gate mode retains both normal polls.
     */
    public boolean allowInjection(long nativeTick,boolean nativeTickPoll){
        if(!active||current==null||nativeTick!=lastTick)return false;
        if(current.mode.equals("gate"))return current.click;
        return pulseRequest&&!nativeTickPoll&&movedTick==lastTick&&admitted.isEmpty();
    }

    /** Only after a successful normal KeyBinding.onTick. Never call on CPS defer.
     * Return the admission gate to retain. Already admitted presses are untouched.
     */
    public boolean queued(){
        if(!active||current==null)throw new IllegalStateException("No active word row to admit");
        if(current.mode.equals("gate")){
            if(!current.click)throw new IllegalStateException("Gate is closed");
            admitted.addLast(new Admission(++admissions,-1,lastIndex,"gate"));return true;
        }
        if(!pulseRequest||movedTick!=lastTick||!admitted.isEmpty())throw new IllegalStateException("Pulse has not passed its movement/admission barrier");
        admitted.addLast(new Admission(++admissions,requestTick,requestIndex,"pulse"));accounted.set(requestIndex);pulseRequest=false;return false;
    }

    /** One callback for EVERY native clickMouse invocation, including a MISS or
     * cooldown-suppressed invocation. Never substitute engine attack/landed cue.
     * Consume exactly one admitted ticket; do not clear a newer pulse request.
     */
    public Consumption consumed(long nativeTick){
        consumptions++;Admission ticket=admitted.pollFirst();
        if(ticket==null){unexpected++;mismatches++;return new Consumption(-1,-1,nativeTick,-1,index(nativeTick),false,"unexpected");}
        boolean matched=ticket.desired<0||ticket.desired==nativeTick;if(!matched)mismatches++;
        return new Consumption(ticket.id,ticket.desired,nativeTick,ticket.index,index(nativeTick),matched,ticket.mode);
    }
    public long mismatchCount(){return mismatches;}
    public long missedDeadlineCount(){return missedDeadlines;}
    public long unexpectedConsumptionCount(){return unexpected;}
    public long skippedTickCount(){return skippedTicks;}
    public long admissionCount(){return admissions;}
    public long consumptionCount(){return consumptions;}
    public int pendingAdmissions(){return admitted.size();}
}
