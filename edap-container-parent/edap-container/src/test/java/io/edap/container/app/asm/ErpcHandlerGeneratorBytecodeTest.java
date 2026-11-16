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
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.frame.ErpcRequest;
import io.edap.erpc.frame.ErpcResponse;
import io.edap.protobuf.annotation.ProtoField;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ErpcHandlerGenerator} 的字节码与行为测试。
 *
 * <p>验的是「容器生成的 eRPC handler」这件事本身：</p>
 * <ol>
 *   <li><b>结构验证</b> —— ASM {@link CheckClassAdapter#verify} 数据流分析 + HotSpot verifier
 *       亲自过一遍（defineClass + getDeclaredConstructors）；</li>
 *   <li><b>行为验证</b> —— 把生成的类加载起来、塞一个假 bean、喂一个真的
 *       {@link ErpcRequest}（带序列化器 + 载荷字节），断言「解码请求 → 调业务 →
 *       编码响应」这条链走对了。</li>
 * </ol>
 */
class ErpcHandlerGeneratorBytecodeTest {

    // ---- 测试用 proto 接口 & DTO ------------------------------------------
    //
    // ★ DTO 的<b>简单名必须带 Erpc 前缀</b>：protobuf 的解码器类名是
    //   「包名 + 简单名 + Decoder」（见 ProtoBufDecoderGenerator#getDecoderName），
    //   同包下两个同名 DTO 会生成<b>同一个</b>解码器类 ⇒ 解码出别的 DTO 的实例。
    //   同包的 GrpcHandlerGeneratorBytecodeTest 已经占了 DemoRequest 这个名字。

    public static class ErpcDemoRequest {
        @ProtoField(tag = 1, type = io.edap.protobuf.wire.Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = io.edap.protobuf.wire.Field.Type.INT32)
        public int value;
    }

    public static class ErpcDemoResponse {
        @ProtoField(tag = 1, type = io.edap.protobuf.wire.Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = io.edap.protobuf.wire.Field.Type.INT32)
        public int value;
    }

    /**
     * 一元服务接口 —— 与 proto 代码生成器产出的 Java 签名一致。
     */
    public interface ErpcDemoService {
        ErpcDemoResponse update(ErpcDemoRequest req);
    }

    public static class ErpcDemoServiceImpl implements ErpcDemoService {

        ErpcDemoRequest lastRequest;

        @Override
        public ErpcDemoResponse update(ErpcDemoRequest req) {
            this.lastRequest = req;
            ErpcDemoResponse resp = new ErpcDemoResponse();
            resp.name = req == null ? null : req.name;
            resp.value = req == null ? -1 : req.value + 1;
            return resp;
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return ErpcDemoService.class.getMethod(name, params);
    }

    private static byte[] gen() throws Exception {
        return new ErpcHandlerGenerator(ErpcDemoService.class, method("update", ErpcDemoRequest.class)).generate();
    }

    /** 跑 ASM 数据流校验；有错就把详细信息作为断言消息抛出。 */
    private static void assertVerifies(byte[] code) {
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(code),
                ErpcHandlerGeneratorBytecodeTest.class.getClassLoader(), false, new PrintWriter(sw));
        String out = sw.toString();
        assertFalse(out.contains("AnalyzerException") || out.contains("java.lang.RuntimeException"),
                "字节码校验失败:\n" + out);
    }

    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] code) {
            return defineClass(name, code, 0, code.length);
        }
    }

    /**
     * 生成 → defineClass → 实例化（AppContext 传 null，bean 后面反射塞进去）。
     */
    private static ErpcHandler instantiate(Object bean) throws Exception {
        byte[] code = gen();
        String binaryName = HandlerAsmGenerator.handlerName(ErpcHandler.class, ErpcDemoService.class,
                method("update", ErpcDemoRequest.class));
        DefiningLoader dl = new DefiningLoader(ErpcHandlerGeneratorBytecodeTest.class.getClassLoader());
        Class<?> cls = dl.define(binaryName, code);
        ErpcHandler handler = (ErpcHandler) cls.getConstructor(AppContext.class).newInstance((AppContext) null);
        java.lang.reflect.Field beanField = cls.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(null, bean);
        return handler;
    }

    /**
     * 编码并把「借来的」载荷拷出来 —— 借来的缓冲下一次编码就会被覆盖。
     */
    private static byte[] bytesOf(ErpcCodec codec, Object obj) {
        ErpcWriter writer = codec.encode(obj);
        return writer == null ? new byte[0] : Arrays.copyOf(writer.buffer(), writer.size());
    }

    private static ErpcCodec serializer() {
        return ErpcCodecRegistry.instance().defaultCodec();
    }

    // ---- tests ------------------------------------------------------------

    @Test
    void generatedClassVerifies() throws Exception {
        assertVerifies(gen());
    }

    @Test
    void generatedClassPassesJvmVerifier() throws Exception {
        byte[] code = gen();
        String binaryName = HandlerAsmGenerator.handlerName(ErpcHandler.class, ErpcDemoService.class,
                method("update", ErpcDemoRequest.class));
        DefiningLoader dl = new DefiningLoader(ErpcHandlerGeneratorBytecodeTest.class.getClassLoader());
        try {
            Class<?> defined = dl.define(binaryName, code);
            defined.getDeclaredConstructors();
        } catch (VerifyError | ClassFormatError e) {
            fail("JVM 校验失败: " + e, e);
        }
    }

    @Test
    void handlerAsmGeneratorDispatchesErpc() throws Exception {
        byte[] code = HandlerAsmGenerator.INSTANCE.generateHandlerClass(
                ErpcHandler.class, ErpcDemoService.class, method("update", ErpcDemoRequest.class),
                new ArrayList<>(), ErpcHandlerGeneratorBytecodeTest.class.getClassLoader());
        assertTrue(code.length > 0);
        ClassNode cn = new ClassNode();
        new ClassReader(code).accept(cn, 0);
        assertTrue(cn.interfaces.contains("io/edap/erpc/ErpcHandler"),
                "生成的类必须实现 ErpcHandler，实际: " + cn.interfaces);
    }

    @Test
    void handleMethodSignatureIsRequestResponse() throws Exception {
        byte[] code = gen();
        ClassNode cn = new ClassNode();
        new ClassReader(code).accept(cn, 0);
        MethodNode handle = null;
        for (MethodNode mn : cn.methods) {
            if ("handle".equals(mn.name)) {
                handle = mn;
                break;
            }
        }
        assertNotNull(handle);
        assertEquals("(Lio/edap/erpc/frame/ErpcRequest;Lio/edap/erpc/frame/ErpcResponse;)V", handle.desc);
        assertEquals(0, handle.tryCatchBlocks.size(),
                "生成的 handle 不该有 try-catch —— 异常直接冒泡给框架转成 ERROR 帧");
    }

    /**
     * ★ 核心行为：解码请求 → 调业务 → 编码响应。
     */
    @Test
    void handleDecodesCallsBusinessAndEncodesResponse() throws Exception {
        ErpcDemoServiceImpl bean = new ErpcDemoServiceImpl();
        ErpcHandler handler = instantiate(bean);

        ErpcCodec ser = serializer();
        ErpcDemoRequest in = new ErpcDemoRequest();
        in.name = "edap";
        in.value = 7;

        ErpcRequest req = new ErpcRequest();
        req.setSerializer(ser);
        req.setPayload(bytesOf(ser, in));
        ErpcResponse resp = new ErpcResponse();

        handler.handle(req, resp);

        assertNotNull(bean.lastRequest, "业务方法必须被调用");
        assertEquals("edap", bean.lastRequest.name);
        assertEquals(7, bean.lastRequest.value);

        assertNotNull(resp.getResult(), "响应对象必须被交回 resp");
        ErpcDemoResponse back = (ErpcDemoResponse) resp.getResult();
        assertEquals("edap", back.name);
        assertEquals(8, back.value);
    }

    /**
     * 请求载荷为 null —— 按空消息处理，不能挂死。
     */
    @Test
    void nullPayloadIsTreatedAsEmptyMessage() throws Exception {
        ErpcDemoServiceImpl bean = new ErpcDemoServiceImpl();
        ErpcHandler handler = instantiate(bean);

        ErpcRequest req = new ErpcRequest();
        req.setSerializer(serializer());
        req.setPayload(null);
        ErpcResponse resp = new ErpcResponse();

        handler.handle(req, resp);

        assertNotNull(bean.lastRequest, "载荷为 null 也要按空消息调业务");
        assertNotNull(resp.getResult());
    }

    /**
     * ★ 序列化器来自帧 —— 换 json 也要能解（同一份 handler 支持多种 codec）。
     */
    @Test
    void serializerComesFromTheFrameSoJsonWorksToo() throws Exception {
        ErpcDemoServiceImpl bean = new ErpcDemoServiceImpl();
        ErpcHandler handler = instantiate(bean);

        ErpcCodec json = ErpcCodecRegistry.instance().byName("json");
        assertNotNull(json);
        ErpcDemoRequest in = new ErpcDemoRequest();
        in.name = "js";
        in.value = 3;

        ErpcRequest req = new ErpcRequest();
        req.setSerializer(json);
        req.setPayload(bytesOf(json, in));
        ErpcResponse resp = new ErpcResponse();

        handler.handle(req, resp);

        assertEquals("js", bean.lastRequest.name);
        ErpcDemoResponse back = (ErpcDemoResponse) resp.getResult();
        assertEquals("js", back.name);
        assertEquals(4, back.value);
    }

    /**
     * 没有 bean —— 抛「接口还没有实现」（框架转成 ERROR 帧，不挂死）。
     */
    @Test
    void missingBeanThrowsNotImplemented() throws Exception {
        ErpcHandler handler = instantiate(null);

        ErpcRequest req = new ErpcRequest();
        req.setSerializer(serializer());
        req.setPayload(new byte[0]);
        ErpcResponse resp = new ErpcResponse();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> handler.handle(req, resp));
        assertTrue(e.getMessage().contains("接口还没有实现"), "异常信息: " + e.getMessage());
    }

    /**
     * 非一元方法（多参）—— 生成器直接拒绝，不产出字节码。
     */
    @Test
    void multiParamMethodIsRejected() throws Exception {
        Method two = MultiParamService.class.getMethod("two", ErpcDemoRequest.class, ErpcDemoRequest.class);
        assertThrows(IllegalArgumentException.class, () -> new ErpcHandlerGenerator(MultiParamService.class, two));
    }

    public interface MultiParamService {
        ErpcDemoResponse two(ErpcDemoRequest a, ErpcDemoRequest b);
    }
}
