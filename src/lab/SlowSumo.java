package lab;

import java.util.*;

/** The server owns the room speed. Old practice clients also understand the current-rate welcome. */
final class SlowSumo {
    private static double announced;
    static Map<String,Object> clock(long now){
        TrainingClock.Segment s=TrainingClock.at(now),next=TrainingClock.last();
        return Map.of("training_speed",s.rate,"training_wall",s.wall,"training_game_ns",s.gameNs,
                "training_next_wall",next==s?0:next.wall,"training_next_speed",next.rate);
    }
    private static void broadcast(){
        for(Object p:Referee.players())if(PovLink.subscribed(R.name(p))){
            String name=R.name(p);Referee.Match m=Referee.find(name);
            Map<String,Object> data=new LinkedHashMap<>(clock(System.currentTimeMillis()));
            data.put("type","welcome");data.put("version",1);data.put("match",m==null?"":m.id);
            PovLink.send(p,data);
        }
    }
    static void tick(){
        double rate=TrainingClock.rate();
        if(announced==rate)return;
        if(announced==0)Referee.writeJson(Referee.DATA.resolve("training-clock.json"),clock(System.currentTimeMillis()));
        announced=rate;broadcast();
    }
    static void command(Object sender,String[] args)throws Exception{
        if(!TrainingClock.ROOM){Referee.message(sender,"Slow motion is available in the training room at 127.0.0.1:25585.");return;}
        if(args.length==1){
            Referee.message(sender,"Room speed "+TrainingClock.rate()+"×. Choose a speed or type /slowmo 0.35.");
            for(String value:new String[]{"0.1","0.25","0.5","0.75","1"})Referee.button(sender,value+"×","/slowmo "+value);
            return;
        }
        double rate;
        try{rate=Double.parseDouble(args[1]);}catch(NumberFormatException ex){rate=Double.NaN;}
        if(args.length!=2||!TrainingClock.valid(rate))throw new IllegalArgumentException("Use /slowmo <0.10 to 1.00>.");
        if(TrainingClock.last().rate==rate){Referee.message(sender,"Room speed is already set to "+rate+"×.");return;}
        long now=System.currentTimeMillis();
        double ping=0;
        for(Object p:Referee.players()){
            Properties proxy=R.properties(Referee.proxyPath(R.name(p)));
            ping=Math.max(ping,Double.parseDouble(proxy.getProperty("rtt_ms","0")));
        }
        // Give an already connected client a one-way trip and two input ticks to receive the switch.
        long effective=now+250+(long)Math.ceil((ping/2+100)/TrainingClock.rate());
        Map<Referee.Match,Long> upcoming=new HashMap<>();
        for(Referee.Match m:Referee.matches.values())if(m.start>now)upcoming.put(m,TrainingClock.gameMillis(m.start));
        TrainingClock.schedule(effective,rate);
        Map<String,Object> plan=clock(now);
        Referee.writeJson(Referee.DATA.resolve("training-clock.json"),plan);
        for(var entry:upcoming.entrySet()){
            entry.getKey().start=TrainingClock.wallTimestamp(entry.getValue());
            PovArchives.manifest(entry.getKey(),false);
        }
        for(Referee.Match m:Referee.matches.values())
            Referee.append(m.replay,Map.of("type","training_speed_change","time",now,"effective_wall_ms",effective,"rate",rate));
        broadcast();
        for(Object p:Referee.players())if(!Referee.isBot(R.name(p)))Referee.message(p,"Sumo speed → "+rate+"×");
        if(!Referee.players().contains(sender))Referee.message(sender,"Sumo speed → "+rate+"×");
    }
}
