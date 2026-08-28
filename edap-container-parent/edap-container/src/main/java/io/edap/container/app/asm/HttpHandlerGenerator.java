package io.edap.container.app.asm;

import io.edap.container.AppContext;
import io.edap.container.mw.AnnoData;
import io.edap.http.HttpHandler;
import io.edap.mw.context.RequestContext;
import io.edap.mw.context.RequestContextHolder;
import io.edap.mw.context.UserResolver;
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
    private String      resolverIf;

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
        this.resolverIf       = toInternalName(UserResolver.class.getName());
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

    private void visitNoLoginLogLambda() {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$handle$1",
                "(L" + resolverIf + "$ResolverResult;Lio/edap/log/LogArgs;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, resolverIf + "$ResolverResult", "getErrorMsg",
                "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/log/LogArgs", "arg",
                "(Ljava/lang/String;)Lio/edap/log/LogArgs;", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private void visitBizLogLambda() {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$handle$0", "(Lio/edap/log/LogArgs;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, handlerName, "bean", "L" + serviceIf + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass",
                "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName",
                "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/log/LogArgs", "arg",
                "(Ljava/lang/String;)Lio/edap/log/LogArgs;", true);
        mv.visitLdcInsn(method.getName());
        mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/log/LogArgs", "arg",
                "(Ljava/lang/String;)Lio/edap/log/LogArgs;", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
    }



    private void writeJsonContentType(MethodVisitor mv, int varHttpResp) {
        // 输出ContentType json的header
        mv.visitVarInsn(ALOAD, varHttpResp);
        mv.visitFieldInsn(GETSTATIC, "io/edap/http/header/ContentTypeHeader", "JSON",
                "Lio/edap/http/header/ContentTypeHeader;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "contentType",
                "(Lio/edap/http/header/ContentTypeHeader;)Lio/edap/http/HttpResponse;", false);
        mv.visitInsn(POP);
    }

    private void visitWriteNotImpl(MethodVisitor mv, Label lbBeanNotNull, int varHttpResp) {
        // 如果bean为空输出没有实现类的错误
        mv.visitFieldInsn(GETSTATIC, handlerName, "bean", "L" + serviceIf + ";");
        mv.visitJumpInsn(IFNONNULL, lbBeanNotNull);
        mv.visitVarInsn(ALOAD, varHttpResp);
        mv.visitFieldInsn(GETSTATIC, handlerName, "NOT_IMPL_DATA", "[B");
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                "([B)Lio/edap/http/HttpResponse;");
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
    }

    private void visitInvokeBean(MethodVisitor mv, int varHttReq, int varHttpResp, int varReq) {
        if (isPost) {
            mv.visitVarInsn(ALOAD, varHttpResp);
            mv.visitFieldInsn(GETSTATIC, handlerName, "bean", "L" + serviceIf + ";");
            mv.visitVarInsn(ALOAD, varHttReq);

            mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/http/HttpRequest", "getBody",
                    "()Lio/edap/util/ByteData;", true);
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/util/ByteData", "getBytes", "()[B", false);
            mv.visitLdcInsn(Type.getType("L" + reqType + ";"));
            mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "parseObject",
                    "([BLjava/lang/Class;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, reqType);
            mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(), "(L" + reqType + ";)L" + respType + ";", true);
            mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "toJsonBytes",
                    "(Ljava/lang/Object;)[B", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                    "([B)Lio/edap/http/HttpResponse;", false);
            mv.visitInsn(POP);

        } else {
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


            mv.visitVarInsn(ALOAD, varHttpResp);
            mv.visitFieldInsn(GETSTATIC, handlerName, "bean",
                    "L" + serviceIf + ";");
            mv.visitVarInsn(ALOAD, varReq);
            mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(),
                    "(L" + reqType + ";)L" + respType + ";", true);
            mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "toJsonBytes",
                    "(Ljava/lang/Object;)[B", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                    "([B)Lio/edap/http/HttpResponse;", false);
            mv.visitInsn(POP);
        }
    }

    private void visitHandleMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "handle",
                "(Lio/edap/http/HttpRequest;Lio/edap/http/HttpResponse;)V",
                null, new String[] { "java/io/IOException" });
        mv.visitCode();

        Label lbStart   = new Label();
        Label lbEnd     = new Label();
        Label lbEx      = new Label();
        mv.visitTryCatchBlock(lbStart, lbEnd, lbEx, "java/lang/Throwable");
        int varHttReq = 1;
        int varHttpResp = varHttReq + 1;
        int varResolveRes = varHttpResp + 1;
        int varReq        = varResolveRes + 1;
        int varBizEx      = varReq + 1;
        int varErrorEx    = varBizEx + 1;
        int varEx         = varErrorEx + 1;
        //String resolverBeanName = null;
        if (resolverBeanName != null) {
            Label lbError = new Label();
            mv.visitTryCatchBlock(lbStart, lbEnd, lbError, null);
            Label lbExHdl = new Label();
            mv.visitTryCatchBlock(lbEx, lbExHdl, lbError, null);
            Label lbFinalHdl = new Label();
            mv.visitTryCatchBlock(lbError, lbFinalHdl, lbError, null);

            writeJsonContentType(mv, varHttpResp);

            // 如果bean为空输出没有实现类的错误

            // 如果bean不为空则跳转
            Label lbBeanExists = new Label();

            // 如果bean为空输出没有实现类的错误
            visitWriteNotImpl(mv, lbBeanExists, varHttpResp);

            mv.visitLabel(lbBeanExists);
            mv.visitFieldInsn(GETSTATIC, handlerName, "userResolver", "L" + resolverIf + ";");
            mv.visitVarInsn(ALOAD, varHttReq);
            mv.visitMethodInsn(INVOKEINTERFACE, resolverIf, "resolve",
                    "(Lio/edap/http/HttpRequest;)L" + resolverIf + "$ResolverResult;", true);
            mv.visitVarInsn(ASTORE, varResolveRes);
            mv.visitVarInsn(ALOAD, varResolveRes);
            mv.visitMethodInsn(INVOKEVIRTUAL, resolverIf + "$ResolverResult", "isSuccess",
                    "()Z", false);
            // 如果用户相关信息解析正确则跳转处理
            mv.visitJumpInsn(IFNE, lbStart);
            // 如果用户信息解析失败打印日志，然后输出用户未登录信息
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, handlerName, "log", "Lio/edap/log/Logger;");
            mv.visitLdcInsn("UserResolver.resolve error {}");
            mv.visitVarInsn(ALOAD, varResolveRes);
            visitNoLoginLogLambda();
            mv.visitInvokeDynamicInsn("accept", "(L" + resolverIf + "$ResolverResult;)Ljava/util/function/Consumer;",
                    new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                                    "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                    new Object[]{Type.getType("(Ljava/lang/Object;)V"),
                            new Handle(Opcodes.H_INVOKESTATIC, handlerName,
                                    "lambda$handle$1",
                                    "(L" + resolverIf + "$ResolverResult;Lio/edap/log/LogArgs;)V", false),
                            Type.getType("(Lio/edap/log/LogArgs;)V")});
            mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/log/Logger", "warn",
                    "(Ljava/lang/String;Ljava/util/function/Consumer;)V", true);
            mv.visitVarInsn(ALOAD, varHttpResp);
            mv.visitFieldInsn(GETSTATIC, handlerName, "NO_LOGIN_ERROR_DATA", "[B");
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                    "([B)Lio/edap/http/HttpResponse;", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);

            // 设置解析的用户信息到线程上下文
            mv.visitLabel(lbStart);
            mv.visitVarInsn(ALOAD, varResolveRes);
            String reqCtxName = toInternalName(RequestContext.class.getName());
            mv.visitMethodInsn(INVOKEVIRTUAL, resolverIf + "$ResolverResult", "getRequestContext",
                    "()L" + reqCtxName + ";", false);
            mv.visitMethodInsn(INVOKESTATIC, toInternalName(RequestContextHolder.class.getName()), "set",
                    "(L" + reqCtxName + ";)V", false);

            visitInvokeBean(mv, varHttReq, varHttpResp, varReq);

            // 清楚线程上下文
            mv.visitLabel(lbEnd);
            mv.visitMethodInsn(INVOKESTATIC, toInternalName(RequestContextHolder.class.getName()), "clear",
                    "()V", false);

            Label label7 = new Label();
            mv.visitJumpInsn(GOTO, label7);

            // 开始处理异常
            mv.visitLabel(lbEx);
            // 打印日志
            mv.visitVarInsn(ASTORE, varBizEx);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, handlerName, "log", "Lio/edap/log/Logger;");
            mv.visitLdcInsn("{}.{} invoke error");
            visitBizLogLambda();
            mv.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;",
                    new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                                    "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                                    "Ljava/lang/invoke/CallSite;", false),
                    new Object[]{Type.getType("(Ljava/lang/Object;)V"),
                            new Handle(Opcodes.H_INVOKESTATIC, handlerName, "lambda$handle$0",
                                    "(Lio/edap/log/LogArgs;)V", false),
                            Type.getType("(Lio/edap/log/LogArgs;)V")});
            mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/log/Logger", "warn",
                    "(Ljava/lang/String;Ljava/util/function/Consumer;)V", true);
            // 输出错误信息到客户端
            mv.visitVarInsn(ALOAD, varHttpResp);
            mv.visitFieldInsn(GETSTATIC, handlerName, "NO_LOGIN_ERROR_DATA", "[B");
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                    "([B)Lio/edap/http/HttpResponse;", false);
            mv.visitInsn(POP);

            // 清除线程上下文
            mv.visitLabel(lbExHdl);
            mv.visitMethodInsn(INVOKESTATIC, toInternalName(RequestContextHolder.class.getName()),
                    "clear", "()V", false);
            mv.visitJumpInsn(GOTO, label7);

            // 处理java.lang.Error的异常
            mv.visitLabel(lbError);
            mv.visitVarInsn(ASTORE, varErrorEx);

            mv.visitLabel(lbFinalHdl);
            mv.visitMethodInsn(INVOKESTATIC, toInternalName(RequestContextHolder.class.getName()),
                    "clear", "()V", false);

            mv.visitVarInsn(ALOAD, varErrorEx);
            mv.visitInsn(ATHROW);

            mv.visitLabel(label7);
        } else {
            writeJsonContentType(mv, varHttpResp);

            // 如果bean为空输出没有实现类的错误
            visitWriteNotImpl(mv, lbStart, varHttpResp);

            mv.visitLabel(lbStart);
            visitInvokeBean(mv, varHttReq, varHttpResp, varReq);

            mv.visitLabel(lbEnd);

            Label lbFinish = new Label();
            mv.visitJumpInsn(GOTO, lbFinish);
            mv.visitLabel(lbEx);
            mv.visitVarInsn(ASTORE, varEx);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, handlerName, "log", "Lio/edap/log/Logger;");
            mv.visitLdcInsn("{}.{} invoke error");
            visitBizLogLambda();
            mv.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;",
                    new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
                                    "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
                                    "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                                    "Ljava/lang/invoke/CallSite;", false),
                    new Object[]{Type.getType("(Ljava/lang/Object;)V"),
                            new Handle(Opcodes.H_INVOKESTATIC, handlerName, "lambda$handle$0",
                                    "(Lio/edap/log/LogArgs;)V", false),
                            Type.getType("(Lio/edap/log/LogArgs;)V")});
            mv.visitMethodInsn(INVOKEINTERFACE, "io/edap/log/Logger", "warn",
                    "(Ljava/lang/String;Ljava/util/function/Consumer;)V", true);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitFieldInsn(GETSTATIC, handlerName, "BIZ_EXC_DATA", "[B");
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                    "([B)Lio/edap/http/HttpResponse;", false);
            mv.visitInsn(POP);
            mv.visitLabel(lbFinish);
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();

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
                    "L" + resolverIf +";", null, null);
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
            mv.visitTypeInsn(CHECKCAST, resolverIf);
            mv.visitFieldInsn(PUTSTATIC, handlerName, "userResolver",
                    "L" + resolverIf + ";");
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private void visitCinit() {
        FieldVisitor fvNotImpl = cw.visitField(ACC_PRIVATE | ACC_STATIC, "NOT_IMPL_DATA",
                "[B", null, null);
        fvNotImpl.visitEnd();
        FieldVisitor fvBizExc = cw.visitField(ACC_PRIVATE | ACC_STATIC, "BIZ_EXC_DATA",
                "[B", null, null);
        fvBizExc.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        // 初始化常见错误信息的map
        int varMap = 0;
        mv.visitTypeInsn(NEW, "java/util/HashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varMap);

        // 构造没有实现类的错误map
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitLdcInsn("code");
        mv.visitFieldInsn(GETSTATIC, handlerName, "NO_IMPL_BEAN_CODE", "I");
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                "(I)Ljava/lang/Integer;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitLdcInsn("message");
        mv.visitLdcInsn(Type.getType("L" + serviceIf + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        mv.visitFieldInsn(GETSTATIC, handlerName, "NOT_IMPL_MSG", "Ljava/lang/String;");
        mv.visitInvokeDynamicInsn("makeConcatWithConstants", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false), new Object[]{"\u0001\u0001"});
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        // 序列化没有实现类信息
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "toJsonBytes", "(Ljava/lang/Object;)[B", false);
        mv.visitFieldInsn(PUTSTATIC, handlerName, "NOT_IMPL_DATA", "[B");


        mv.visitVarInsn(ALOAD, varMap);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "clear", "()V", true);
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitLdcInsn("code");
        mv.visitFieldInsn(GETSTATIC, handlerName, "BIZ_EXCEPTION_CODE", "I");
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitLdcInsn("message");
        mv.visitLdcInsn(Type.getType("L" + serviceIf + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        mv.visitFieldInsn(GETSTATIC, handlerName, "BIZ_EXCEPTION_MSG", "Ljava/lang/String;");
        mv.visitInvokeDynamicInsn("makeConcatWithConstants", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false), new Object[]{"\u0001\u0001"});
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, varMap);
        mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "toJsonBytes", "(Ljava/lang/Object;)[B", false);
        mv.visitFieldInsn(PUTSTATIC, handlerName, "BIZ_EXC_DATA", "[B");

        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
    }
}
