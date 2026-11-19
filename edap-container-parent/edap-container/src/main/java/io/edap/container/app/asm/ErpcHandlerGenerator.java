/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.container.app.asm;

import io.edap.container.AppContext;
import io.edap.erpc.ErpcHandler;
import io.edap.erpc.ErpcRequest;
import io.edap.erpc.ErpcResponse;
import io.edap.erpc.codec.ErpcCodec;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;

import static io.edap.container.app.asm.HandlerAsmGenerator.handlerName;
import static io.edap.util.AsmUtil.toInternalName;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

/**
 * ASM 生成 {@link ErpcHandler} 实现类 —— 把 proto 生成的服务接口方法接到 eRPC 调用上。
 *
 * <p><b>类形态</b>（与 {@link GrpcHandlerGenerator} 对齐）：
 * <pre>{@code
 *   public class erpc.com.example.reviewservice.ListHandler
 *           extends AbstractHandler
 *           implements ErpcHandler {
 *
 *       private static <protoIf> bean;
 *
 *       public <init>(AppContext appContext) {
 *           super(appContext);
 *           bean = (<protoIf>) getBean(<protoIf>.class);     // miss → null
 *       }
 *
 *       public void handle(ErpcRequest req, ErpcResponse resp) throws Exception {
 *           // 1. 按 req.getSerializer() 解码载荷 → 业务入参
 *           // 2. bean.<method>(入参)
 *           // 3. resp.setResult(返回值) —— 编码推迟到出站写帧那一刻（载荷只拷一次）
 *       }
 *   }
 * }</pre>
 *
 * <p><b>为什么 DTO 类型来自方法签名而不是注解</b>：eRPC v1 只支持一元调用
 * （1 个参数 + 非 void 返回值），JVM descriptor 里就带着真实的 DTO 类型 ——
 * 不像 gRPC 的流式方法那样被泛型擦除。所以 {@code @ProtoErpc} 不需要
 * {@code req()/resp()}，eRPC 绑定也不依赖任何方法级协议注解。</p>
 *
 * <p><b>序列化器从帧里来</b>：解码用 {@code req.getSerializer()}（服务端按帧头
 * {@code codec} 字节现场解析出来的），编码用<b>同一个</b>序列化器 ⇒ 回帧的载荷
 * 客户端一定解得动（帧头的 codec 字节也会被原样抄回）。</p>
 *
 * <p><b>异常策略</b>：handler 内部不 try/catch —— 业务异常直接冒泡到
 * {@link io.edap.erpc.server.ErpcServer}，由它统一转成 ERROR 帧
 * （带异常类名 + message）。</p>
 */
public class ErpcHandlerGenerator {

    private static final String IFACE_NAME       = toInternalName(ErpcHandler.class.getName());
    private static final String PARENT_NAME      = toInternalName(AbstractHandler.class.getName());
    private static final String APP_CONTEXT_NAME = toInternalName(AppContext.class.getName());
    private static final String REQUEST_NAME     = toInternalName(ErpcRequest.class.getName());
    private static final String RESPONSE_NAME    = toInternalName(ErpcResponse.class.getName());
    private static final String SERIALIZER_NAME  = toInternalName(ErpcCodec.class.getName());

    private static final String REQUEST_DESC  = "L" + REQUEST_NAME + ";";
    private static final String RESPONSE_DESC = "L" + RESPONSE_NAME + ";";

    /** bean 缺失时抛出的异常描述 —— 与 HTTP 侧的「接口还没有实现」语义对齐。 */
    private static final String NOT_IMPL_MESSAGE = "接口还没有实现";

    private final Class<?> protoIf;
    private final Method   method;
    private final String   handlerName;      // internal name（斜杠）
    private final String   serviceIf;        // proto 接口的 internal name
    private final String   serviceIfDesc;    // "L<serviceIf>;"
    private final String   methodDesc;       // 业务方法的 JVM descriptor（擦除后）
    private final Class<?> reqType;          // 请求 DTO 类型
    private final String   reqTypeInternal;  // 请求 DTO 的 internal name

    private ClassWriter cw;

    /**
     * @param protoIf proto 服务接口
     * @param method  proto 接口上的目标方法（一元：1 参 + 非 void 返回）
     */
    public ErpcHandlerGenerator(Class<?> protoIf, Method method) {
        this.protoIf          = protoIf;
        this.method           = method;
        this.handlerName      = toInternalName(handlerName(ErpcHandler.class, protoIf, method));
        this.serviceIf        = toInternalName(protoIf.getName());
        this.serviceIfDesc    = "L" + serviceIf + ";";
        this.methodDesc       = Type.getMethodDescriptor(method);

        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1) {
            throw new IllegalArgumentException("eRPC 只支持一元调用，但方法 "
                    + protoIf.getName() + "#" + method.getName() + " 有 " + params.length + " 个参数");
        }
        this.reqType          = params[0];
        this.reqTypeInternal  = toInternalName(reqType.getName());
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
     * bean 缺失 → bean 留 null，handle 阶段抛「接口还没有实现」（框架转成 ERROR 帧，
     * 不打断整个部署）。
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
     * {@code void handle(ErpcRequest req, ErpcResponse resp) throws Exception}。
     *
     * <p>局部变量表：0 = this，1 = req，2 = resp，3 = 请求字节，4 = 序列化器，
     * 5 = 请求 DTO，6 = 业务返回值。</p>
     *
     * <p>★ <b>1 和 2 是方法的两个入参，不能当临时变量用</b> ——
     * {@code resp} 在局部变量 2 上，早期版本把临时值也塞进 2 ⇒
     * {@code resp} 被覆盖，随后 {@code resp.setResult(...)} 拿到的接收者是
     * 别的对象，JVM 校验期直接报 VerifyError。</p>
     */
    private void visitHandleMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "handle", "(" + REQUEST_DESC + RESPONSE_DESC + ")V",
                null, new String[]{"java/lang/Exception"});
        mv.visitCode();

        final int vReq = 1;      // ErpcRequest
        final int vResp = 2;     // ErpcResponse
        final int vRaw = 3;      // byte[]
        final int vSer = 4;      // ErpcCodec
        final int vDto = 5;      // 请求 DTO
        final int vRet = 6;      // 业务返回值

        // bean == null → throw new IllegalStateException("接口还没有实现")
        Label lbBeanOk = new Label();
        mv.visitFieldInsn(GETSTATIC, handlerName, "bean", serviceIfDesc);
        mv.visitJumpInsn(IFNONNULL, lbBeanOk);
        mv.visitTypeInsn(NEW, "java/lang/IllegalStateException");
        mv.visitInsn(DUP);
        mv.visitLdcInsn(NOT_IMPL_MESSAGE);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(lbBeanOk);

        // byte[] raw = req.getPayload();
        mv.visitVarInsn(ALOAD, vReq);
        mv.visitMethodInsn(INVOKEVIRTUAL, REQUEST_NAME, "getPayload", "()[B", false);
        mv.visitVarInsn(ASTORE, vRaw);
        // if (raw == null) raw = new byte[0];
        mv.visitVarInsn(ALOAD, vRaw);
        Label lbRawNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbRawNotNull);
        mv.visitInsn(ICONST_0);
        mv.visitIntInsn(NEWARRAY, T_BYTE);
        mv.visitVarInsn(ASTORE, vRaw);
        mv.visitLabel(lbRawNotNull);
        // ErpcCodec ser = req.getSerializer();
        mv.visitVarInsn(ALOAD, vReq);
        mv.visitMethodInsn(INVOKEVIRTUAL, REQUEST_NAME, "getSerializer", "()L" + SERIALIZER_NAME + ";", false);
        mv.visitVarInsn(ASTORE, vSer);
        // Req dto = (Req) ser.decode(raw, 0, raw.length, Req.class);
        // ★ 接收者 ser 必须先压栈 —— invokeinterface 的 count 含 this
        mv.visitVarInsn(ALOAD, vSer);
        mv.visitVarInsn(ALOAD, vRaw);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ALOAD, vRaw);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitLdcInsn(Type.getType("L" + reqTypeInternal + ";"));
        mv.visitMethodInsn(INVOKEINTERFACE, SERIALIZER_NAME, "decode",
                "([BIILjava/lang/Class;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, reqTypeInternal);
        mv.visitVarInsn(ASTORE, vDto);
        // Object ret = bean.<method>(dto);
        mv.visitFieldInsn(GETSTATIC, handlerName, "bean", serviceIfDesc);
        mv.visitVarInsn(ALOAD, vDto);
        mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(), methodDesc, true);
        mv.visitVarInsn(ASTORE, vRet);
        // resp.setResult(ret);
        // ★ 交<b>对象</b>而不是编码好的字节：编码推迟到出站写帧那一刻
        //   ⇒ 载荷在网络上只被拷一次（与 gRPC 的帧头内联同一形态）。
        mv.visitVarInsn(ALOAD, vResp);
        mv.visitVarInsn(ALOAD, vRet);
        mv.visitMethodInsn(INVOKEVIRTUAL, RESPONSE_NAME, "setResult", "(Ljava/lang/Object;)V", false);

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
