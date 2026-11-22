package practice.core.replay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

/** Local capture reader only. It never connects, sends, changes files or infers
 * server damage acceptance from client animations. All times are milliseconds
 * from this own-client stream's monotonic origin when available. */
public final class ReplayRecording {
    public static final class Entry {
        public final String id,label,server;
        public final long startMs,preferredStartMs,finishMs;
        public final double durationMs;
        public final Path firstFile,archiveFolder;
        public final boolean midConnection,durationEstimated;
        private final Path lastFile;
        public Entry(String id,String label,String server,long startMs,double durationMs,Path firstFile,boolean midConnection,Path archiveFolder){
            this(id,label,server,startMs,durationMs,firstFile,midConnection,archiveFolder,false,startMs,0);
        }
        private Entry(String id,String label,String server,long startMs,double durationMs,Path firstFile,boolean midConnection,Path archiveFolder,boolean estimated,long preferred,long finish){
            this(id,label,server,startMs,durationMs,firstFile,midConnection,archiveFolder,estimated,preferred,finish,null);
        }
        private Entry(String id,String label,String server,long startMs,double durationMs,Path firstFile,boolean midConnection,
                      Path archiveFolder,boolean estimated,long preferred,long finish,Path lastFile){
            this.id=id;this.label=label;this.server=server;this.startMs=startMs;this.durationMs=durationMs;
            this.firstFile=firstFile.toAbsolutePath().normalize();this.midConnection=midConnection;this.archiveFolder=archiveFolder;durationEstimated=estimated;preferredStartMs=preferred;finishMs=finish;
            this.lastFile=lastFile==null?null:lastFile.toAbsolutePath().normalize();
        }
        public boolean isSumo(){return id.startsWith("sumo:");}
    }
    public static final class Wire {
        public final double atMs,wireAtMs;
        public final boolean inbound,applicationTimed;
        public final byte[] bytes;
        /** packetId is the observer's identity, not the protocol packet type. */
        public final long packetId;
        public final int protocolId;
        public final String kind;
        Wire(double at,double wire,boolean inbound,boolean applied,byte[] bytes,long id,int protocol,String kind){
            atMs=at;wireAtMs=wire;this.inbound=inbound;applicationTimed=applied;this.bytes=bytes.clone();packetId=id;protocolId=protocol;this.kind=kind;
        }
    }
    public static final class Pose {
        public final double atMs;
        public final Map<String,Object> state,self,opponent;
        Pose(double at,Map<String,Object> frozenState){atMs=at;state=frozenState;self=object(state.get("self"));opponent=object(state.get("opponent"));}
    }
    public static final class Mark {
        public final double atMs;
        public final String kind,label;
        public final Map<String,Object> details;
        Mark(double at,String kind,String label,Map<String,Object> details){atMs=at;this.kind=kind;this.label=label;this.details=immutableMap(details);}
    }
    public static final class Limits {
        public final long wireBytes,inflatedBytes,retainedPoseBytes;
        public final int packetBytes,rows,parts,poses;
        public Limits(long wireBytes,long inflatedBytes,int packetBytes,int rows,int parts,int poses){
            this(wireBytes,inflatedBytes,packetBytes,rows,parts,poses,32L*1024*1024);
        }
        public Limits(long wireBytes,long inflatedBytes,int packetBytes,int rows,int parts,int poses,long retainedPoseBytes){
            if(wireBytes<1||inflatedBytes<1||packetBytes<1||rows<1||parts<1||poses<1||retainedPoseBytes<1)throw new IllegalArgumentException("Positive replay limits required");
            this.wireBytes=wireBytes;this.inflatedBytes=inflatedBytes;this.packetBytes=packetBytes;this.rows=rows;this.parts=parts;this.poses=poses;this.retainedPoseBytes=retainedPoseBytes;
        }
        /** Bounded scale in 512 MiB heap units; packet and part limits stay fixed. */
        public static Limits forHeap(long bytes){
            int scale=(int)Math.max(1,Math.min(8,Math.round(bytes/(512.0*1024*1024))));
            return new Limits(128L*1024*1024*scale,1024L*1024*1024*scale,2*1024*1024,2000000*scale,10000,10000*scale,32L*1024*1024*scale);
        }
        public static Limits defaults(){return forHeap(Runtime.getRuntime().maxMemory());}
    }
    public final Entry entry;
    public final long startMs;
    public final double durationMs;
    public final List<Wire> packets;
    public final List<Pose> poses;
    public final List<Mark> timeline;
    private final NavigableMap<Double,Boolean> sprintHits=new TreeMap<>();
    private final ReplayCoachFeedback.Reader coach;
    private final double coachOrigin;
    public Map<String,Object> coachAt(double ms){return coach.at(coachOrigin+ms);}
    public Boolean sprintHitAt(double ms){
        Map.Entry<Double,Boolean> hit=sprintHits.floorEntry(ms);
        return hit!=null&&ms-hit.getKey()<practice.replay.SprintHit.DISPLAY_MS?hit.getValue():null;
    }
    public final List<String> warnings;
    public final boolean hasJoin;
    public final int selfId;
    public final boolean hasAttackSamples;
    public final ReplayMotion motion;
    public final ReplaySumoClip sumo;
    public final ReplayInputs inputs;
    private final List<Wire> inbound;
    private final double[] ticks;
    private final ReplayRecoil recoil;
    private ReplayRecording(Entry entry,long start,double duration,List<Wire> packets,List<Pose> poses,List<Mark> marks,List<String> warnings,boolean join,int selfId,List<Double> ticks,boolean hasAttackSamples,List<ReplayMotion.Sample> motionSamples,ReplaySumoClip sumo,ReplayInputs inputs,ReplayCoachFeedback.Reader coach,double coachOrigin){
        this.entry=entry;startMs=start;durationMs=duration;this.packets=Collections.unmodifiableList(packets);this.poses=Collections.unmodifiableList(poses);
        timeline=Collections.unmodifiableList(marks);this.warnings=Collections.unmodifiableList(new ArrayList<String>(warnings));hasJoin=join;this.selfId=selfId;
        this.hasAttackSamples=hasAttackSamples;
        this.sumo=sumo;
        this.inputs=inputs;
        this.coach=coach;this.coachOrigin=coachOrigin;
        for(Mark m:marks)if("sprint_hit".equals(m.kind))sprintHits.put(m.atMs,(Boolean)m.details.get("sprint"));
        recoil=new ReplayRecoil(marks,sumo==null?Math.max(0,entry.preferredStartMs-start):sumo.startMs);
        motion=new ReplayMotion(motionSamples,marks,poses);
        List<Wire> in=new ArrayList<Wire>();for(Wire w:packets)if(w.inbound)in.add(w);inbound=Collections.unmodifiableList(in);
        this.ticks=new double[ticks.size()];for(int i=0;i<ticks.size();i++)this.ticks[i]=ticks.get(i);
    }
    public List<Wire> inboundPackets(){return inbound;}
    public double playStart(){return sumo==null?0:sumo.startMs;}
    public double playEnd(){return sumo==null?durationMs:sumo.finishMs;}
    public ReplayRecoil.Count recoilAt(double ms){return recoil.at(ms);}
    public boolean attackScaleFlash(double ms){return recoil.flashingAt(ms);}
    /** Last recorded pose; no extrapolation or fabricated entity state. */
    public Pose poseAt(double ms){int index=afterPose(ms);return index==0?null:poses.get(index-1);}
    public Pose nextPose(double ms){int index=afterPose(ms);return index==poses.size()?null:poses.get(index);}
    private int afterPose(double ms){
        int lo=0,hi=poses.size();
        while(lo<hi){int mid=(lo+hi)>>>1;if(poses.get(mid).atMs<=ms)lo=mid+1;else hi=mid;}
        return lo;
    }
    /** Next recorded own tick_end, clamped to the recording end. */
    public double tickStep(double ms){int lo=0,hi=ticks.length;while(lo<hi){int m=(lo+hi)>>>1;if(ticks[m]<=ms)lo=m+1;else hi=m;}return lo<ticks.length?ticks[lo]:durationMs;}
    public double previousTick(double ms){int lo=0,hi=ticks.length;while(lo<hi){int m=(lo+hi)>>>1;if(ticks[m]<ms)lo=m+1;else hi=m;}return lo>0?ticks[lo-1]:0;}

    public static List<Entry> catalog(Path captures,Path archive,Function<String,Map<String,Object>> parser)throws IOException{
        Objects.requireNonNull(parser,"JSON parser");List<Entry> entries=new ArrayList<Entry>();
        List<Path> captured=captures!=null&&Files.isDirectory(captures)?files(captures):Collections.<Path>emptyList();
        for(Path p:captured){
            if(firstSegment(p)&&p.toString().endsWith(".jsonl.gz")){
                Map<String,Object> header=header(p,parser),data=object(header.get("data"));
                long modified=Files.getLastModifiedTime(p).toMillis(),start=integer(header,"time_ms",modified);
                String name=p.getFileName().toString(),prefix=name.substring(0,name.length()-".jsonl.gz".length())+"-part",suffix=".jsonl.gz";
                String server=text(data,"server_host","local capture");
                boolean mid=bool(data,"started_mid_connection",true);
                List<Path> segments=new ArrayList<>();
                segments.add(p);
                for(Path sibling:captured){String part=sibling.getFileName().toString();if(part.startsWith(prefix)&&part.endsWith(suffix)&&part.substring(prefix.length(),part.length()-suffix.length()).matches("[0-9]+")){segments.add(sibling);modified=Math.max(modified,Files.getLastModifiedTime(sibling).toMillis());}}
                if(name.startsWith("a-client-")&&segments.size()>24){
                    addRemoteClips(entries,segments,prefix,server,start,mid,parser);
                }else{
                    entries.add(new Entry("capture:"+name,name,server,start,Math.max(0,modified-start),p,mid,null,true,start,0));
                }
            }
            if(entries.size()>=4096)break;
        }
        if(captures!=null)entries.addAll(sumoEntries(captures));
        if(archive!=null&&Files.isDirectory(archive))for(Path folder:files(archive)){
            Entry entry=archiveEntry(folder,parser);
            if(entry!=null)entries.add(entry);
            if(entries.size()>=4096)break;
        }
        Collections.sort(entries,(a,b)->{int c=Long.compare(b.startMs,a.startMs);return c!=0?c:a.id.compareTo(b.id);});return Collections.unmodifiableList(entries);
    }
    public static Entry archiveEntry(Path folder,Function<String,Map<String,Object>> parser)throws IOException{
        return archiveEntry(folder,parser,true);
    }
    public static Entry archiveEntry(Path folder,Function<String,Map<String,Object>> parser,boolean useFullSession)throws IOException{
        if(!Files.isDirectory(folder)||!folder.getFileName().toString().matches("m[0-9]+|r[0-9a-fA-F]+"))return null;
        Path mp=folder.resolve("manifest.json");Map<String,Object> manifest=Collections.emptyMap();
        if(Files.isRegularFile(mp)&&Files.size(mp)<=1024*1024)try{manifest=parse(parser,new String(Files.readAllBytes(mp),StandardCharsets.UTF_8));}catch(RuntimeException ignored){}
        List<Path> archived=files(folder);
        Path first=null;for(Path p:archived)if(firstSegment(p)&&p.getFileName().toString().matches("a-client-.*-events\\.jsonl\\.gz")){first=p;break;}
        if(first==null)for(Path p:archived)if(firstSegment(p)&&p.getFileName().toString().matches("b-client-.*-events\\.jsonl\\.gz")){first=p;break;}
        if(first==null)return null;
        Map<String,Object> header=header(first,parser),metadata=object(header.get("metadata"));
        if(metadata.isEmpty())metadata=object(manifest.get("metadata"));if(metadata.isEmpty())metadata=object(header.get("data"));
        boolean mid=bool(metadata,"started_mid_connection",true);String full=text(metadata,"full_session_capture","");
        if(useFullSession&&!full.isEmpty())try{
            Path candidate=Paths.get(full);
            if(!candidate.isAbsolute())candidate=first.getParent().resolve(candidate);
            if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jsonl.gz")){
                Map<String,Object> fullHeader=header(candidate,parser);
                if(!fullHeader.isEmpty()){
                    first=candidate;header=fullHeader;mid=bool(object(header.get("data")),"started_mid_connection",true);
                }
            }
        }catch(InvalidPathException ignored){}
        long start=integer(header,"time_ms",integer(manifest,"start_ms",0)),finish=integer(manifest,"finish_ms",integer(manifest,"last_received_sample_ms",start));
        String id=folder.getFileName().toString(),server=text(metadata,"server_host",text(object(header.get("data")),"server_host","local lab"));
        long preferred=integer(manifest,"start_ms",start);
        return new Entry(id,id+" — "+server,server,start,Math.max(0,finish-start),first,mid,folder,false,preferred,finish>start?finish:0);
    }
    /** Filename-only catalogue; supply a JSON parser for session metadata. */
    public static List<Entry> catalog(Path captures,Path archive)throws IOException{
        return catalog(captures,archive,s->Collections.<String,Object>emptyMap());
    }
    public static Entry sumoEntry(Path captures,MmcSumoReplay.Match match){
        if(match==null)return null;
        Path first=match.firstSegment(captures);
        if(!Files.isRegularFile(first))return null;
        return new Entry("sumo:"+match.id,"Sumo vs "+match.opponent+(match.won?" · Win":" · Loss"),"MMC",
                match.start,match.finish-match.start,first,match.part>1,null,false,match.start,match.finish);
    }
    public static List<Entry> sumoEntries(Path captures)throws IOException{
        Path sumo=captures.resolveSibling("sumo-replays");
        List<Entry> matches=new ArrayList<>();
        if(Files.isDirectory(sumo))for(Path file:files(sumo)){
            String filename=file.getFileName().toString();
            if(!filename.endsWith(".properties"))continue;
            try{
                Entry match=sumoEntry(captures,MmcSumoReplay.read(sumo,filename.substring(0,filename.length()-11)));
                if(match!=null)matches.add(match);
            }catch(IOException|RuntimeException ignored){}
            if(matches.size()>=4096)break;
        }
        matches.sort(Comparator.comparingLong(e->e.startMs));
        return matches;
    }
    private static List<Path> files(Path folder)throws IOException{List<Path> out=new ArrayList<Path>();try(DirectoryStream<Path> stream=Files.newDirectoryStream(folder)){for(Path p:stream)out.add(p);}Collections.sort(out);return out;}
    private static boolean firstSegment(Path p){return !p.getFileName().toString().matches(".*-part[0-9]+\\.jsonl\\.gz");}
    private static void addRemoteClips(List<Entry> entries,List<Path> segments,String prefix,String server,long start,
                                       boolean mid,Function<String,Map<String,Object>> parser)throws IOException{
        segments.subList(1,segments.size()).sort(Comparator.comparingInt(p->partNumber(p,prefix)));
        int clipSize=12;
        for(int from=0;from<segments.size()&&entries.size()<4096;){
            int end=from+1;
            while(end<segments.size()&&end-from<clipSize&&partNumber(segments.get(end),prefix)==partNumber(segments.get(end-1),prefix)+1)end++;
            Path first=segments.get(from),last=segments.get(end-1);
            long clipStart=from==0?start:segmentTime(first,parser);
            int number=partNumber(first,prefix);
            entries.add(new Entry("capture:"+first.getFileName(),"Session clip · part "+number,server,clipStart,
                    Math.max(0,Files.getLastModifiedTime(last).toMillis()-clipStart),first,mid||from>0,null,
                    true,clipStart,0,last));
            from=end;
        }
    }
    private static int partNumber(Path segment,String prefix){
        String name=segment.getFileName().toString();
        if(!name.startsWith(prefix))return 1;
        try{return Integer.parseInt(name.substring(prefix.length(),name.length()-".jsonl.gz".length()));}
        catch (NumberFormatException malformed){return Integer.MAX_VALUE;}
    }
    private static long segmentTime(Path path,Function<String,Map<String,Object>> parser)throws IOException{
        try(BufferedReader in=reader(path,new Budget(256*1024))){
            for(int n=0;n<4;n++){
                String text=line(in);if(text==null)break;
                long time=integer(parse(parser,text),"time_ms",0);
                if(time>0)return time;
            }
        }catch(IOException|RuntimeException ignored){}
        return Files.getLastModifiedTime(path).toMillis();
    }
    private static Map<String,Object> header(Path path,Function<String,Map<String,Object>> parser){
        try(BufferedReader in=reader(path,new Budget(256*1024))){for(int n=0;n<32;n++){String line=line(in);if(line==null)break;try{Map<String,Object> row=parse(parser,line);if("capture_start".equals(row.get("type"))||"pov_archive_start".equals(row.get("type")))return row;}catch(RuntimeException ignored){}}}catch(IOException ignored){}return Collections.emptyMap();
    }
    private static final class Stamp {final long ns,wall;Stamp(Map<String,Object> r){ns=integer(r,"monotonic_ns",Long.MIN_VALUE);wall=integer(r,"time_ms",0);}}
    private static final class RawWire {Stamp stamp;boolean in;byte[] bytes;long id;int protocol;String kind;}
    private static final class RawPose {Stamp stamp;Map<String,Object> state;String type;RawPose(Stamp s,Map<String,Object> m,String type){stamp=s;state=m;this.type=type;}}
    private static final class RawInput {
        final Stamp stamp;
        final ReplayInputs.Key key;
        final boolean down;
        RawInput(Stamp stamp,ReplayInputs.Key key,boolean down){this.stamp=stamp;this.key=key;this.down=down;}
    }
    private static final class RawContact {
        final Stamp before,after;
        final Map<String,Object> details;
        RawContact(Stamp before,Stamp after,Map<String,Object> details){this.before=before;this.after=after;this.details=details;}
    }
    private static final class RawMotion {
        final Stamp stamp;
        final ReplayMotion.Sample sample;
        RawMotion(Stamp stamp,ReplayMotion.Sample sample){this.stamp=stamp;this.sample=sample;}
    }
    private static final class Origin {
        long ns=Long.MAX_VALUE,wall,latestNs=Long.MIN_VALUE,latestWall;
        void add(Stamp s){latestWall=Math.max(latestWall,s.wall);if(s.ns!=Long.MIN_VALUE){latestNs=Math.max(latestNs,s.ns);if(s.ns<ns){ns=s.ns;wall=s.wall;}}}
        double at(Stamp s){return s.ns!=Long.MIN_VALUE&&ns!=Long.MAX_VALUE?(s.ns-ns)/1000000.0:s.wall-wall;}
        double span(){return ns==Long.MAX_VALUE?Math.max(0,latestWall-wall):Math.max(0,(latestNs-ns)/1000000.0);}
    }
    private static final class Budget {final long limit;long used;Budget(long n){limit=n;}void add(int n)throws IOException{used+=n;if(used>limit)throw new IOException("Replay inflated-byte limit exceeded ("+limit+")");}}
    private static BufferedReader reader(Path p,final Budget budget)throws IOException{
        InputStream zip=new GZIPInputStream(Files.newInputStream(p),65536);
        InputStream bounded=new FilterInputStream(zip){
            int lineBytes;
            private void byteRead(int value)throws IOException{
                if(value=='\n')lineBytes=0;
                else if(++lineBytes>16*1024*1024)throw new IOException("Replay line exceeds4MiB");
            }
            public int read()throws IOException{
                int n=super.read();
                if(n>=0){budget.add(1);byteRead(n);}
                return n;
            }
            public int read(byte[] b,int o,int l)throws IOException{
                int n=in.read(b,o,l);
                if(n>0){
                    budget.add(n);
                    for(int i=o;i<o+n;i++)byteRead(b[i]);
                }
                return n;
            }
        };
        return new BufferedReader(new InputStreamReader(bounded,StandardCharsets.UTF_8),16384);
    }
    private static String line(BufferedReader reader)throws IOException{
        String line=reader.readLine();
        if(line!=null&&line.length()>4*1024*1024)throw new IOException("Replay line exceeds4MiB");
        return line;
    }
    static List<Wire> earlierChunks(Path first,int x,int z,Function<String,Map<String,Object>> parser){
        String name=first.getFileName().toString();
        int split=name.lastIndexOf("-part");
        if(split<0)return Collections.emptyList();
        int part=partNumber(first,name.substring(0,split+5));
        if(part<2||part>10000)return Collections.emptyList();
        String base=name.substring(0,split);
        Budget budget=new Budget(384L*1024*1024);
        for(int index=part-1;index>0&&index>=part-120;index--){
            Path file=first.resolveSibling(base+(index==1?"":"-part"+index)+".jsonl.gz");
            List<Wire> nearby=new ArrayList<>();
            boolean center=false;
            if(!Files.isRegularFile(file))continue;
            try(BufferedReader in=reader(file,budget)){
                for(String text;(text=line(in))!=null;){
                    if(!text.contains("\"packet_wire\"")||!(text.contains("S21PacketChunkData")||text.contains("S26PacketMapChunkBulk")))continue;
                    try{
                        Map<String,Object> row=parse(parser,text),data=object(row.get("data")),meta=object(data.get("packet"));
                        if(!"in".equals(meta.get("direction")))continue;
                        String encoded=text(data,"base64","");
                        if(encoded.isEmpty()||encoded.length()>2800000)continue;
                        byte[] bytes=Base64.getDecoder().decode(encoded);
                        int protocol=new Cursor(bytes).varint();
                        Wire packet=new Wire(0,0,true,false,bytes,integer(meta,"packet_id",-1),protocol,text(meta,"kind",""));
                        if(!ReplaySumoClip.fullChunk(packet,x,z,4))continue;
                        if(nearby.size()==16)nearby.remove(0);
                        nearby.add(packet);
                        center|=ReplaySumoClip.fullChunk(packet,x,z,0);
                    }catch(RuntimeException|IOException malformed){}
                }
            }catch(IOException unavailable){break;}
            if(center)return nearby;
        }
        return Collections.emptyList();
    }
    private static final class Warnings {
        final List<String> values=new ArrayList<String>();int omitted;
        void add(String s){if(values.size()<64)values.add(s);else omitted++;}
        List<String> finish(){if(omitted>0)values.add(omitted+" additional warnings omitted");return values;}
    }

    public static ReplayRecording load(Entry entry,Function<String,Map<String,Object>> parser)throws IOException{return load(entry,parser,Limits.defaults());}
    public static ReplayRecording load(Entry entry,Function<String,Map<String,Object>> parser,Limits limits)throws IOException{
        Objects.requireNonNull(entry);Objects.requireNonNull(parser);Objects.requireNonNull(limits);
        List<RawWire> wires=new ArrayList<RawWire>();List<RawPose> samples=new ArrayList<RawPose>();List<Stamp> tickStamps=new ArrayList<Stamp>();
        List<RawContact> contacts=new ArrayList<>();
        // archiveEntry selects the A stream when present, otherwise B; it can then
        // substitute that same client's full session. A bare remote log has no lab opponent.
        String side="a";
        if(entry.archiveFolder!=null&&Files.isDirectory(entry.archiveFolder))try(java.util.stream.Stream<Path> names=Files.list(entry.archiveFolder)){
            if(!names.anyMatch(p->firstSegment(p)&&p.getFileName().toString().matches("a-client-.*-events\\.jsonl\\.gz")))side="b";
        }
        ReplayCoachFeedback.Reader coach=new ReplayCoachFeedback.Reader(ServerReplayInputs.opponent(entry.archiveFolder,side,parser));
        List<RawMotion> motionSamples=new ArrayList<>();
        List<RawInput> inputEvents=new ArrayList<>();
        Map<Stamp,Boolean> sprintHits=new LinkedHashMap<>();
        Map<String,Object> pendingClick=null;
        Stamp clickStamp=null;
        boolean hasAttackSamples=false,screenOpen=false;
        Map<Long,Stamp> applies=new HashMap<Long,Stamp>();Set<Long> ambiguous=new HashSet<Long>();Origin origin=new Origin();Warnings warnings=new Warnings();
        Budget budget=new Budget(limits.inflatedBytes);Set<Path> visited=new HashSet<Path>();Path part=entry.firstFile;
        long wireBytes=0,poseBytes=0,lastFallbackNs=Long.MIN_VALUE;int rows=0,parts=0,skippedPoseSamples=0;boolean captureMid=entry.midConnection,clipped=false,hasTickPoses=false;
        while(part!=null){
            part=part.toAbsolutePath().normalize();if(!visited.add(part))throw new IOException("Replay segment cycle");if(++parts>limits.parts)throw new IOException("Replay part limit exceeded");
            if(!Files.isRegularFile(part)){warnings.add("Missing linked segment: "+part.getFileName());break;}
            String next=null;boolean footer=false;
            try(BufferedReader in=reader(part,budget)){
                String text;while((text=line(in))!=null){
                    if(++rows>limits.rows)throw new IOException("Replay row limit exceeded");
                    if(Thread.currentThread().isInterrupted())throw new IOException("Replay load canceled");
                    if(entry.isSumo()&&hasTickPoses&&(text.startsWith("{\"type\":\"render_view\"")
                            ||text.startsWith("{\"type\":\"remote_pose\""))){
                        skippedPoseSamples++;continue;
                    }
                    Map<String,Object> row;
                    try{row=parse(parser,text);}catch(RuntimeException malformed){warnings.add("Malformed JSON row in "+part.getFileName());continue;}
                    String type=text(row,"type","");Map<String,Object> data=object(row.get("data"));Stamp stamp=new Stamp(row);
                    if(entry.finishMs>0&&stamp.wall>entry.finishMs+(entry.isSumo()?750:0)){clipped=true;break;}
                    if(!coach.opponent.isEmpty()||type.equals("sumo_coach_cue"))
                        coach.accept(type,stamp.ns==Long.MIN_VALUE?stamp.wall:stamp.ns/1e6,row,data);
                    if("packet_oversize".equals(type)){warnings.add("Capture omitted an oversize wire packet ("+integer(data,"bytes",0)+" bytes)");continue;}
                    if("capture_end".equals(type)){footer=true;next=text(row,"continues",null);if(!bool(row,"writer_complete",false)||integer(row,"dropped",0)>0||bool(row,"size_capped",false))warnings.add("Incomplete/dropped capture segment: "+part.getFileName());continue;}
                    if("capture_start".equals(type)){origin.add(stamp);captureMid=bool(data,"started_mid_connection",true);continue;}
                    if("link_sprint_hit".equals(type)&&data.get("sprint") instanceof Boolean){
                        origin.add(stamp);sprintHits.put(stamp,(Boolean)data.get("sprint"));continue;
                    }
                    if(type.equals("key")||type.equals("mouse")){
                        ReplayInputs.Key key=type.equals("key")&&!bool(data,"repeat",false)
                                ?ReplayInputs.Key.keyboard((int)integer(data,"key",-1))
                                :type.equals("mouse")?ReplayInputs.Key.mouse((int)integer(data,"button",-1)):null;
                        if(key!=null&&data.get("down") instanceof Boolean){
                            origin.add(stamp);
                            inputEvents.add(new RawInput(stamp,key,bool(data,"down",false)));
                        }
                        continue;
                    }
                    if(type.equals("keystrokes_reset")){
                        origin.add(stamp);
                        inputEvents.add(new RawInput(stamp,null,false));
                        continue;
                    }
                    if(type.equals("tick_end")&&data.containsKey("screen")){
                        boolean open=data.get("screen")!=null;
                        if(open&&!screenOpen)inputEvents.add(new RawInput(stamp,null,false));
                        screenOpen=open;
                    }
                    if(type.equals("tick_start")||type.equals("tick_end")||type.startsWith("motion_")||type.startsWith("attack_click_")){
                        ReplayMotion.Sample motion=ReplayMotion.read(type,data);
                        if(motion!=null){origin.add(stamp);motionSamples.add(new RawMotion(stamp,motion));}
                    }
                    if("attack_click_before".equals(type)){
                        pendingClick=row;clickStamp=stamp;continue;
                    }
                    if("attack_click_after".equals(type)){
                        if(pendingClick!=null&&stamp.ns>=clickStamp.ns&&stamp.wall>=clickStamp.wall
                                &&integer(pendingClick,"client_tick",-1)==integer(row,"client_tick",-1)
                                &&Objects.equals(pendingClick.get("thread"),row.get("thread"))){
                            Map<String,Object> before=object(pendingClick.get("data"));
                            hasAttackSamples|=!object(before.get("self")).isEmpty()&&!object(data.get("self")).isEmpty();
                            Map<String,Object> reduction=ReplayRecoil.reduction(before,data);
                            if(!reduction.isEmpty()){
                                origin.add(clickStamp);origin.add(stamp);
                                contacts.add(new RawContact(clickStamp,stamp,reduction));
                            }
                        }
                        pendingClick=null;clickStamp=null;continue;
                    }
                    if("packet_handler_enter".equals(type)&&bool(data,"main_thread",false)){
                        origin.add(stamp);long id=integer(data,"packet_id",-1);if(id>=0){if(applies.containsKey(id))ambiguous.add(id);else applies.put(id,stamp);}continue;
                    }
                    if("packet_wire".equals(type)){
                        Map<String,Object> packet=object(data.get("packet"));if(packet.isEmpty())packet=data;
                        String direction=text(packet,"direction","");if(!direction.equals("in")&&!direction.equals("out")){warnings.add("Packet without client direction skipped");continue;}
                        String phase=text(packet,"state",text(packet,"phase","PLAY"));if(!phase.equalsIgnoreCase("PLAY")){warnings.add("Non-PLAY packet skipped");continue;}
                        String encoded=text(data,"base64",text(packet,"base64",""));if(encoded.length()>4L*((limits.packetBytes+2L)/3L)){warnings.add("Oversize wire packet skipped");continue;}
                        byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException bad){warnings.add("Invalid packet base64 skipped");continue;}
                        if(bytes.length>limits.packetBytes){warnings.add("Oversize wire packet skipped");continue;}if(bytes.length==0){warnings.add("Empty wire packet skipped");continue;}
                        wireBytes+=bytes.length;if(wireBytes>limits.wireBytes)throw new IOException("Replay loaded-wire-byte limit exceeded ("+limits.wireBytes+")");
                        RawWire w=new RawWire();w.stamp=stamp;w.in=direction.equals("in");w.bytes=bytes;w.id=integer(packet,"packet_id",-1);w.kind=text(packet,"kind","");
                        try{w.protocol=new Cursor(bytes).varint();}catch(IOException malformed){warnings.add("Malformed packet ID skipped");continue;}origin.add(stamp);wires.add(w);continue;
                    }
                    Map<String,Object> state=null;
                    if("tick_end".equals(type)||"render_view".equals(type))state=data;
                    else if("remote_pose".equals(type))state=object(data.get("view"));
                    else if("pov_frame".equals(type))state=object(row.get("view"));
                    if(state!=null&&!object(state.get("self")).isEmpty()){
                        origin.add(stamp);boolean tick="tick_end".equals(type);
                        if(tick){
                            tickStamps.add(stamp);
                            // Once the exact native tick stream exists, render
                            // snapshots are redundant. Never accumulate them.
                            if(!hasTickPoses){skippedPoseSamples+=samples.size();samples.clear();poseBytes=0;hasTickPoses=true;}
                        }else{
                            if(hasTickPoses){skippedPoseSamples++;continue;}
                            long sampleNs=stamp.ns==Long.MIN_VALUE?stamp.wall*1000000L:stamp.ns;
                            if(lastFallbackNs!=Long.MIN_VALUE&&sampleNs-lastFallbackNs<50000000L){skippedPoseSamples++;continue;}
                            lastFallbackNs=sampleNs;
                        }
                        if(samples.size()>=limits.poses)throw new IOException("Replay pose limit exceeded ("+limits.poses+")");
                        Map<String,Object> compact=compactState(state);poseBytes+=encodedSize(compact);
                        if(poseBytes>limits.retainedPoseBytes)throw new IOException("Replay retained-pose-byte limit exceeded ("+limits.retainedPoseBytes+" encoded-byte estimate)");
                        // Freeze now while the large parsed row is transient;
                        // Pose later transfers this immutable map without copy.
                        samples.add(new RawPose(stamp,immutableMap(compact),type));
                    }
                }
            }catch(EOFException truncated){warnings.add("Truncated gzip segment: "+part.getFileName());}
            if(clipped){if(!entry.id.startsWith("sumo:"))warnings.add("Full session loaded only through this archive's recorded finish");break;}
            if(!footer)warnings.add("Missing capture_end footer: "+part.getFileName());
            if(part.equals(entry.lastFile))break;
            if(!footer){pendingClick=null;clickStamp=null;}
            if(next==null||next.isEmpty())part=null;
            else{Path name;try{name=Paths.get(next);}catch(InvalidPathException bad){throw new IOException("Invalid linked segment name",bad);}if(name.isAbsolute()||name.getNameCount()!=1||next.equals(".")||next.equals(".."))throw new IOException("Linked segment must stay beside its first file");part=part.resolveSibling(name);}
        }
        if(origin.ns==Long.MAX_VALUE){origin.wall=entry.startMs;warnings.add("No monotonic origin; using recorded wall timestamps");}
        if(skippedPoseSamples>0)warnings.add("Pose index uses exact recorded tick_end snapshots, or <=20Hz recorded view samples when ticks are absent; "+skippedPoseSamples+" redundant view samples omitted");
        List<Pose> poses=new ArrayList<Pose>();for(RawPose p:samples){poses.add(new Pose(Math.max(0,origin.at(p.stamp)),p.state));p.state=null;}samples.clear();Collections.sort(poses,Comparator.comparingDouble(p->p.atMs));
        List<Wire> packets=new ArrayList<Wire>();int fallback=0;
        for(RawWire w:wires){Stamp apply=w.in&&!ambiguous.contains(w.id)?applies.get(w.id):null;double raw=Math.max(0,origin.at(w.stamp));double at=apply==null?raw:origin.at(apply);if(at<raw){warnings.add("Handler precedes wire receipt; retaining wire time");apply=null;at=raw;}if(w.in&&apply==null)fallback++;
            packets.add(new Wire(at,raw,w.in,apply!=null,w.bytes,w.id,w.protocol,w.kind));w.bytes=null;}
        Collections.sort(packets,Comparator.comparingDouble(w->w.atMs));
        if(fallback>0)warnings.add(fallback+" inbound packets lack unique main-thread handler time; using wire time");
        List<Double> ticks=new ArrayList<Double>();for(Stamp t:tickStamps)ticks.add(Math.max(0,origin.at(t)));Collections.sort(ticks);
        if(ticks.isEmpty())warnings.add("No recorded own tick_end boundaries");
        int selfId=-1;boolean join=false;for(Wire w:packets)if(w.inbound&&w.protocolId==1)try{Cursor c=new Cursor(w.bytes);c.varint();int id=c.int32();if(!join)selfId=id;join=true;}catch(IOException bad){warnings.add("Malformed JOIN_GAME packet");}
        if(selfId<0)for(Pose pose:poses){int id=(int)integer(pose.self,"id",-1);if(id>=0){selfId=id;break;}}
        if(!join)warnings.add("Mid-connection recording: no JOIN_GAME; world bootstrap may be incomplete");else if(captureMid)warnings.add("Capture marked started mid-connection despite containing JOIN_GAME");
        List<Mark> marks=marks(packets,poses,-1,warnings);
        for(Map.Entry<Stamp,Boolean> hit:sprintHits.entrySet())
            marks.add(new Mark(origin.at(hit.getKey()),"sprint_hit",hit.getValue()?"Sprint KB":"Base KB",Collections.singletonMap("sprint",hit.getValue())));
        for(RawContact contact:contacts){
            contact.details.put("click_start_ms",origin.at(contact.before));
            boolean backward=Boolean.TRUE.equals(contact.details.get("backward"));
            marks.add(new Mark(origin.at(contact.after),backward?"recoil_reduction":"horizontal_attack_scale",
                    backward?"Horizontal KB reduced by attack":"Attack scaled horizontal motion",contact.details));
        }
        marks.sort(Comparator.comparingDouble(m->m.atMs));
        List<ReplayMotion.Sample> motion=new ArrayList<>();
        for(RawMotion raw:motionSamples){raw.sample.at=origin.at(raw.stamp);motion.add(raw.sample);}
        if(motion.isEmpty())for(Pose pose:poses){
            ReplayMotion.Sample sample=ReplayMotion.read("pose",pose.state);
            if(sample!=null){sample.at=pose.atMs;motion.add(sample);}
        }
        double duration=origin.span();for(Wire w:packets)duration=Math.max(duration,w.atMs);for(Pose p:poses)duration=Math.max(duration,p.atMs);for(double t:ticks)duration=Math.max(duration,t);
        ReplaySumoClip sumo=ReplaySumoClip.read(entry,origin.wall,duration,packets,poses,parser);
        List<ReplayInputs.Event> events=new ArrayList<>(inputEvents.size());
        for(RawInput input:inputEvents)events.add(new ReplayInputs.Event(Math.max(0,origin.at(input.stamp)),input.key,input.down));
        for(Pose pose:poses){
            ReplayInputs.Event sprint=ReplayInputs.Event.recordedSprint(pose.atMs,pose.state);
            if(sprint!=null)events.add(sprint);
        }
        return new ReplayRecording(entry,origin.wall,duration,packets,poses,marks,warnings.finish(),join,selfId,ticks,hasAttackSamples,
                motion,sumo,new ReplayInputs(events),coach,origin.ns==Long.MAX_VALUE?origin.wall:origin.ns/1e6);
    }
    private static List<Mark> marks(List<Wire> packets,List<Pose> poses,int initialSelf,Warnings warnings){
        List<Mark> out=new ArrayList<Mark>();Map<String,Double> transactions=new HashMap<String,Double>();Map<Integer,Double> keepalives=new HashMap<Integer,Double>();
        double lastAttack=Double.NaN;int poseIndex=0,self=initialSelf;
        for(Wire w:packets){
            while(poseIndex<poses.size()&&poses.get(poseIndex).atMs<=w.atMs){self=(int)integer(poses.get(poseIndex++).self,"id",self);}
            try{Cursor c=new Cursor(w.bytes);c.varint();Map<String,Object> d=new LinkedHashMap<String,Object>();d.put("packet_id",w.packetId);d.put("time_source",w.applicationTimed?"main_thread_handler":"wire");
                if(w.inbound&&w.protocolId==1){self=c.int32();out.add(new Mark(w.atMs,"join","Recorded JOIN_GAME",d));}
                else if(w.inbound&&(w.protocolId==0x07||w.protocolId==0x08)){d.put("teleport",w.protocolId==0x08);out.add(new Mark(w.atMs,"position_reset","Server position reset",d));}
                else if(!w.inbound&&w.protocolId==2){int entity=c.varint(),action=c.varint();if(action==1){lastAttack=w.atMs;d.put("target_entity_id",entity);d.put("accepted_by_server",null);d.put("server_acceptance","Unknown; this is an outgoing attempt");out.add(new Mark(w.atMs,"attack","Outgoing attack attempt",d));}}
                else if(w.inbound&&w.protocolId==0x12){int entity=c.varint();double x=c.short16()/8000.,y=c.short16()/8000.,z=c.short16()/8000.;d.put("entity_id",entity);d.put("own",entity==self);d.put("velocity",Arrays.asList(x,y,z));if(Double.isFinite(lastAttack)&&w.atMs-lastAttack<=1000){d.put("since_attack_ms",w.atMs-lastAttack);d.put("association","Temporal proximity only; unconfirmed, not a causal hit");}out.add(new Mark(w.atMs,"velocity",entity==self?"Own S12 velocity":"Entity S12 velocity",d));}
                else if(w.inbound&&w.protocolId==0x1a){int entity=c.int32(),status=c.unsigned();if(status==2){d.put("entity_id",entity);d.put("own",entity==self);d.put("server_immunity","Unknown; client hurt cue is not accepted-damage proof");out.add(new Mark(w.atMs,"hurt","Client hurt status2",d));}}
                else if(w.inbound&&w.protocolId==0x27){
                    c.skip(16);int records=c.int32();
                    if(records<0||records>(w.bytes.length-c.at-12)/3)throw new IOException("Explosion record count");
                    c.skip(records*3);
                    d.put("velocity",Arrays.asList(Float.intBitsToFloat(c.int32()),Float.intBitsToFloat(c.int32()),Float.intBitsToFloat(c.int32())));
                    out.add(new Mark(w.atMs,"explosion","Explosion motion packet",d));
                }
                else if(!w.inbound&&w.protocolId==0x0b){
                    int entity=c.varint(),action=c.varint();
                    if(entity==self&&(action==3||action==4)){
                        d.put("action",action==3?"start":"stop");
                        out.add(new Mark(w.atMs,"sprint","Outgoing sprint change",d));
                    }
                }
                else if(!w.inbound&&w.protocolId==0x0e){int window=c.unsigned();c.short16();c.unsigned();int action=c.short16();String id=window+":"+action;if(transactions.containsKey(id))transactions.put(id,Double.NaN);else transactions.put(id,w.atMs);}
                else if(w.inbound&&w.protocolId==0x32){int window=c.unsigned(),action=c.short16();Double sent=transactions.remove(window+":"+action);if(sent!=null&&Double.isFinite(sent)&&w.atMs>=sent){d.put("rtt_ms",w.wireAtMs-sent);d.put("window_id",window);d.put("action_uid",action);d.put("scope","Matched C0E to S32 confirmation; includes server processing");if(w.wireAtMs>=sent)out.add(new Mark(w.atMs,"transaction_rtt","Matched transaction round trip",d));}}
                else if(w.protocolId==0){int id=c.varint();if(w.inbound){if(keepalives.containsKey(id))keepalives.put(id,Double.NaN);else keepalives.put(id,w.wireAtMs);}else{Double received=keepalives.remove(id);if(received!=null&&Double.isFinite(received)&&w.atMs>=received){d.put("client_response_ms",w.atMs-received);d.put("keepalive_id",id);d.put("rtt",false);out.add(new Mark(w.atMs,"keepalive_response","Keepalive client response (not RTT)",d));}}}
            }catch(IOException malformed){warnings.add("Malformed timeline packet0x"+Integer.toHexString(w.protocolId)+" skipped");}
        }return out;
    }
    static final class Cursor {
        final byte[] bytes;int at;
        Cursor(byte[] b){bytes=b;}
        void skip(int n)throws IOException{if(n<0||n>bytes.length-at)throw new EOFException("Packet field missing");at+=n;}
        int unsigned()throws IOException{if(at>=bytes.length)throw new EOFException("Packet field missing");return bytes[at++]&255;}
        int varint()throws IOException{int v=0;for(int n=0;n<5;n++){int b=unsigned();if(n==4&&(b&0xf0)!=0)throw new IOException("VarInt overflow");v|=(b&127)<<(7*n);if((b&128)==0)return v;}throw new IOException("VarInt too long");}
        int short16()throws IOException{return(short)((unsigned()<<8)|unsigned());}
        int int32()throws IOException{return(unsigned()<<24)|(unsigned()<<16)|(unsigned()<<8)|unsigned();}
        double double64()throws IOException{return Double.longBitsToDouble(((long)int32()<<32)|(int32()&0xffffffffL));}
    }
    private static Map<String,Object> selectTarget(Map<String,Object> state){
        Object entities=state.get("entities");if(!(entities instanceof List))return Collections.emptyMap();Map<String,Object> self=object(state.get("self"));long target=integer(object(state.get("ray")),"entity",Long.MIN_VALUE),own=integer(self,"id",Long.MIN_VALUE);Map<String,Object> best=Collections.emptyMap();double distance=36;
        for(Object value:(List<?>)entities){Map<String,Object> e=object(value);long id=integer(e,"id",Long.MIN_VALUE);if(id==target&&id!=Long.MIN_VALUE&&id!=own)return e;if(id==own||!text(e,"kind","").contains("Player"))continue;double d=distanceSquared(self,e);if(d<distance){distance=d;best=e;}}
        return best;
    }
    /** Own/selected-target observations remain complete; unrelated entity
     * snapshots retain visual fields only, not duplicated inventories/attributes. */
    private static Map<String,Object> compactState(Map<String,Object> state){
        Map<String,Object> out=new LinkedHashMap<String,Object>();
        for(String key:Arrays.asList("self","opponent","ray","camera_settings","partial_ticks","sample_begin_ms","sample_begin_ns","sample_end_ms","sample_end_ns","entities_truncated","source_scope","target_names","screen"))if(state.containsKey(key))out.put(key,state.get(key));
        if(state.containsKey("sprint_key_down"))out.put("sprint_key_down",state.get("sprint_key_down"));
        if(state.containsKey("sprint_key_code"))out.put("sprint_key_code",state.get("sprint_key_code"));
        if(object(out.get("opponent")).isEmpty()){Map<String,Object> target=selectTarget(state);if(!target.isEmpty())out.put("opponent",target);}
        if(state.get("entities")instanceof List){
            List<Object> entities=new ArrayList<Object>();for(Object value:(List<?>)state.get("entities")){
                Map<String,Object> raw=object(value),visual=new LinkedHashMap<String,Object>();
                for(String key:Arrays.asList("id","kind","name","uuid","skin_texture","skin_model","pos","previous","render","motion","yaw","pitch","render_yaw","render_pitch","rotationYawHead","renderYawOffset","ground","onGround","sneaking","sprinting","hurtTime","health","swingProgress","prevSwingProgress","limbSwing","limbSwingAmount","eye_height","box","border","width","height","held_item_id"))if(raw.containsKey(key))visual.put(key,raw.get(key));entities.add(visual);
            }out.put("entities",entities);
        }return out;
    }
    /** Upper estimate of retained JSON bytes, not a promise about JVM overhead. */
    private static long encodedSize(Object value){
        if(value==null)return 4;if(value instanceof Boolean)return 5;if(value instanceof Number)return value.toString().length();
        if(value instanceof Map){long n=2;for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())n+=encodedSize(String.valueOf(e.getKey()))+1+encodedSize(e.getValue())+1;return n;}
        if(value instanceof List){long n=2;for(Object v:(List<?>)value)n+=encodedSize(v)+1;return n;}
        long n=2;String s=String.valueOf(value);for(int i=0;i<s.length();i++){char c=s.charAt(i);n+=c<32?6:c=='"'||c=='\\'?2:c<128?1:3;}return n;
    }
    private static double distanceSquared(Map<String,Object> a,Map<String,Object> b){Object p=a.get("pos"),q=b.get("pos");if(!(p instanceof List)||!(q instanceof List)||((List<?>)p).size()<3||((List<?>)q).size()<3)return Double.POSITIVE_INFINITY;double d=0;for(int i=0;i<3;i++){Object x=((List<?>)p).get(i),y=((List<?>)q).get(i);if(!(x instanceof Number)||!(y instanceof Number))return Double.POSITIVE_INFINITY;double z=((Number)x).doubleValue()-((Number)y).doubleValue();d+=z*z;}return d;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object o){return o instanceof Map?(Map<String,Object>)o:Collections.<String,Object>emptyMap();}
    private static Map<String,Object> parse(Function<String,Map<String,Object>> p,String s){Map<String,Object> v=p.apply(s);if(v==null)throw new IllegalArgumentException("Null JSON object");return v;}
    private static String text(Map<String,Object> m,String key,String fallback){Object o=m.get(key);return o instanceof String?(String)o:fallback;}
    private static long integer(Map<String,Object> m,String key,long fallback){Object o=m.get(key);return o instanceof Number?((Number)o).longValue():fallback;}
    private static boolean bool(Map<String,Object> m,String key,boolean fallback){Object o=m.get(key);return o instanceof Boolean?(Boolean)o:fallback;}
    @SuppressWarnings("unchecked") private static Object immutable(Object o){if(o instanceof Map){Map<String,Object> m=new LinkedHashMap<String,Object>();for(Map.Entry<?,?> e:((Map<?,?>)o).entrySet())m.put(String.valueOf(e.getKey()),immutable(e.getValue()));return Collections.unmodifiableMap(m);}if(o instanceof List){List<Object> l=new ArrayList<Object>();for(Object v:(List<?>)o)l.add(immutable(v));return Collections.unmodifiableList(l);}return o;}
    @SuppressWarnings("unchecked") private static Map<String,Object> immutableMap(Map<String,Object> m){return(Map<String,Object>)immutable(m);}
}
