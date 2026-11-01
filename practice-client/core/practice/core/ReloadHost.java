package practice.core;

import practice.api.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Local module replacement. No server, downloads or remotely supplied code. */
public final class ReloadHost implements AutoCloseable {
    private final Path directory;
    private String fingerprint="",status="Not loaded";
    private PracticeExtension active;
    private URLClassLoader loader;
    private Path copy;
    private boolean faulted;

    public ReloadHost(Path directory){this.directory=directory;}
    public String status(){return status;}
    private byte[] readBounded(Path p,int max) throws IOException {
        if(Files.size(p)>max)throw new IOException(p.getFileName()+" is too large");
        byte[] data=Files.readAllBytes(p);if(data.length>max)throw new IOException("File grew while reading");return data;
    }
    public boolean reload(boolean force) {
        URLClassLoader candidateLoader=null;PracticeExtension candidate=null;Path candidateCopy=null;
        try {
            byte[] jar=readBounded(directory.resolve("practice-module.jar"),4*1024*1024);
            byte[] config=readBounded(directory.resolve("settings.properties"),64*1024);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(jar);digest.update(config);
            String next=Base64.getEncoder().encodeToString(digest.digest());
            if(!force && next.equals(fingerprint))return false;
            fingerprint=next;Properties props=new Properties();props.load(new ByteArrayInputStream(config));
            Path cache=directory.resolve("loaded");Files.createDirectories(cache);
            candidateCopy=Files.createTempFile(cache,"module-",".jar");Files.write(candidateCopy,jar);
            candidateLoader=new URLClassLoader(new URL[]{candidateCopy.toUri().toURL()},PracticeExtension.class.getClassLoader());
            candidate=(PracticeExtension)Class.forName("practice.module.PracticeModule",true,candidateLoader).getDeclaredConstructor().newInstance();
            candidate.configure(props,directory);
            dispose(active,loader,copy);
            active=candidate;loader=candidateLoader;copy=candidateCopy;faulted=false;
            status="Loaded local module "+next.substring(0,8);return true;
        } catch(Exception|LinkageError error) {
            dispose(candidate,candidateLoader,candidateCopy);
            status="Reload failed; kept previous module: "+error.getClass().getSimpleName()+": "+error.getMessage();return false;
        }
    }
    public Frame tick(Snapshot s) {
        if(active==null || faulted)return Frame.empty();
        try{return Objects.requireNonNull(active.tick(s));}
        catch(Exception|LinkageError e){faulted=true;status="Module disabled after error: "+e.getClass().getSimpleName()+": "+e.getMessage();return Frame.empty();}
    }
    public void chat(String text,int type){
        if(active==null||faulted)return;
        try{active.chat(text,type);}catch(Exception|LinkageError e){faulted=true;status="Module chat error: "+e.getClass().getSimpleName();}
    }
    private static void dispose(PracticeExtension mod,URLClassLoader loader,Path copy) {
        try{if(mod!=null)mod.close();}catch(Exception ignored){}
        try{if(loader!=null)loader.close();}catch(IOException ignored){}
        try{if(copy!=null)Files.deleteIfExists(copy);}catch(IOException ignored){}
    }
    public void close(){dispose(active,loader,copy);active=null;loader=null;copy=null;}
}
