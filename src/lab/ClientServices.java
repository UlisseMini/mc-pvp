package lab;

/** Passive services injected by the native host; never an observation source for policies. */
public interface ClientServices {
    void tick();
    void frame();
    void event(String encoded);
    boolean probe(Object mc,Object player,long elapsed);
    static ClientServices legacy() {
        if(!"client".equals(System.getProperty("lab.role"))||"forge".equals(System.getProperty("lab.backend")))return null;
        try{return (ClientServices)Class.forName("lab.LegacyClientServices").newInstance();}
        catch(Exception e){throw new IllegalStateException("Legacy client services unavailable",e);}
    }
}
