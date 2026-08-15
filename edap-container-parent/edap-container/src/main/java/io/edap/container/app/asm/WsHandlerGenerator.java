package io.edap.container.app.asm;

import io.edap.container.AppContext;
import io.edap.container.mw.AnnoData;
import io.edap.container.ws.WSServiceMsgHandler;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Method;
import java.util.List;

import static io.edap.container.app.asm.HandlerAsmGenerator.handlerName;
import static io.edap.util.AsmUtil.toInternalName;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

public class WsHandlerGenerator {
    private static final String IFACE_NAME = toInternalName(WSServiceMsgHandler.class.getName());
    private static final String PARENT_NAME = toInternalName(AbstractHandler.class.getName());
    private static final String APP_CONTEXT_NAME = toInternalName(AppContext.class.getName());

    private List<AnnoData> annoDatas;
    private ClassWriter cw;
    private String      handlerName;
    private Class<?>    iface;
    private Method method;

    public WsHandlerGenerator(List<AnnoData> annoDatas, Class<?> protoIf, Method method) {
        this.annoDatas   = annoDatas;
        this.handlerName = toInternalName(handlerName(WSServiceMsgHandler.class, protoIf, method));
        this.iface       = protoIf;
        this.method      = method;
    }

    public byte[] generate() {
        String[] ifaceName = new String[]{IFACE_NAME};
        cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        String desc = "<String:Ljava/lang/Object;>L" + PARENT_NAME + ";" +
                "L" + IFACE_NAME + "<TString;>;";
        // handlerName 返回 binary name（点号）—— ASM ClassWriter.visit 第三个参数要 internal name（斜杠）
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, handlerName, desc, PARENT_NAME, ifaceName);

        visitInit();
        visitHandleMethod();

        return cw.toByteArray();
    }

    private void visitHandleMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "handle",
                "(Ljava/lang/Object;)Ljava/lang/Object;", "(TString;)TString;", null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
    }

    private void visitInit() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(L" + APP_CONTEXT_NAME + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "<init>",
                "(L" + APP_CONTEXT_NAME + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }
}
