package io.edap.container.app.asm;

import io.edap.container.AppContext;
import io.edap.container.mw.AnnoData;
import io.edap.http.HttpHandler;
import io.edap.protobuf.annotation.ProtoHttp;
import io.edap.util.CollectionUtils;
import org.objectweb.asm.*;

import java.lang.reflect.Method;
import java.util.List;

import static io.edap.container.app.asm.HandlerAsmGenerator.handlerName;
import static io.edap.util.AsmUtil.toInternalName;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

public class HttpHandlerGenerator {

    private static final String IFACE_NAME = toInternalName(HttpHandler.class.getName());
    private static final String PARENT_NAME = toInternalName(AbstractHandler.class.getName());
    private static final String APP_CONTEXT_NAME = toInternalName(AppContext.class.getName());

    private List<AnnoData> annoDatas;
    private ClassWriter cw;
    private String      handlerName;
    private Class<?>    iface;
    private Method      method;
    private String      serviceIf;
    private String      reqType;
    private String      respType;
    private boolean     isPost = false;

    public HttpHandlerGenerator(List<AnnoData> annoDatas, Class<?> protoIf, Method method) {
        this.annoDatas   = annoDatas;
        this.handlerName = toInternalName(handlerName(HttpHandler.class, protoIf, method));
        this.iface       = protoIf;
        this.serviceIf   = toInternalName(protoIf.getName());
        this.method      = method;
        this.reqType     = toInternalName(method.getParameterTypes()[0].getName());
        this.respType    = toInternalName(method.getReturnType().getName());
        if (!CollectionUtils.isEmpty(annoDatas)) {
            for (AnnoData annoData : annoDatas) {
                if (ProtoHttp.class.getName().equals(annoData.getType())) {
                    Object httpMethod = annoData.getValues().get("method");
                    if (httpMethod != null && "POST".equalsIgnoreCase((String) httpMethod)) {
                        isPost = true;
                    }
                }
            }
        }
    }

    public byte[] generate() {
        String[] ifaceName = new String[]{IFACE_NAME};
        cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        // handlerName 返回 binary name（点号）—— ASM ClassWriter.visit 第三个参数要 internal name（斜杠）
        // 否则 class 文件 this_class 字段是点号形式，JVM 加载时整串当作单个 class name → ClassFormatError
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, handlerName, null, PARENT_NAME, ifaceName);

        visitInit();
        visitCinit();
        visitHandleMethod();

        return cw.toByteArray();
    }

    private void visitHandleMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "handle",
                "(Lio/edap/http/HttpRequest;Lio/edap/http/HttpResponse;)V",
                null, new String[] { "java/io/IOException" });
        mv.visitCode();

        Label lbStart   = new Label();
        Label lbEnd     = new Label();
        Label lbHandler = new Label();
        mv.visitTryCatchBlock(lbStart, lbEnd, lbHandler, "java/lang/Throwable");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitFieldInsn(GETSTATIC, "io/edap/http/header/ContentTypeHeader", "JSON",
                "Lio/edap/http/header/ContentTypeHeader;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "contentType",
                "(Lio/edap/http/header/ContentTypeHeader;)Lio/edap/http/HttpResponse;", false);
        mv.visitInsn(POP);

        int varMap = 4;
        mv.visitFieldInsn(GETSTATIC, handlerName, "bean", "L" + serviceIf + ";");
        mv.visitJumpInsn(IFNONNULL, lbStart);
        mv.visitTypeInsn(NEW, "java/util/HashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varMap);
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitLdcInsn("code");
        mv.visitIntInsn(BIPUSH, 100);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                "(I)Ljava/lang/Integer;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitLdcInsn("msg");
        mv.visitFieldInsn(GETSTATIC, handlerName, "NOT_IMPL", "Ljava/lang/String;");
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "toJsonString",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                "(Ljava/lang/String;)Lio/edap/http/HttpResponse;", false);
        mv.visitInsn(POP);
        // 跳到结束
        Label lbFinish = new Label();
        mv.visitJumpInsn(GOTO, lbFinish);

        // 业务的实现Bean不为空
        mv.visitLabel(lbStart);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitFieldInsn(GETSTATIC, handlerName, "bean", "L" + serviceIf + ";");
        mv.visitVarInsn(ALOAD, 1);

        if (isPost) {
            mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/http/HttpRequest", "getBody",
                    "()Lio/edap/util/ByteData;", true);
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/util/ByteData", "getBytes", "()[B", false);
            mv.visitLdcInsn(Type.getType("L" + reqType + ";"));
            mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "parseObject",
                    "([BLjava/lang/Class;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, reqType);
        } else {

        }
        mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(), "(L" + reqType + ";)L" + respType + ";", true);
        mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "toJsonString",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                "(Ljava/lang/String;)Lio/edap/http/HttpResponse;", false);
        mv.visitInsn(POP);

        mv.visitLabel(lbEnd);
        mv.visitJumpInsn(GOTO, lbFinish);

        // 业务处理异常
        mv.visitLabel(lbHandler);
        int varExc = 3;
        mv.visitVarInsn(ASTORE, varExc);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, handlerName, "log", "Lio/edap/log/Logger;");
        mv.visitLdcInsn("");
        mv.visitVarInsn(ALOAD, varExc);
        mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/log/Logger", "warn",
                "(Ljava/lang/String;Ljava/lang/Throwable;)V", true);
        mv.visitTypeInsn(NEW, "java/util/HashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        varMap = 4;
        mv.visitVarInsn(ASTORE, varMap);
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitLdcInsn("code");
        mv.visitIntInsn(BIPUSH, 101);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitLdcInsn("msg");
        mv.visitFieldInsn(GETSTATIC, handlerName, "BIZ_EXC", "Ljava/lang/String;");
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "" +
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "toJsonString",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                "(Ljava/lang/String;)Lio/edap/http/HttpResponse;", false);
        mv.visitInsn(POP);

        // handler处理结束
        mv.visitLabel(lbFinish);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 3);
        mv.visitEnd();
    }

    private void visitInit() {
        FieldVisitor fv = cw.visitField(ACC_PRIVATE | ACC_STATIC, "bean",
                "L" + serviceIf + ";", null, null);
        fv.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(L" + APP_CONTEXT_NAME + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "<init>",
                "(L" + APP_CONTEXT_NAME + ";)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(Type.getType("L" + serviceIf + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, handlerName, "getBean",
                "(Ljava/lang/Class;)Ljava/lang/Object;", false);
        int varObj = 2;
        mv.visitVarInsn(ASTORE, varObj);
        mv.visitVarInsn(ALOAD, varObj);
        Label lbNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbNotNull);
        mv.visitInsn(ACONST_NULL);
        mv.visitFieldInsn(PUTSTATIC, handlerName, "bean", "L" + serviceIf + ";");
        Label lbFinish = new Label();
        mv.visitJumpInsn(GOTO, lbFinish);

        // bean 不为空
        mv.visitLabel(lbNotNull);
        mv.visitVarInsn(ALOAD, varObj);
        mv.visitTypeInsn(CHECKCAST, serviceIf);
        mv.visitFieldInsn(PUTSTATIC, handlerName, "bean", "L" + serviceIf + ";");

        mv.visitLabel(lbFinish);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private void visitCinit() {
        FieldVisitor fv = cw.visitField(ACC_PRIVATE | ACC_STATIC, "serviceName",
                "Ljava/lang/String;", null, null);
        fv.visitEnd();
        FieldVisitor fvNotImpl = cw.visitField(ACC_PRIVATE | ACC_STATIC, "NOT_IMPL",
                "Ljava/lang/String;", null, null);
        fvNotImpl.visitEnd();
        FieldVisitor fvBizExc = cw.visitField(ACC_PRIVATE | ACC_STATIC, "BIZ_EXC",
                "Ljava/lang/String;", null, null);
        fvBizExc.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(Type.getType("L" + serviceIf + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        mv.visitFieldInsn(PUTSTATIC, handlerName, "serviceName", "Ljava/lang/String;");

        mv.visitFieldInsn(GETSTATIC, handlerName, "serviceName", "Ljava/lang/String;");
        mv.visitFieldInsn(GETSTATIC, handlerName, "NOT_IMPL_MSG", "Ljava/lang/String;");
        mv.visitInvokeDynamicInsn("makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                                "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false),
                new Object[]{"\u0001\u0001"});
        mv.visitFieldInsn(PUTSTATIC, handlerName, "NOT_IMPL", "Ljava/lang/String;");

        mv.visitFieldInsn(GETSTATIC, handlerName, "serviceName", "Ljava/lang/String;");
        mv.visitFieldInsn(GETSTATIC, handlerName, "BIZ_EXCEPTION_MSG", "Ljava/lang/String;");
        mv.visitInvokeDynamicInsn("makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                                "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false),
                new Object[]{"\u0001\u0001"});
        mv.visitFieldInsn(PUTSTATIC, handlerName, "BIZ_EXC", "Ljava/lang/String;");

        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
    }
}
