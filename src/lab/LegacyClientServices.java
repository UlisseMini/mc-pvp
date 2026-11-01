package lab;

/** Executable legacy capture/backend retained for comparison and rollback. */
public final class LegacyClientServices implements ClientServices {
    public void tick(){FastPov.tick();BotArchive.tick();if(Client.player!=null&&R.get(Client.mc,"theWorld")!=null)PovLink.clientTick();}
    public void frame(){FastPov.frame();BotArchive.frame();}
    public void event(String encoded){BotArchive.archive.event(encoded);}
    public boolean probe(Object mc,Object player,long elapsed){return PracticeProbe.input(mc,player,elapsed);}
}
