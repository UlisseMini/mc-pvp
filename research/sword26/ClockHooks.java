package sword;

import java.lang.classfile.*;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.*;

final class ClockHooks {
    static final ClassDesc CLOCK=ClassDesc.of("sword.Lockstep"), CLIENT=ClassDesc.of("sword.LockstepClient");
    static final MethodTypeDesc MC=MethodTypeDesc.ofDescriptor("(Lnet/minecraft/client/Minecraft;)V");
    static byte[] transform(String name,byte[] bytes){
        var api=ClassFile.of();
        if(name.equals("net/minecraft/network/Connection")){
            return api.transformClass(api.parse(bytes),ClassTransform.transformingMethodBodies(
                m->m.methodName().equalsString("channelRead0")&&m.methodType().equalsString("(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V"),
                new CodeTransform(){
                    public void atStart(CodeBuilder b){
                        var normal=b.newLabel();
                        b.aload(0).aload(1).aload(2).invokestatic(CLOCK,"capture",MethodTypeDesc.ofDescriptor(
                            "(Lnet/minecraft/network/Connection;Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)Z"))
                            .ifeq(normal).return_().labelBinding(normal);
                    }
                    public void accept(CodeBuilder b,CodeElement e){b.with(e);}
                }));
        }
        if(name.equals("net/minecraft/client/Minecraft")){
            byte[] timed=api.transformClass(api.parse(bytes),ClassTransform.transformingMethodBodies(m->m.methodName().equalsString("runTick"),
                new CodeTransform(){
                    public void atStart(CodeBuilder b){var normal=b.newLabel();b.aload(0).invokestatic(CLIENT,"fastFrame",
                        MethodTypeDesc.ofDescriptor("(Lnet/minecraft/client/Minecraft;)Z")).ifeq(normal).return_().labelBinding(normal);}
                    public void accept(CodeBuilder b,CodeElement e){
                        if(Boolean.getBoolean("sword.exportFrames") && e instanceof InvokeInstruction call){
                            var film=ClassDesc.of("sword.FilmFrames");
                            if(call.owner().name().equalsString("net/minecraft/client/DeltaTracker$Timer")
                                    &&call.name().equalsString("advanceGameTime")){
                                b.invokestatic(film,"advance",MethodTypeDesc.ofDescriptor("(Lnet/minecraft/client/DeltaTracker$Timer;J)I"));
                                return;
                            }
                        }
                        b.with(e);
                    }
                }));
            if(!Boolean.getBoolean("sword.exportFrames"))return timed;
            return api.transformClass(api.parse(timed),ClassTransform.transformingMethodBodies(m->m.methodName().equalsString("renderFrame"),
                (b,e)->{
                    if(e instanceof InvokeInstruction call && call.owner().name().equalsString("net/minecraft/client/DeltaTracker$Timer")
                            && call.name().equalsString("advanceRealTime")){
                        b.invokestatic(ClassDesc.of("sword.FilmFrames"),"advanceUi",MethodTypeDesc.ofDescriptor("(Lnet/minecraft/client/DeltaTracker$Timer;J)V"));
                        return;
                    }
                    b.with(e);
                    if(e instanceof InvokeInstruction call && call.owner().name().equalsString("net/minecraft/client/renderer/GameRenderer")
                            && call.name().equalsString("render"))
                        b.aload(0).invokestatic(ClassDesc.of("sword.FilmFrames"),"capture",MC);
                }));
        }
        var before=api.transformClass(api.parse(bytes),ClassTransform.transformingMethodBodies(m->m.methodName().equalsString("processPacketsAndTick"),
            new CodeTransform(){
                public void atStart(CodeBuilder b){b.aload(0).invokestatic(CLOCK,"beforeServer",
                    MethodTypeDesc.ofDescriptor("(Lnet/minecraft/server/MinecraftServer;)V"));}
                public void accept(CodeBuilder b,CodeElement e){
                    if(e instanceof ReturnInstruction)b.invokestatic(CLOCK,"afterServer",MethodTypeDesc.ofDescriptor("()V"));
                    b.with(e);
                }
            }));
        return api.transformClass(api.parse(before),ClassTransform.transformingMethodBodies(m->m.methodName().equalsString("waitUntilNextTick"),
            new CodeTransform(){
                public void atStart(CodeBuilder b){
                    var normal=b.newLabel();
                    b.invokestatic(CLOCK,"unpaced",MethodTypeDesc.ofDescriptor("()Z")).ifeq(normal)
                        .aload(0).invokevirtual(ClassDesc.of("net.minecraft.server.MinecraftServer"),"runAllTasks",MethodTypeDesc.ofDescriptor("()V"))
                        .return_().labelBinding(normal);
                }
                public void accept(CodeBuilder b,CodeElement e){b.with(e);}
            }));
    }
}
