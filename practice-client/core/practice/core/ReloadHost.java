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
    private String fingerprint="";
    private volatile String status="Not loaded",moduleSha256="unreported";
    private PracticeExtension active;
    private URLClassLoader loader;
    private Path copy;
    private boolean faulted;
    private volatile boolean remoteCaptureEvents=true;
    private PracticeServices services;

    public ReloadHost(Path directory){this.directory=directory;}
    public String status(){return status;}
    public String moduleSha256(){return moduleSha256;}
    public boolean wantsRemoteCaptureEvents(){return remoteCaptureEvents;}
    public synchronized void services(PracticeServices services){this.services=services;}
    private byte[] readBounded(Path p,int max) throws IOException {
        if(Files.size(p)>max)throw new IOException(p.getFileName()+" is too large");
        byte[] data=Files.readAllBytes(p);if(data.length>max)throw new IOException("File grew while reading");return data;
    }
    public synchronized boolean reload(boolean force) {
        URLClassLoader candidateLoader=null;PracticeExtension candidate=null;Path candidateCopy=null;
        try {
            byte[] jar=readBounded(directory.resolve("practice-module.jar"),4*1024*1024);
            byte[] config=readBounded(directory.resolve("settings.properties"),64*1024);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(jar);digest.update(config);
            String next=Base64.getEncoder().encodeToString(digest.digest());
            if(!force && next.equals(fingerprint))return false;
            Properties props=new Properties();props.load(new ByteArrayInputStream(config));
            Path cache=directory.resolve("loaded");Files.createDirectories(cache);
            candidateCopy=Files.createTempFile(cache,"module-",".jar");Files.write(candidateCopy,jar);
            candidateLoader=new URLClassLoader(new URL[]{candidateCopy.toUri().toURL()},PracticeExtension.class.getClassLoader());
            candidate=(PracticeExtension)Class.forName("practice.module.PracticeModule",true,candidateLoader).getDeclaredConstructor().newInstance();
            if(services!=null)candidate.services(services);
            candidate.configure(props,directory);
            boolean wantsRemote=candidate.wantsRemoteCaptureEvents();
            dispose(active,loader,copy);
            active=candidate;loader=candidateLoader;copy=candidateCopy;faulted=false;
            fingerprint=next;
            remoteCaptureEvents=wantsRemote;
            StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(jar))hex.append(String.format("%02x",b&255));moduleSha256=hex.toString();
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
    public void event(PracticeEvent event){
        if(active==null||faulted)return;
        try{active.event(event);}catch(Exception|LinkageError e){faulted=true;status="Module event error: "+e;}
    }
    public Frame render(float partial,int width,int height){
        if(active==null||faulted)return null;
        try{return active.render(partial,width,height);}catch(Exception|LinkageError e){faulted=true;status="Module render error: "+e;return null;}
    }
    public boolean command(String[] args){
        if(active==null||faulted)return false;
        try{return active.command(args);}catch(Exception|LinkageError e){faulted=true;status="Module command error: "+e;return false;}
    }
    public WorldFrame world(float partial){
        if(active==null||faulted)return null;
        try{return active.world(partial);}catch(Exception|LinkageError e){faulted=true;status="Module world error: "+e;return null;}
    }
    private static void dispose(PracticeExtension mod,URLClassLoader loader,Path copy) {
        try{if(mod!=null)mod.close();}catch(Exception ignored){}
        try{if(loader!=null)loader.close();}catch(IOException ignored){}
        try{if(copy!=null)Files.deleteIfExists(copy);}catch(IOException ignored){}
    }
    public synchronized void close(){dispose(active,loader,copy);active=null;loader=null;copy=null;}
}
