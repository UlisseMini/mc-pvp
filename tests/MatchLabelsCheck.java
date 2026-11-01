package lab;

import java.util.*;

/** Labels must never replace the identities used by scheduling and recorded POVs. */
public final class MatchLabelsCheck {
    public static final class Player {
        final String name;String custom="";
        Player(String name){this.name=name;}
        public String getName(){return name;}
        public void setCustomNameTag(String value){custom=value;}
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        Referee.Match m=new Referee.Match();m.a="LabSlot1";m.b="LabSlot2";m.pa="wtap";m.pb="wtap";
        check(m.label("a").equals("wtap-a")&&m.label("b").equals("wtap-b"),"mirror labels");
        Map<String,Object> state=m.state();
        check(state.get("a").equals("LabSlot1")&&state.get("policy_a").equals("wtap")&&state.get("display_a").equals("wtap-a"),"state keeps identity and display separate");
        check(ReplayData.label(state,"a").equals("wtap-a")&&ReplayData.label(state,"b").equals("wtap-b"),"replay uses live labels");
        state.remove("display_a");state.remove("display_b");
        check(ReplayData.label(state,"a").equals("wtap-a"),"historical replays gain mirror suffixes");
        m.a="Nomophobia";m.pa="human";
        check(m.label("a").equals("Nomophobia")&&m.label("b").equals("wtap"),"human identity and normal bot policy");
        Player bot=new Player("LabSlot1"),human=new Player("Nomophobia");
        Referee.botLabel(bot,"wtap-a");Referee.botLabel(human,"wtap-a");
        check(bot.custom.equals("wtap-a")&&bot.getName().equals("LabSlot1")&&human.custom.isEmpty(),"metadata only changes bots");
        Referee.botLabel(bot,"");check(bot.custom.isEmpty(),"cleanup");
        Referee.botLabel(bot,"hold-w");check(bot.custom.equals("hold-w"),"slot reuse");
        String longPolicy="very-long-research-candidate-with-many-parameters";
        String a=MatchLabels.profileName(longPolicy+"-a"),b=MatchLabels.profileName(longPolicy+"-b");
        check(a.length()<=16&&b.length()<=16&&a.matches("[A-Za-z0-9_]+")&&!a.equals(b)&&a.endsWith("_a")&&b.endsWith("_b"),"readable distinct bounded replay fallbacks");
        check(MatchLabels.label("LabSlot1",longPolicy,longPolicy,"a").equals(longPolicy+"-a"),"full policy stays visible in custom metadata");
        check(MatchLabels.profileName("hold-w").equals("hold_w"),"short vanilla replay fallback");
        System.out.println("PASS match labels: duel/mirror/replay/history, stable identities, human isolation, cleanup/reuse, long names");
    }
}
