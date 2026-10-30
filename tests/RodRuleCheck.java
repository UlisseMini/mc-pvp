package lab;

/** Check the rod exception's duel/lifecycle boundary against the pinned hook class. */
public final class RodRuleCheck {
    public static final class Named {
        final String name;
        Named(String name){this.name=name;}
        public String getName(){return name;}
    }
    public static final class Source {
        final Object direct,owner;
        Source(Object direct,Object owner){this.direct=direct;this.owner=owner;}
        public Object getSourceOfDamage(){return direct;}
        public Object getEntity(){return owner;}
    }
    static void expect(boolean expected,Object target,Object source,String context){
        if(Referee.allowRodContact(target,source)!=expected)throw new AssertionError(context);
    }
    public static void main(String[] args){
        R.call(R.cls("net.minecraft.init.Bootstrap"),"register");
        Object hook=R.construct("net.minecraft.entity.projectile.EntityFishHook",(Object)null);
        Named a=new Named("TestA"),b=new Named("TestB"),other=new Named("Bystander");
        Source fromA=new Source(hook,a),fromB=new Source(hook,b);
        Referee.server=new Object();
        Referee.Match m=new Referee.Match();m.id="rod-scope-check";m.a=a.name;m.b=b.name;
        m.mode="rod_diamond";m.start=System.currentTimeMillis()-1000;Referee.matches.put(m.id,m);
        expect(true,b,fromA,"active opponent rod");expect(true,a,fromB,"reverse direction");
        expect(false,a,fromA,"self hook");expect(false,other,fromA,"outside victim");
        expect(false,b,new Source(hook,other),"outside attacker");
        expect(false,b,new Source(hook,null),"ownerless hook");
        expect(false,b,new Source(a,a),"non-hook damage source");
        for(String mode:java.util.List.of("boxing","diamond","diamond_box")){
            m.mode=mode;expect(false,b,fromA,"mode "+mode);
        }
        m.mode="rod_diamond";m.start=System.currentTimeMillis()+60_000;
        expect(false,b,fromA,"countdown");m.start=0;
        m.winner=a.name;expect(false,b,fromA,"decided duel");m.winner="";
        m.finish=1;expect(false,b,fromA,"finished duel");m.finish=0;
        System.setProperty("lab.role","client");expect(false,b,fromA,"client prediction");
        System.setProperty("lab.role","server");Referee.matches.clear();
        expect(false,b,fromA,"lobby");
        if(Referee.errors!=0)throw new AssertionError("Unexpected guard error");
        System.out.println("PASS rod exception: opponents only, rod mode only, active round only, server only");
    }
}
