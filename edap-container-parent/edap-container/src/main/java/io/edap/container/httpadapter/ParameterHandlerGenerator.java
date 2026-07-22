package io.edap.container.httpadapter;

import io.edap.container.utils.ValueTypeConvertor;
import io.edap.http.HttpHandler;
import io.edap.http.HttpRequest;
import io.edap.microservice.annotation.ParamConf;
import io.edap.microservice.enums.ParamType;
import io.edap.util.ClazzUtil;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static io.edap.container.httpadapter.HttpHandlerRegister.buildHandlerName;
import static io.edap.util.AsmUtil.toInternalName;
import static org.objectweb.asm.Opcodes.*;

public class ParameterHandlerGenerator {

    private ClassWriter cw;

    private String IFACE_NAME = toInternalName(HttpHandler.class.getName());
    static final String PARENT_NAME = toInternalName(Object.class.getName());
    static final String HTTP_REQUEST_NAME = toInternalName(HttpRequest.class.getName());
    static final String VALUE_CONVERTOR_NAME = toInternalName(ValueTypeConvertor.class.getName());
    private String beanName;
    private String handlerName;
    private Method method;
    private HandlerConfig handlerConfig;

    public ParameterHandlerGenerator(Class beanClass, Method method, HandlerConfig handlerConfig) {
        this.beanName      = toInternalName(beanClass.getName());
        this.method        = method;
        this.handlerConfig = handlerConfig;
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        String[] ifaceName = new String[]{IFACE_NAME};

        List inners = new ArrayList<>();
        handlerName = toInternalName(buildHandlerName(method));
        String handlerDescriptor = "null";
        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, handlerName,
                handlerDescriptor, PARENT_NAME, ifaceName);

        visitInitMethod();
        visitHandleMethod();

        gci.inners = inners;
        gci.clazzName = handlerName;
        gci.clazzBytes = cw.toByteArray();
        return gci;
    }

    public void visitHandleMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "handle",
                "(Lio/edap/http/HttpRequest;Lio/edap/http/HttpResponse;)V", null,
                new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 2);
        mv.visitFieldInsn(GETSTATIC, "io/edap/http/header/ContentTypeHeader", "JSON",
                "Lio/edap/http/header/ContentTypeHeader;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "contentType",
                "(Lio/edap/http/header/ContentTypeHeader;)Lio/edap/http/HttpResponse;", false);
        mv.visitInsn(POP);
        Class<?>[] pts = method.getParameterTypes();
        int varParam = 3;
        StringBuilder paramDescs = new StringBuilder();
        if (pts.length > 0) {
            for (Class cls : pts) {
                paramDescs.append(ClazzUtil.getDescriptor(cls));
            }
            boolean hasConfig = false;
            ParamConfig[] paramConfigs = null;
            if (handlerConfig != null && handlerConfig.getParamConfig() != null
                    && handlerConfig.getParamConfig().length == pts.length) {
                hasConfig = true;
                paramConfigs = handlerConfig.getParamConfig();
            }
            if (!hasConfig) {
                Parameter[] ps = method.getParameters();
                List<ParamConfig> pcs = new ArrayList<>();
                int index = 1;
                for (Parameter p : ps) {
                    Annotation[] anns = p.getAnnotations();
                    if (anns == null || anns.length == 0) {
                        throw new RuntimeException(method.getDeclaringClass().getName() + "." + method.getName() +
                                " parameter " + index + " hasn't " + ParamConf.class.getName() + " Annotation");
                    }
                    boolean hasParamConf = false;
                    for (Annotation ann : anns) {
                        if (ann instanceof ParamConf) {
                            ParamConf paramConf = (ParamConf)ann;
                            hasParamConf = true;
                            ParamConfig pc = new ParamConfig();
                            pc.setParamName(paramConf.name());
                            pc.setParamType(paramConf.paramType());
                            pcs.add(pc);
                        }
                    }
                    if (!hasParamConf) {
                        throw new RuntimeException(method.getDeclaringClass().getName() + "." + method.getName() +
                                " " + index + " parameter hasn't " + ParamConf.class.getName() + " Annotation");
                    }
                    index++;
                }
                paramConfigs = pcs.toArray(new ParamConfig[0]);
            }
            for (ParamConfig pc : paramConfigs) {
                if (pc.getParamType() == ParamType.HTTP_PARAMETER) {
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitLdcInsn(pc.getParamName());
                    mv.visitMethodInsn(INVOKEINTERFACE, HTTP_REQUEST_NAME, "getParameter",
                            "(Ljava/lang/String;)Ljava/lang/String;", true);
                    mv.visitVarInsn(ASTORE, varParam);

                }
                varParam++;
            }
        }
        int varResult = varParam + 1;
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, handlerName, "bean", "L" + beanName + ";");
        if (varParam > 3) {
            for (int i = 3;i<varParam;i++) {
                mv.visitVarInsn(ALOAD, i);
                String name= pts[i-3].getName();
                if ("java.lang.String".equals(name)) {
                    continue;
                }
                switch (name) {
                    case "int":
                        mv.visitMethodInsn(INVOKESTATIC, VALUE_CONVERTOR_NAME, "convertToInt",
                                "(Ljava/lang/String;)I", false);
                        break;
                    case "java.lang.Integer":
                        mv.visitMethodInsn(INVOKESTATIC, VALUE_CONVERTOR_NAME, "convertToInteger",
                                "(Ljava/lang/String;)Ljava/lang/Integer;", false);
                        break;
                    case "long":
                        mv.visitMethodInsn(INVOKESTATIC, VALUE_CONVERTOR_NAME, "convertToLong",
                                "(Ljava/lang/String;)J", false);
                        break;
                    case "java.lang.long":
                        mv.visitMethodInsn(INVOKESTATIC, VALUE_CONVERTOR_NAME, "convertToLongObj",
                                "(Ljava/lang/String;)Ljava/lang/Long;", false);
                        break;
                    case "boolean":
                        mv.visitMethodInsn(INVOKESTATIC, VALUE_CONVERTOR_NAME, "convertToBoolean",
                                "(Ljava/lang/String;)Z", false);
                        break;
                    case "java.lang.Boolean":
                        mv.visitMethodInsn(INVOKESTATIC, VALUE_CONVERTOR_NAME, "convertToBooleanObj",
                                "(Ljava/lang/String;)Ljava/lang/Boolean;", false);
                        break;
                    case "float":
                        mv.visitMethodInsn(INVOKESTATIC, VALUE_CONVERTOR_NAME, "convertToFloat",
                                "(Ljava/lang/String;)F", false);
                        break;
                    case "java.lang.Float":
                        mv.visitMethodInsn(INVOKESTATIC, VALUE_CONVERTOR_NAME, "convertToFloatObj",
                                "(Ljava/lang/String;)Ljava/lang/Float;", false);
                        break;
                    case "double":
                        mv.visitMethodInsn(INVOKESTATIC, VALUE_CONVERTOR_NAME, "convertToDouble",
                                "(Ljava/lang/String;)D", false);
                        break;
                    case "java.lang.Double":
                        mv.visitMethodInsn(INVOKESTATIC, VALUE_CONVERTOR_NAME, "convertToDoubleObj",
                                "(Ljava/lang/String;)Ljava/lang/Double;", false);
                        break;
                }
            }
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, beanName, method.getName(),
                "(" + paramDescs + ")" + ClazzUtil.getDescriptor(method.getReturnType()), false);
        mv.visitVarInsn(ASTORE, varResult);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, varResult);
        mv.visitMethodInsn(INVOKESTATIC, "io/edap/json/Eson", "toJsonString",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/http/HttpResponse", "write",
                "(Ljava/lang/String;)Lio/edap/http/HttpResponse;", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 4);
        mv.visitEnd();
    }

    private void visitInitMethod() {

        FieldVisitor fieldVisitor = cw.visitField(ACC_PRIVATE, "bean",
                "L" + beanName + ";", null, null);
        fieldVisitor.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + beanName + ";)V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, handlerName, "bean", "L" + beanName + ";");
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

}
