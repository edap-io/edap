package io.edap.container.app.asm;

import io.edap.container.AppContext;
import io.edap.container.mw.AnnoData;
import io.edap.http.HttpHandler;
import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.annotation.ProtoHttp;
import io.edap.util.ClazzUtil;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;
import org.objectweb.asm.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.container.app.asm.HandlerAsmGenerator.handlerName;
import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.ClazzUtil.getDescriptor;
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
    private String      reqLangType;
    private String      respType;
    private boolean     isPost = false;
    private ClassLoader loader;
    /** {@code @RequireAuth} 出现时记 bean 名(null = 公开路由,不消费 holder)。 */
    private String      resolverBeanName;

    private ReentrantLock lock = new ReentrantLock();
    private Set<String> parseEnumMethods = new HashSet<>();

    public HttpHandlerGenerator(List<AnnoData> annoDatas, Class<?> protoIf, Method method, ClassLoader loader,
                                String resolverBeanName) {
        this.annoDatas        = annoDatas;
        this.handlerName      = toInternalName(handlerName(HttpHandler.class, protoIf, method));
        this.iface            = protoIf;
        this.serviceIf        = toInternalName(protoIf.getName());
        this.method           = method;
        this.reqLangType      = method.getParameterTypes()[0].getTypeName();
        this.reqType          = toInternalName(method.getParameterTypes()[0].getName());
        this.respType         = toInternalName(method.getReturnType().getName());
        this.loader           = loader;
        this.resolverBeanName = resolverBeanName;
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
        // @RequireAuth 路径专用:POST 和 GET 各一份 inner try-catch + 共享 catch handler label
        // (不共享 try-start label 是因为 POST/GET 分支进入 lbTryStart 时 stack frame 不同)
        Label lbFinallyStart = new Label();
        Label lbCatchAuth    = new Label();
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

        // @RequireAuth 路径:在调 service method 之前 resolve + set holder
        // (resolve 抛 IOException 由老 lbHandler 接住,语义上是 500 — 但同 OAuth 行为)
        if (resolverBeanName != null) {
            mv.visitFieldInsn(GETSTATIC, handlerName, "userResolver",
                    "Lio/edap/container/context/UserResolver;");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/container/context/UserResolver",
                    "resolve", "(Lio/edap/http/HttpRequest;)Lio/edap/container/context/RequestContext;", true);
            mv.visitMethodInsn(INVOKESTATIC, "io/edap/container/context/RequestContextHolder",
                    "set", "(Lio/edap/container/context/RequestContext;)V", false);
        }

        if (isPost) {
            if (resolverBeanName != null) {
                // POST + RequireAuth:parse body → invoke → store slot 6 → write → clear → GOTO lbFinish
                // inner try-catch 包整个流程,异常 → clear + athrow 回外层 lbHandler
                Label lbTryStartPost = new Label();
                mv.visitTryCatchBlock(lbTryStartPost, lbFinallyStart, lbCatchAuth, "java/lang/Throwable");
                mv.visitLabel(lbTryStartPost);
                int varResp = 6;
                // INVOKEINTERFACE 要求 receiver 先入栈,再入参数——bean 必须在 request 之前 push
                mv.visitFieldInsn(GETSTATIC, handlerName, "bean", "L" + serviceIf + ";");
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/http/HttpRequest", "getBody",
                        "()Lio/edap/util/ByteData;", true);
                mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/util/ByteData", "getBytes", "()[B", false);
                mv.visitLdcInsn(Type.getType("L" + reqType + ";"));
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "parseObject",
                        "([BLjava/lang/Class;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, reqType);
                mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(),
                        "(L" + reqType + ";)L" + respType + ";", true);
                mv.visitVarInsn(ASTORE, varResp);
                emitWriteResponse(mv, varResp);
                mv.visitLabel(lbFinallyStart);
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/container/context/RequestContextHolder",
                        "clear", "()V", false);
                mv.visitJumpInsn(GOTO, lbFinish);
                mv.visitLabel(lbCatchAuth);
                // slot 7 是新分配的局部变量,try 块内不写,在 catch handler 里用来暂存 throwable。
                // 用老 slot 3 (lbHandler 用) 会出现 VerifyError:try 块未初始化 slot → 不能在 handler 写。
                int varExcAuth = 7;
                mv.visitVarInsn(ASTORE, varExcAuth);
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/container/context/RequestContextHolder",
                        "clear", "()V", false);
                mv.visitVarInsn(ALOAD, varExcAuth);
                mv.visitInsn(ATHROW);
            } else {
                mv.visitVarInsn(ALOAD, 2);
                mv.visitFieldInsn(GETSTATIC, handlerName, "bean", "L" + serviceIf + ";");
                mv.visitVarInsn(ALOAD, 1);

                mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/http/HttpRequest", "getBody",
                        "()Lio/edap/util/ByteData;", true);
                mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/util/ByteData", "getBytes", "()[B", false);
                mv.visitLdcInsn(Type.getType("L" + reqType + ";"));
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "parseObject",
                        "([BLjava/lang/Class;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, reqType);
                mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(), "(L" + reqType + ";)L" + respType + ";", true);
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "toJsonString",
                        "(Ljava/lang/Object;)Ljava/lang/String;", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                        "(Ljava/lang/String;)Lio/edap/http/HttpResponse;", false);
                mv.visitInsn(POP);
            }
        } else {
            // GET + RequireAuth:inner try-catch 包整个 varReq 构造 + invoke + store + write
            // catch handler 走 clear + athrow(抛回外层 lbHandler 走老 BIZ_EXCEPTION 路径)
            Label lbTryStartGet    = resolverBeanName != null ? new Label() : null;
            Label lbFinallyStartGet = resolverBeanName != null ? new Label() : null;
            Label lbCatchAuthGet    = resolverBeanName != null ? new Label() : null;
            if (resolverBeanName != null) {
                mv.visitTryCatchBlock(lbTryStartGet, lbFinallyStartGet, lbCatchAuthGet, "java/lang/Throwable");
                mv.visitLabel(lbTryStartGet);
            }
            int varReq = 5;
            mv.visitTypeInsn(NEW, reqType);
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, reqType, "<init>", "()V", false);
            mv.visitVarInsn(ASTORE, varReq);

            try {
                Class reqClass = Class.forName(reqLangType, false, loader);
                List<Field> fields = ClazzUtil.getClassFields(reqClass);

                for (Field field : fields) {
                    Annotation[] anns = field.getAnnotations();
                    String paramName = field.getName();
                    for (Annotation ann : anns) {
                        if (ann instanceof ProtoField) {
                            ProtoField pf = (ProtoField) ann;
                            if (!StringUtil.isEmpty(pf.name())) {
                                paramName = pf.name();
                            }
                        }
                    }
                    if (field.getType().isEnum()) {
                        Class enumClass = field.getType();
                        lock.lock();
                        try {
                            if (!parseEnumMethods.contains(enumClass.getName())) {
                                visitParseEnumMethod(enumClass, paramName);
                                parseEnumMethods.add(enumClass.getName());
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            lock.unlock();
                        }
                        mv.visitVarInsn(ALOAD, varReq);
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitVarInsn(ALOAD, 1);
                        mv.visitMethodInsn(INVOKEVIRTUAL, handlerName, "parse" + enumClass.getSimpleName(),
                                "(Lio/edap/http/HttpRequest;)L" + toInternalName(enumClass.getName()) + ";",
                                false);
                        String fieldName = field.getName();
                        mv.visitMethodInsn(INVOKEVIRTUAL, reqType, "set" +
                                        fieldName.substring(0, 1).toUpperCase(Locale.ENGLISH) +
                                        fieldName.substring(1),
                                "(L" + toInternalName(enumClass.getName()) + ";)V", false);
                    } else {
                        // 为请求实例赋值
                        mv.visitVarInsn(ALOAD, varReq);
                        boolean needConvert = false;
                        if (field.getType() != String.class) {
                            // 调用本类的类型转换
                            mv.visitVarInsn(ALOAD, 0);
                            needConvert = true;
                        }
                        mv.visitVarInsn(ALOAD, 1);
                        mv.visitLdcInsn(paramName);
                        String typeDesc = getDescriptor(field.getType());
                        mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/http/HttpRequest", "getParameter",
                                "(Ljava/lang/String;)Ljava/lang/String;", true);
                        if (needConvert) {
                            String convertMethodName = getConvertMethodName(field.getType());
                            mv.visitMethodInsn(INVOKEVIRTUAL, handlerName, convertMethodName,
                                    "(Ljava/lang/String;)" + typeDesc, false);
                        }
                        String fieldName = field.getName();
                        mv.visitMethodInsn(INVOKEVIRTUAL, reqType, "set" +
                                        fieldName.substring(0, 1).toUpperCase(Locale.ENGLISH) +
                                        fieldName.substring(1),
                                "(" + typeDesc + ")V", false);
                    }
                }
            } catch (RuntimeException e) {
                throw new RuntimeException(e);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }

            if (resolverBeanName == null) {
                // 公开路径:resp 先入栈,invoke 结果直接接 write
                mv.visitVarInsn(ALOAD, 2);
            }
            mv.visitFieldInsn(GETSTATIC, handlerName, "bean",
                    "L" + serviceIf + ";");
            mv.visitVarInsn(ALOAD, varReq);
            mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(),
                    "(L" + reqType + ";)L" + respType + ";", true);
            if (resolverBeanName != null) {
                // GET + RequireAuth:store slot 6 → write → clear → GOTO lbFinish
                int varResp = 6;
                mv.visitVarInsn(ASTORE, varResp);
                emitWriteResponse(mv, varResp);
                mv.visitLabel(lbFinallyStartGet);
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/container/context/RequestContextHolder",
                        "clear", "()V", false);
                mv.visitJumpInsn(GOTO, lbFinish);
                mv.visitLabel(lbCatchAuthGet);
                int varExcAuth = 7;
                mv.visitVarInsn(ASTORE, varExcAuth);
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/container/context/RequestContextHolder",
                        "clear", "()V", false);
                mv.visitVarInsn(ALOAD, varExcAuth);
                mv.visitInsn(ATHROW);
            } else {
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "toJsonString",
                        "(Ljava/lang/Object;)Ljava/lang/String;", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                        "(Ljava/lang/String;)Lio/edap/http/HttpResponse;", false);
                mv.visitInsn(POP);
            }
        }

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

    /**
     * Emit 写响应字节码片段 —— RequireAuth 路径下,service method 返回值已存 slot varResp,
     * 这里把它 toJsonString + resp.write。
     *
     * <p>调用前提:栈顶为空(varResp 已被 ALOAD 过本方法负责)。</p>
     */
    private void emitWriteResponse(MethodVisitor mv, int varResp) {
        mv.visitVarInsn(ALOAD, 2);                             // HttpResponse resp
        mv.visitVarInsn(ALOAD, varResp);                       // service return value
        mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "toJsonString",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                "(Ljava/lang/String;)Lio/edap/http/HttpResponse;", false);
        mv.visitInsn(POP);
    }

    private void visitParseEnumMethod(Class enumClass, String paramName) {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, "parse" + enumClass.getSimpleName(),
                "(Lio/edap/http/HttpRequest;)" + getDescriptor(enumClass), null, null);
        mv.visitCode();
        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();
        mv.visitTryCatchBlock(label0, label1, label2, "java/lang/IllegalArgumentException");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn(paramName);
        mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/http/HttpRequest", "getParameter",
                "(Ljava/lang/String;)Ljava/lang/String;", true);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, "io/edap/util/StringUtil", "isEmpty",
                "(Ljava/lang/String;)Z", false);
        Label label3 = new Label();
        mv.visitJumpInsn(IFNE, label3);
        mv.visitLabel(label0);
        mv.visitVarInsn(ALOAD, 2);
        String enumName = toInternalName(enumClass.getName());
        mv.visitMethodInsn(INVOKESTATIC, enumName, "valueOf",
                "(Ljava/lang/String;)L" + enumName + ";", false);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitLabel(label1);
        Label label4 = new Label();
        mv.visitJumpInsn(GOTO, label4);
        mv.visitLabel(label2);

        mv.visitVarInsn(ASTORE, 4);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, handlerName, "log", "Lio/edap/log/Logger;");
        mv.visitLdcInsn(enumClass.getSimpleName() + " valueOf error");
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/log/Logger", "warn",
                "(Ljava/lang/String;Ljava/lang/Throwable;)V", true);
        mv.visitInsn(ACONST_NULL);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitJumpInsn(GOTO, label4);
        mv.visitLabel(label3);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(ACONST_NULL);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitLabel(label4);

        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 5);
        mv.visitEnd();
    }

    private String getConvertMethodName(Class type) {
        String name = "toObject";
        if (type == int.class) {
            name = "toInt";
        } else if (type == Integer.class) {
            name = "toInteger";
        } else if (type == long.class) {
            name = "toLong";
        } else if (type == Long.class) {
            name = "toLongObj";
        } else if (type == boolean.class) {
            name = "toBoolean";
        } else if (type == Boolean.class) {
            name = "toBooleanObj";
        } else if (type == float.class) {
            name = "toFloat";
        } else if (type == Float.class) {
            name = "toFloatObj";
        } else if (type == double.class) {
            name = "toDouble";
        } else if (type == Double.class) {
            name = "toDoubleObj";
        }

        return name;
    }

    private void visitInit() {
        FieldVisitor fv = cw.visitField(ACC_PRIVATE | ACC_STATIC, "bean",
                "L" + serviceIf + ";", null, null);
        fv.visitEnd();

        // @RequireAuth 路径:加 userResolver 静态字段。null = 公开路由,字段留 null 不引用
        if (resolverBeanName != null) {
            FieldVisitor fvRes = cw.visitField(ACC_PRIVATE | ACC_STATIC, "userResolver",
                    "Lio/edap/container/context/UserResolver;", null, null);
            fvRes.visitEnd();
        }

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

        // @RequireAuth 路径:在 bean null-check 之后无条件查 UserResolver bean,存静态字段。
        // 不能嵌套在 lbNotNull 里 —— 否则 bean == null 时 userResolver 永远不被赋值,
        // 下一次同 class 实例化（不同 AppContext / bean 重新加载）会读到陈旧静态值。
        // 无 bean 时 resolve 在 handle() 不会被调到（早 return 写 NOT_IMPL）,但
        // resolver 缺失应在 <init> 期 fail-fast (NoSuchBeanException → RouteBindException),
        // 而不是沉默通过 → 上线后某个 bean 出现时才发现鉴权缺失。
        if (resolverBeanName != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, handlerName, "getAppContext",
                    "()L" + APP_CONTEXT_NAME + ";", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, APP_CONTEXT_NAME, "beans",
                    "()Lio/edap/container/BeanContainer;", false);
            mv.visitLdcInsn(resolverBeanName);
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/container/BeanContainer", "getBean",
                    "(Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, "io/edap/container/context/UserResolver");
            mv.visitFieldInsn(PUTSTATIC, handlerName, "userResolver",
                    "Lio/edap/container/context/UserResolver;");
        }

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
