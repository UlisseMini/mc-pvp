import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** Build-only: vanilla/Forge obfuscated names to Forge's stable SRG API. */
public final class Remap {
    public static void main(String[] args) throws Exception {
        final Map<String,String> classes=new HashMap<>(), fields=new HashMap<>(), methods=new HashMap<>();
        final Map<String,List<String>> parents=new HashMap<>();
        for(String line:Files.readAllLines(Paths.get(args[0]))) {
            String[] p=line.split(" ");
            if(p[0].equals("CL:")) classes.put(p[1],p[2]);
            if(p[0].equals("FD:")) fields.put(p[1],p[2].substring(p[2].lastIndexOf('/')+1));
            if(p[0].equals("MD:")) methods.put(p[1]+p[2],p[3].substring(p[3].lastIndexOf('/')+1));
        }
        for(int i=2;i<args.length;i++) try(JarFile jar=new JarFile(args[i])) {
            for(Enumeration<JarEntry> en=jar.entries();en.hasMoreElements();) {
                JarEntry e=en.nextElement(); if(!e.getName().endsWith(".class"))continue;
                ClassReader cr=new ClassReader(jar.getInputStream(e));
                List<String> ps=new ArrayList<>(Arrays.asList(cr.getInterfaces()));
                if(cr.getSuperName()!=null)ps.add(cr.getSuperName());parents.put(cr.getClassName(),ps);
            }
        }
        Remapper mapper=new Remapper(Opcodes.ASM9) {
            String lookup(Map<String,String> map,String owner,String member,Set<String> seen) {
                if(!seen.add(owner))return null;
                String found=map.get(owner+"/"+member);if(found!=null)return found;
                for(String p:parents.getOrDefault(owner,Collections.emptyList())) {
                    found=lookup(map,p,member,seen);if(found!=null)return found;
                }
                return null;
            }
            public String map(String name){return classes.getOrDefault(name,name);}
            public String mapFieldName(String owner,String name,String desc){
                String n=lookup(fields,owner,name,new HashSet<>());return n==null?name:n;
            }
            public String mapMethodName(String owner,String name,String desc){
                if(name.startsWith("<"))return name;
                String n=lookup(methods,owner,name+desc,new HashSet<>());return n==null?name:n;
            }
        };
        Set<String> written=new HashSet<>();
        try(JarOutputStream out=new JarOutputStream(new FileOutputStream(args[1]))) {
            for(int i=2;i<args.length;i++) try(JarFile jar=new JarFile(args[i])) {
                for(Enumeration<JarEntry> en=jar.entries();en.hasMoreElements();) {
                    JarEntry e=en.nextElement();if(!e.getName().endsWith(".class"))continue;
                    ClassReader cr=new ClassReader(jar.getInputStream(e));ClassWriter cw=new ClassWriter(0);
                    cr.accept(new ClassRemapper(cw,mapper),0);String name=mapper.map(cr.getClassName())+".class";
                    if(written.add(name)){out.putNextEntry(new JarEntry(name));out.write(cw.toByteArray());out.closeEntry();}
                }
            }
        }
    }
}
