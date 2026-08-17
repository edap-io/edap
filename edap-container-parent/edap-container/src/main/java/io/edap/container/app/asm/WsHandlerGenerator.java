package io.edap.container.app.asm;

import io.edap.container.AppContext;
import io.edap.container.mw.AnnoData;
import io.edap.container.ws.WSServiceMsgHandler;
import io.edap.json.JsonObject;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.util.List;

import static io.edap.container.app.asm.HandlerAsmGenerator.handlerName;
import static io.edap.util.AsmUtil.toInternalName;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

/**
 * ASM 生成 {@code WSServiceMsgHandler<Object>} 实现类，固定用于第一期 JSON 路径。
 *
 * <p><b>类形态</b>（与 HttpHandlerGenerator 对齐）：
 * <pre>{@code
 *   public class <ws.cap.method>Handler
 *           extends AbstractHandler
 *           implements WSServiceMsgHandler<Object> {
 *
 *       private static <protoIf> bean;
 *
 *       public <init>(AppContext appContext) {
 *           super(appContext);
 *           bean = (<protoIf>) getBean(<protoIf>.class);     // miss → null
 *       }
 *
 *       public Object handle(Object msg) {
 *           if (msg == null) return null;
 *           JsonObject json = (JsonObject) msg;
 *           Object pojo;
 *           if (paramType == JsonObject.class) {
 *               pojo = json;
 *           } else {
 *               pojo = Eson.toBean(json, paramType);        // 直接 Map → Bean，省 round-trip
 *           }
 *           return bean.<method>(pojo);
 *       }
 *   }
 * }</pre>
 *
 * <p><b>异常策略</b>：不在 handler 内部 try/catch —— {@link io.edap.container.ws.ServiceWSHandler#onMessage}
 *     外层已包 {@code catch(Throwable)} → 统一 sendError(code:500)。handler 抛 ClassCastException /
 *     Eson 解析异常 → 自然冒泡到外层 → 业务侧收到 500 而非断连。</p>
 *
 * <p><b>为什么 {@code bean} 用 static 字段</b>：跨多次调用复用，避开每次 invokevirtual 时再查 BeanContainer
 *     的开销；与 HttpHandlerGenerator 的设计对称。</p>
 *
 * <p><b>第二期扩展</b>：protobuf wire 路径另起 generator（{@code WsProtoHandlerGenerator}），
 *     本类只覆盖 JSON 路径，不动 byte[] 入参的 handle 方法（ServiceWSHandler 第一期固定 501）。</p>
 */
public class WsHandlerGenerator {

    private static final String IFACE_NAME         = toInternalName(WSServiceMsgHandler.class.getName());
    private static final String PARENT_NAME        = toInternalName(AbstractHandler.class.getName());
    private static final String APP_CONTEXT_NAME   = toInternalName(AppContext.class.getName());
    private static final String JSON_OBJECT_NAME   = toInternalName(JsonObject.class.getName());
    private static final String ESON_NAME          = "io/edap/json/Eson";

    private final List<AnnoData> annoDatas;
    private final Class<?>       iface;
    private final Method        method;
    private final String        handlerName;             // internal name（斜杠），用于 GETSTATIC / PUTSTATIC
    private final Class<?>      serviceIf;
    private final String        serviceIfInternal;
    private final Class<?>      paramType;
    private final String        paramTypeInternal;
    private final boolean       paramIsJsonObject;
    private final boolean       paramIsVoid;

    private ClassWriter cw;

    public WsHandlerGenerator(List<AnnoData> annoDatas, Class<?> protoIf, Method method) {
        this.annoDatas          = annoDatas;
        this.handlerName        = toInternalName(handlerName(WSServiceMsgHandler.class, protoIf, method));
        this.iface              = protoIf;
        this.method             = method;
        this.serviceIf          = protoIf;
        this.serviceIfInternal  = toInternalName(protoIf.getName());

        // bean method 必须是单参（JsonObject 或业务 POJO）；其他形态当前不支持
        Class<?>[] params = method.getParameterTypes();
        if (params == null || params.length != 1) {
            throw new IllegalArgumentException(
                    "WS handler method must have exactly 1 parameter, got "
                            + (params == null ? 0 : params.length)
                            + " for " + protoIf.getName() + "#" + method.getName());
        }
        this.paramType          = params[0];
        // 基本类型当前不支持：Eson.parseObject 返 Object，CHECKCAST 期望引用类型 descriptor（L...;）
        if (paramType.isPrimitive()) {
            throw new IllegalArgumentException(
                    "WS handler method parameter type must not be primitive, got "
                            + paramType.getName() + " for "
                            + protoIf.getName() + "#" + method.getName());
        }
        this.paramTypeInternal  = toInternalName(paramType.getName());
        this.paramIsJsonObject  = (paramType == JsonObject.class);
        // paramType == void.class 实际不可能（方法无参才可能 void，已被 params.length != 1 拦下）；
        // 保留分支仅为防御性，避免后续 NPE。
        this.paramIsVoid        = (paramType == void.class);
    }

    public byte[] generate() {
        String[] ifaceName = new String[]{IFACE_NAME};
        cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        // implements WSServiceMsgHandler<Object> —— 把泛型接口擦除为 Object 实现，避免类自身再带类型参数
        String signature = "L" + PARENT_NAME + ";"
                + "L" + IFACE_NAME + "<Ljava/lang/Object;>;";
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, handlerName, signature, PARENT_NAME, ifaceName);

        visitInit();
        visitHandleMethod();

        return cw.toByteArray();
    }

    /**
     * {@code <init>(AppContext)}：调父类构造 → bean = (protoIf) getBean(protoIf.class)。
     * bean 缺失（应用未注册该 proto 接口的实现）→ bean 留 null，handle 阶段 GETSTATIC 会拿到 null，
     * 后续 invokevirtual NPE → 由 ServiceWSHandler 外层 catch 转 sendError(500)。
     */
    private void visitInit() {
        // private static <protoIf> bean;
        FieldVisitor fv = cw.visitField(ACC_PRIVATE | ACC_STATIC, "bean",
                "L" + serviceIfInternal + ";", null, null);
        fv.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(L" + APP_CONTEXT_NAME + ";)V", null, null);
        mv.visitCode();
        // super(appContext);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "<init>",
                "(L" + APP_CONTEXT_NAME + ";)V", false);
        // Object bean = getBean(<protoIf>.class);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(Type.getType("L" + serviceIfInternal + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, handlerName, "getBean",
                "(Ljava/lang/Class;)Ljava/lang/Object;", false);
        int varBeanObj = 2;
        mv.visitVarInsn(ASTORE, varBeanObj);
        mv.visitVarInsn(ALOAD, varBeanObj);
        Label lbNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbNotNull);
        // miss → bean = null
        mv.visitInsn(ACONST_NULL);
        mv.visitFieldInsn(PUTSTATIC, handlerName, "bean", "L" + serviceIfInternal + ";");
        Label lbFinish = new Label();
        mv.visitJumpInsn(GOTO, lbFinish);
        // hit → bean = (<protoIf>) beanObj
        mv.visitLabel(lbNotNull);
        mv.visitVarInsn(ALOAD, varBeanObj);
        mv.visitTypeInsn(CHECKCAST, serviceIfInternal);
        mv.visitFieldInsn(PUTSTATIC, handlerName, "bean", "L" + serviceIfInternal + ";");
        mv.visitLabel(lbFinish);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code Object handle(Object msg)}：
     * <pre>
     *   if (msg == null) return null;
     *   JsonObject json = (JsonObject) msg;
     *   Object pojo;
     *   if (paramIsJsonObject) {
     *       pojo = json;
     *   } else {
     *       pojo = Eson.toBean(json, paramType);    // 直接 Map → Bean，避免 toJsonString+parseObject 的 round-trip
     *   }
     *   return bean.<method>((paramType) pojo);
     * </pre>
     */
    private void visitHandleMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "handle",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();

        Label lbNull = new Label();

        // if (msg == null) return null;
        mv.visitVarInsn(ALOAD, 1);
        mv.visitJumpInsn(IFNULL, lbNull);

        // JsonObject json = (JsonObject) msg;     —— local 2
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, JSON_OBJECT_NAME);
        int varJson = 2;
        mv.visitVarInsn(ASTORE, varJson);

        // Object pojo;     —— local 3
        int varPojo = 3;
        if (paramIsVoid) {
            // 极少见（接口方法签名 void param）—— 直接塞 null，避免 Eson 报错
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, varPojo);
        } else if (paramIsJsonObject) {
            // pojo = json;
            mv.visitVarInsn(ALOAD, varJson);
            mv.visitVarInsn(ASTORE, varPojo);
        } else {
            // pojo = Eson.toBean(json, paramType);    —— 直接 Map → Bean（避免 toJsonString + parseObject 的 round-trip）
            //   JsonObject 本身即 Map<String,Object>，Eson.toBean 路径走 ASM 生成的 MapBeanDecoder
            mv.visitVarInsn(ALOAD, varJson);
            mv.visitLdcInsn(Type.getType("L" + paramTypeInternal + ";"));
            mv.visitMethodInsn(INVOKESTATIC, ESON_NAME, "toBean",
                    "(Ljava/util/Map;Ljava/lang/Class;)Ljava/lang/Object;", false);
            // 业务方法签名固定为 (LparamType;)Ljava/lang/Object;，需 cast 才能 INVOKEINTERFACE
            mv.visitTypeInsn(CHECKCAST, paramTypeInternal);
            mv.visitVarInsn(ASTORE, varPojo);
        }

        // return bean.<method>(pojo);
        mv.visitFieldInsn(GETSTATIC, handlerName, "bean", "L" + serviceIfInternal + ";");
        mv.visitVarInsn(ALOAD, varPojo);
        String methodDesc = "(L" + paramTypeInternal + ";)Ljava/lang/Object;";
        mv.visitMethodInsn(INVOKEINTERFACE, serviceIfInternal, method.getName(),
                methodDesc, true);
        mv.visitInsn(ARETURN);

        // null msg → return null;
        mv.visitLabel(lbNull);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
