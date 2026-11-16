package io.edap.container.app.asm;

import io.edap.container.AppContext;
import io.edap.container.mw.AnnoData;
import io.edap.grpc.GrpcCall;
import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcHandler;
import io.edap.grpc.GrpcStatus;
import io.edap.protobuf.annotation.ProtoGrpc;
import io.edap.rpc.StreamObserver;
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
 * ASM 生成 {@link GrpcHandler} 实现类 —— 把 proto 生成的服务接口方法接到 gRPC 调用上。
 *
 * <p><b>类形态</b>（与 {@link WsHandlerGenerator} 对齐）：
 * <pre>{@code
 *   public class grpc.com.example.reviewservice.ListHandler
 *           extends AbstractHandler
 *           implements GrpcHandler {
 *
 *       private static <protoIf> bean;
 *
 *       public <init>(AppContext appContext) {
 *           super(appContext);
 *           bean = (<protoIf>) getBean(<protoIf>.class);     // miss → null
 *       }
 *
 *       public void handle(GrpcCall call) throws Exception {
 *           // 按 @ProtoGrpc.type() 分四种骨架
 *       }
 *   }
 * }</pre>
 *
 * <p><b>四种调用形态</b>（与 proto 代码生成器产出的 Java 签名一一对应）：
 * <table border="1">
 *   <caption>骨架</caption>
 *   <tr><th>形态</th><th>业务方法签名</th><th>骨架</th></tr>
 *   <tr><td>UNARY</td><td>{@code Resp m(Req)}</td>
 *       <td>解码请求 → 调业务 → sendOne(返回值) → close(OK)</td></tr>
 *   <tr><td>SERVER_STREAM</td><td>{@code void m(Req, StreamObserver<Resp>)}</td>
 *       <td>解码请求 → 调业务（响应观察者由框架给）→ 由业务负责收尾</td></tr>
 *   <tr><td>CLIENT_STREAM</td><td>{@code StreamObserver<Req> m(StreamObserver<Resp>)}</td>
 *       <td>调业务 → 把返回的请求观察者交回框架</td></tr>
 *   <tr><td>BIDIRECTIONAL</td><td>{@code StreamObserver<Req> m(StreamObserver<Resp>)}</td>
 *       <td>同 CLIENT_STREAM</td></tr>
 * </table>
 *
 * <p><b>为什么 DTO 类型来自 {@code @ProtoGrpc} 而不是方法签名</b>：流式方法在 JVM
 * descriptor 里只剩 {@code io.edap.rpc.StreamObserver}（泛型被擦除），拿不到真实的 DTO 类型。
 * {@code @ProtoGrpc.req()/resp()} 是唯一载体 —— 见 {@link ProtoGrpc} 的说明。</p>
 *
 * <p><b>异常策略</b>：handler 内部不 try/catch —— 业务抛出的异常直接冒泡到
 * {@code GrpcCallImpl.invokeHandler}，由它统一转成 {@code grpc-status: 2}（UNKNOWN）。
 * 生成的字节码没有 checked exception，所以不需要生成 Exception table。</p>
 */
public class GrpcHandlerGenerator {

    private static final String IFACE_NAME       = toInternalName(GrpcHandler.class.getName());
    private static final String PARENT_NAME      = toInternalName(AbstractHandler.class.getName());
    private static final String APP_CONTEXT_NAME = toInternalName(AppContext.class.getName());
    private static final String CALL_NAME        = toInternalName(GrpcCall.class.getName());
    private static final String CODEC_NAME       = toInternalName(GrpcCodec.class.getName());
    private static final String STATUS_NAME      = toInternalName(GrpcStatus.class.getName());

    private static final String CALL_DESC        = "L" + CALL_NAME + ";";
    private static final String STATUS_DESC      = "L" + STATUS_NAME + ";";
    private static final String OBSERVER_DESC    = "L" + toInternalName(StreamObserver.class.getName()) + ";";

    /** bean 缺失时回给客户端的状态码 —— 与 HTTP 侧的「接口还没有实现」语义对齐。 */
    private static final String NOT_IMPL_MESSAGE = "接口还没有实现";

    private final Class<?> protoIf;
    private final Method   method;
    private final String   handlerName;      // internal name（斜杠）
    private final String   serviceIf;        // proto 接口的 internal name
    private final String   serviceIfDesc;    // "L<serviceIf>;"
    private final String   methodDesc;       // 业务方法的 JVM descriptor（擦除后）
    private final ProtoGrpc.MethodType methodType;
    private final Class<?> reqType;

    private ClassWriter cw;

    /**
     * @param annoDatas 方法上的全部注解数据（用来找 {@code @ProtoGrpc}）
     * @param protoIf   proto 服务接口
     * @param method    proto 接口上的目标方法
     * @param loader    appCL —— 用来加载 {@code @ProtoGrpc.req()/resp()} 里的 DTO 类
     */
    public GrpcHandlerGenerator(List<AnnoData> annoDatas, Class<?> protoIf, Method method, ClassLoader loader) {
        this.protoIf      = protoIf;
        this.method       = method;
        this.handlerName  = toInternalName(handlerName(GrpcHandler.class, protoIf, method));
        this.serviceIf    = toInternalName(protoIf.getName());
        this.serviceIfDesc = "L" + serviceIf + ";";
        this.methodDesc   = Type.getMethodDescriptor(method);

        AnnoData grpc = findProtoGrpc(annoDatas);
        if (grpc == null) {
            throw new IllegalArgumentException("方法 " + protoIf.getName() + "#" + method.getName()
                    + " 上没有 @ProtoGrpc 注解，无法生成 gRPC handler");
        }
        this.methodType = readMethodType(grpc);
        this.reqType    = resolveDtoType(grpc, "req", loader);
        // resp 只做校验：字节码用不到（sendOne 由框架按 GrpcMethod.respType 编码），
        // 但缺失说明注解写坏了 —— 部署期炸掉比线上解码失败好。
        resolveDtoType(grpc, "resp", loader);
    }

    /**
     * 从方法级注解里找 {@code @ProtoGrpc}。
     */
    private static AnnoData findProtoGrpc(List<AnnoData> annoDatas) {
        if (annoDatas == null) {
            return null;
        }
        String type = ProtoGrpc.class.getName();
        for (AnnoData anno : annoDatas) {
            if (type.equals(anno.getType())) {
                return anno;
            }
        }
        return null;
    }

    /**
     * 读 {@code @ProtoGrpc.type()} —— 注解扫描器把枚举值存成字面名字（{@code "UNARY"}）。
     */
    private static ProtoGrpc.MethodType readMethodType(AnnoData grpc) {
        Object value = grpc.getValues().get("type");
        if (value == null) {
            return ProtoGrpc.MethodType.UNARY;
        }
        String name = value.toString();
        for (ProtoGrpc.MethodType t : ProtoGrpc.MethodType.values()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的 @ProtoGrpc.type(): " + name);
    }

    /**
     * 读 DTO 类型。注解里的 {@code req()/resp()} 是类名字符串（扫描器把 Class 归一化成 FQCN），
     * 要用 appCL 加载 —— 生成的 handler 类由 appCL 的子类加载，DTO 也归 appCL 管。
     *
     * @param grpc   {@code @ProtoGrpc} 的注解数据
     * @param key    {@code "req"} 或 {@code "resp"}
     * @param loader appCL
     */
    private static Class<?> resolveDtoType(AnnoData grpc, String key, ClassLoader loader) {
        Class<?> type = loadClass(grpc.getValues().get(key), loader);
        if (type == null) {
            throw new IllegalArgumentException("@ProtoGrpc 缺少 " + key + " 类型");
        }
        return type;
    }

    private static Class<?> loadClass(Object value, ClassLoader loader) {
        if (value == null) {
            return null;
        }
        String name = value.toString();
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("加载 DTO 类失败: " + name, e);
        }
    }

    public byte[] generate() {
        cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, handlerName, null, PARENT_NAME, new String[]{IFACE_NAME});

        visitInit();
        visitHandleMethod();

        return cw.toByteArray();
    }

    /**
     * {@code <init>(AppContext)}：调父类构造 → {@code bean = (protoIf) getBean(protoIf.class)}。
     * bean 缺失 → bean 留 null，handle 阶段回 UNIMPLEMENTED（不抛异常打断整个部署）。
     */
    private void visitInit() {
        FieldVisitor fv = cw.visitField(ACC_PRIVATE | ACC_STATIC, "bean", serviceIfDesc, null, null);
        fv.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + APP_CONTEXT_NAME + ";)V", null, null);
        mv.visitCode();
        // super(appContext);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "<init>", "(L" + APP_CONTEXT_NAME + ";)V", false);
        // Object beanObj = getBean(<protoIf>.class);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(Type.getType(serviceIfDesc));
        mv.visitMethodInsn(INVOKEVIRTUAL, handlerName, "getBean",
                "(Ljava/lang/Class;)Ljava/lang/Object;", false);
        int varBeanObj = 2;
        mv.visitVarInsn(ASTORE, varBeanObj);
        mv.visitVarInsn(ALOAD, varBeanObj);
        Label lbNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbNotNull);
        mv.visitInsn(ACONST_NULL);
        mv.visitFieldInsn(PUTSTATIC, handlerName, "bean", serviceIfDesc);
        Label lbFinish = new Label();
        mv.visitJumpInsn(GOTO, lbFinish);
        mv.visitLabel(lbNotNull);
        mv.visitVarInsn(ALOAD, varBeanObj);
        mv.visitTypeInsn(CHECKCAST, serviceIf);
        mv.visitFieldInsn(PUTSTATIC, handlerName, "bean", serviceIfDesc);
        mv.visitLabel(lbFinish);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code void handle(GrpcCall call) throws Exception}。
     *
     * <p>局部变量表：0 = this，1 = call，2 = 请求字节，3 = 请求 DTO，4 = 响应 DTO（一元）。</p>
     */
    private void visitHandleMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "handle", "(" + CALL_DESC + ")V",
                null, new String[]{"java/lang/Exception"});
        mv.visitCode();

        // bean == null → close(UNIMPLEMENTED) 并返回
        Label lbBeanOk = new Label();
        mv.visitFieldInsn(GETSTATIC, handlerName, "bean", serviceIfDesc);
        mv.visitJumpInsn(IFNONNULL, lbBeanOk);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETSTATIC, STATUS_NAME, "UNIMPLEMENTED", STATUS_DESC);
        mv.visitLdcInsn(NOT_IMPL_MESSAGE);
        mv.visitMethodInsn(INVOKEINTERFACE, CALL_NAME, "close",
                "(" + STATUS_DESC + "Ljava/lang/String;)V", true);
        mv.visitInsn(RETURN);
        mv.visitLabel(lbBeanOk);

        switch (methodType) {
            case UNARY:
                emitUnary(mv);
                break;
            case SERVER_STREAM:
                emitServerStream(mv);
                break;
            case CLIENT_STREAM:
            case BIDIRECTIONAL:
                emitClientStream(mv);
                break;
            default:
                throw new IllegalStateException("未处理的 gRPC 调用形态: " + methodType);
        }

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * 一元：解码请求 → 调业务 → sendOne(返回值) → close(OK)。
     */
    private void emitUnary(MethodVisitor mv) {
        emitDecodeRequest(mv);
        // Resp resp = bean.m(req);
        mv.visitFieldInsn(GETSTATIC, handlerName, "bean", serviceIfDesc);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(), methodDesc, true);
        mv.visitVarInsn(ASTORE, 4);
        // call.sendOne(resp);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, CALL_NAME, "sendOne", "(Ljava/lang/Object;)V", true);
        // call.close(GrpcStatus.OK, null);
        emitCloseOk(mv);
    }

    /**
     * 服务端流式：解码请求 → 调业务（响应观察者由框架提供）→ 由业务负责收尾。
     */
    private void emitServerStream(MethodVisitor mv) {
        emitDecodeRequest(mv);
        mv.visitFieldInsn(GETSTATIC, handlerName, "bean", serviceIfDesc);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, CALL_NAME, "responseObserver", "()" + OBSERVER_DESC, true);
        mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(), methodDesc, true);
        mv.visitInsn(RETURN);
    }

    /**
     * 客户端流式 / 双向流式：调业务拿到请求观察者 → 交回框架。
     *
     * <p>泛型被擦除，业务方法返回的 {@code StreamObserver<Req>} 在字节码层面就是
     * {@code io.edap.rpc.StreamObserver}，可以直接塞给 {@code setRequestObserver}。</p>
     */
    private void emitClientStream(MethodVisitor mv) {
        mv.visitFieldInsn(GETSTATIC, handlerName, "bean", serviceIfDesc);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, CALL_NAME, "responseObserver", "()" + OBSERVER_DESC, true);
        mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(), methodDesc, true);
        mv.visitVarInsn(ASTORE, 5);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEINTERFACE, CALL_NAME, "setRequestObserver", "(" + OBSERVER_DESC + ")V", true);
        mv.visitInsn(RETURN);
    }

    /**
     * {@code Req req = GrpcCodec.decode(Req.class, call.requestBytes(), 0, len)}。
     *
     * <p>请求字节为 null 时按空消息处理 —— 一元调用的客户端一条消息都没发就半关闭是合法的，
     * 不能因此挂死。</p>
     */
    private void emitDecodeRequest(MethodVisitor mv) {
        // byte[] raw = call.requestBytes();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, CALL_NAME, "requestBytes", "()[B", true);
        mv.visitVarInsn(ASTORE, 2);
        // if (raw == null) raw = new byte[0];
        mv.visitVarInsn(ALOAD, 2);
        Label lbNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbNotNull);
        mv.visitInsn(ICONST_0);
        // newarray 的元素类型是操作数 —— ASM 的 API 是 visitIntInsn(NEWARRAY, atype)，
        // 不是 visitInsn(NEWARRAY)（后者在 COMPUTE_FRAMES 下会因缺操作数直接抛 IllegalArgumentException）
        mv.visitIntInsn(NEWARRAY, T_BYTE);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitLabel(lbNotNull);
        // Req req = (Req) GrpcCodec.decode(Req.class, raw, 0, raw.length);
        mv.visitLdcInsn(Type.getType("L" + toInternalName(reqType.getName()) + ";"));
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitMethodInsn(INVOKESTATIC, CODEC_NAME, "decode",
                "(Ljava/lang/Class;[BII)Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, toInternalName(reqType.getName()));
        mv.visitVarInsn(ASTORE, 3);
    }

    /**
     * {@code call.close(GrpcStatus.OK, null)}。
     */
    private void emitCloseOk(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETSTATIC, STATUS_NAME, "OK", STATUS_DESC);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEINTERFACE, CALL_NAME, "close",
                "(" + STATUS_DESC + "Ljava/lang/String;)V", true);
        mv.visitInsn(RETURN);
    }
}
