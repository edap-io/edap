package io.edap.container.app.asm;

import io.edap.container.AppContext;
import io.edap.container.mw.AnnoData;
import io.edap.grpc.GrpcCall;
import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcHandler;
import io.edap.grpc.GrpcMetadata;
import io.edap.grpc.GrpcStatus;
import io.edap.protobuf.annotation.ProtoField;
import io.edap.rpc.StreamObserver;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link GrpcHandlerGenerator} 的字节码与行为测试。
 *
 * <p>两种验证：</p>
 * <ol>
 *   <li><b>结构验证</b> —— ASM {@link CheckClassAdapter#verify} 数据流分析 + HotSpot verifier
 *       亲自过一遍（defineClass + getDeclaredConstructors）；</li>
 *   <li><b>行为验证</b> —— 真的把生成的类加载起来、塞一个假 bean、喂一个假 {@link GrpcCall}，
 *       断言四种调用形态各自走对了骨架（一元解���请求并回消息、客户端流式交回请求观察者……）。</li>
 * </ol>
 */
class GrpcHandlerGeneratorBytecodeTest {

    // ---- 测试用 proto 接口 & DTO ------------------------------------------

    public static class DemoRequest {
        @ProtoField(tag = 1, type = io.edap.protobuf.wire.Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = io.edap.protobuf.wire.Field.Type.INT32)
        public int value;
    }

    public static class DemoResponse {
        @ProtoField(tag = 1, type = io.edap.protobuf.wire.Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = io.edap.protobuf.wire.Field.Type.INT32)
        public int value;
    }

    /**
     * 四种调用形态各一个方法 —— 签名与 proto 代码生成器产出的 Java 签名一致。
     */
    public interface DemoService {
        DemoResponse update(DemoRequest req);

        void watch(DemoRequest req, StreamObserver<DemoResponse> responseObserver);

        StreamObserver<DemoRequest> collect(StreamObserver<DemoResponse> responseObserver);

        StreamObserver<DemoRequest> chat(StreamObserver<DemoResponse> responseObserver);
    }

    public static class DemoServiceImpl implements DemoService {

        DemoRequest lastRequest;
        StreamObserver<DemoResponse> lastResponder;

        @Override
        public DemoResponse update(DemoRequest req) {
            this.lastRequest = req;
            DemoResponse resp = new DemoResponse();
            resp.name = req == null ? null : req.name;
            resp.value = req == null ? -1 : req.value;
            return resp;
        }

        @Override
        public void watch(DemoRequest req, StreamObserver<DemoResponse> responseObserver) {
            this.lastRequest = req;
            this.lastResponder = responseObserver;
        }

        @Override
        public StreamObserver<DemoRequest> collect(StreamObserver<DemoResponse> responseObserver) {
            this.lastResponder = responseObserver;
            return new StreamObserver<DemoRequest>() {
                @Override
                public void onNext(DemoRequest value) {
                    DemoResponse resp = new DemoResponse();
                    resp.name = value == null ? null : value.name;
                    responseObserver.onNext(resp);
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                }
            };
        }

        @Override
        public StreamObserver<DemoRequest> chat(StreamObserver<DemoResponse> responseObserver) {
            return collect(responseObserver);
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static List<AnnoData> annos(String type, String path) {
        List<AnnoData> list = new ArrayList<>();
        AnnoData grpc = new AnnoData("io.edap.protobuf.annotation.ProtoGrpc");
        grpc.getValues().put("type", type);
        grpc.getValues().put("req", DemoRequest.class.getName());
        grpc.getValues().put("resp", DemoResponse.class.getName());
        grpc.getValues().put("path", path);
        list.add(grpc);
        return list;
    }

    private static byte[] gen(String type, String path, Method method) throws Exception {
        return new GrpcHandlerGenerator(annos(type, path), DemoService.class, method,
                GrpcHandlerGeneratorBytecodeTest.class.getClassLoader()).generate();
    }

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return DemoService.class.getMethod(name, params);
    }

    /** 跑 ASM 数据流校验；有错就把详细信息作为断言消息抛出。 */
    private static void assertVerifies(byte[] code) {
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(code),
                GrpcHandlerGeneratorBytecodeTest.class.getClassLoader(), false, new PrintWriter(sw));
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
    private static GrpcHandler instantiate(String type, String path, Method method, Object bean) throws Exception {
        byte[] code = gen(type, path, method);
        String binaryName = HandlerAsmGenerator.handlerName(GrpcHandler.class, DemoService.class, method);
        DefiningLoader dl = new DefiningLoader(GrpcHandlerGeneratorBytecodeTest.class.getClassLoader());
        Class<?> cls = dl.define(binaryName, code);
        GrpcHandler handler = (GrpcHandler) cls.getConstructor(AppContext.class).newInstance((AppContext) null);
        java.lang.reflect.Field beanField = cls.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(null, bean);
        return handler;
    }

    /**
     * 假的 {@link GrpcCall} —— 记录发出去的消息与结束状态。
     */
    private static final class FakeCall implements GrpcCall {
        private final byte[] requestBytes;
        final List<Object> sent = new ArrayList<>();
        final List<StreamObserver<Object>> requestObservers = new ArrayList<>();
        GrpcStatus closedStatus;
        String closedMessage;
        boolean closed;

        FakeCall(byte[] requestBytes) {
            this.requestBytes = requestBytes;
        }

        @Override
        public String getMethod() {
            return "/demo.Svc/Method";
        }

        @Override
        public GrpcMetadata metadata() {
            return new GrpcMetadata();
        }

        @Override
        public GrpcMetadata responseMetadata() {
            return new GrpcMetadata();
        }

        @Override
        public boolean isClientStreaming() {
            return false;
        }

        @Override
        public boolean isServerStreaming() {
            return false;
        }

        @Override
        public byte[] requestBytes() {
            return requestBytes;
        }

        @Override
        public void sendOne(Object message) {
            sent.add(message);
        }

        @Override
        public StreamObserver<Object> responseObserver() {
            return new StreamObserver<Object>() {
                @Override
                public void onNext(Object value) {
                    sent.add(value);
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                }
            };
        }

        @Override
        public void setRequestObserver(StreamObserver<Object> requestObserver) {
            requestObservers.add(requestObserver);
        }

        @Override
        public void close(GrpcStatus status, String message) {
            this.closed = true;
            this.closedStatus = status;
            this.closedMessage = message;
        }
    }

    // ---- tests ------------------------------------------------------------

    @Test
    void allFourShapesVerify() throws Exception {
        assertVerifies(gen("UNARY", "/demo.Svc/Update", method("update", DemoRequest.class)));
        assertVerifies(gen("SERVER_STREAM", "/demo.Svc/Watch",
                method("watch", DemoRequest.class, StreamObserver.class)));
        assertVerifies(gen("CLIENT_STREAM", "/demo.Svc/Collect",
                method("collect", StreamObserver.class)));
        assertVerifies(gen("BIDIRECTIONAL", "/demo.Svc/Chat",
                method("chat", StreamObserver.class)));
    }

    @Test
    void allFourShapesPassJvmVerifier() throws Exception {
        Object[][] cases = {
                {"UNARY", "/demo.Svc/Update", method("update", DemoRequest.class)},
                {"SERVER_STREAM", "/demo.Svc/Watch", method("watch", DemoRequest.class, StreamObserver.class)},
                {"CLIENT_STREAM", "/demo.Svc/Collect", method("collect", StreamObserver.class)},
                {"BIDIRECTIONAL", "/demo.Svc/Chat", method("chat", StreamObserver.class)},
        };
        for (Object[] c : cases) {
            byte[] code = gen((String) c[0], (String) c[1], (Method) c[2]);
            String binaryName = HandlerAsmGenerator.handlerName(GrpcHandler.class, DemoService.class, (Method) c[2]);
            DefiningLoader dl = new DefiningLoader(GrpcHandlerGeneratorBytecodeTest.class.getClassLoader());
            try {
                Class<?> defined = dl.define(binaryName, code);
                defined.getDeclaredConstructors();
            } catch (VerifyError | ClassFormatError e) {
                fail(c[0] + " JVM 校验失败: " + e, e);
            }
        }
    }

    @Test
    void handlerAsmGeneratorDispatchesGrpc() throws Exception {
        byte[] code = HandlerAsmGenerator.INSTANCE.generateHandlerClass(
                GrpcHandler.class, DemoService.class, method("update", DemoRequest.class),
                annos("UNARY", "/demo.Svc/Update"),
                GrpcHandlerGeneratorBytecodeTest.class.getClassLoader());
        assertTrue(code.length > 0);
        ClassNode cn = new ClassNode();
        new ClassReader(code).accept(cn, 0);
        assertTrue(cn.interfaces.contains("io/edap/grpc/GrpcHandler"),
                "生成的类必须实现 GrpcHandler，实际: " + cn.interfaces);
    }

    @Test
    void missingProtoGrpcAnnoIsRejected() {
        List<AnnoData> empty = new ArrayList<>();
        assertThrowsIllegalArgument(() -> new GrpcHandlerGenerator(empty, DemoService.class,
                methodUnchecked("update", DemoRequest.class),
                GrpcHandlerGeneratorBytecodeTest.class.getClassLoader()));
    }

    @Test
    void unaryDecodesRequestSendsResponseAndClosesOk() throws Exception {
        DemoServiceImpl bean = new DemoServiceImpl();
        GrpcHandler handler = instantiate("UNARY", "/demo.Svc/Update",
                method("update", DemoRequest.class), bean);

        DemoRequest req = new DemoRequest();
        req.name = "edap";
        req.value = 7;
        FakeCall call = new FakeCall(GrpcCodec.encode(DemoRequest.class, req));

        handler.handle(call);

        assertNotNull(bean.lastRequest, "业务方法必须被调用");
        assertEquals("edap", bean.lastRequest.name);
        assertEquals(7, bean.lastRequest.value);
        assertEquals(1, call.sent.size(), "必须回一条响应消息");
        DemoResponse resp = (DemoResponse) call.sent.get(0);
        assertEquals("edap", resp.name);
        assertEquals(7, resp.value);
        assertTrue(call.closed, "必须结束调用");
        assertSame(GrpcStatus.OK, call.closedStatus);
    }

    @Test
    void unaryWithEmptyRequestStillCallsHandler() throws Exception {
        DemoServiceImpl bean = new DemoServiceImpl();
        GrpcHandler handler = instantiate("UNARY", "/demo.Svc/Update",
                method("update", DemoRequest.class), bean);

        FakeCall call = new FakeCall(null);
        handler.handle(call);

        assertNotNull(bean.lastRequest, "请求字节为 null 也要按空消息调用业务");
        assertTrue(call.closed);
        assertSame(GrpcStatus.OK, call.closedStatus);
    }

    @Test
    void serverStreamPassesResponseObserverAndDoesNotClose() throws Exception {
        DemoServiceImpl bean = new DemoServiceImpl();
        GrpcHandler handler = instantiate("SERVER_STREAM", "/demo.Svc/Watch",
                method("watch", DemoRequest.class, StreamObserver.class), bean);

        DemoRequest req = new DemoRequest();
        req.name = "stream";
        FakeCall call = new FakeCall(GrpcCodec.encode(DemoRequest.class, req));

        handler.handle(call);

        assertNotNull(bean.lastRequest);
        assertEquals("stream", bean.lastRequest.name);
        assertNotNull(bean.lastResponder, "服务端流式必须把响应观察者交给业务");
        assertFalse(call.closed, "服务端流式的收尾由业务负责，框架不能提前结束");
        assertEquals(0, call.sent.size());
    }

    @Test
    void clientStreamHandsRequestObserverBack() throws Exception {
        DemoServiceImpl bean = new DemoServiceImpl();
        GrpcHandler handler = instantiate("CLIENT_STREAM", "/demo.Svc/Collect",
                method("collect", StreamObserver.class), bean);

        FakeCall call = new FakeCall(null);
        handler.handle(call);

        assertNotNull(bean.lastResponder, "客户端流式必须把响应观察者交给业务");
        assertEquals(1, call.requestObservers.size(), "业务返回的请求观察者必须交回框架");
        assertFalse(call.closed, "客户端流式的收尾由业务负责");
    }

    @Test
    void bidirectionalHandsRequestObserverBack() throws Exception {
        DemoServiceImpl bean = new DemoServiceImpl();
        GrpcHandler handler = instantiate("BIDIRECTIONAL", "/demo.Svc/Chat",
                method("chat", StreamObserver.class), bean);

        FakeCall call = new FakeCall(null);
        handler.handle(call);

        assertEquals(1, call.requestObservers.size());
    }

    @Test
    void missingBeanClosesWithUnimplemented() throws Exception {
        GrpcHandler handler = instantiate("UNARY", "/demo.Svc/Update",
                method("update", DemoRequest.class), null);

        FakeCall call = new FakeCall(new byte[0]);
        handler.handle(call);

        assertTrue(call.closed, "没有 bean 时必须结束调用，不能挂死");
        assertSame(GrpcStatus.UNIMPLEMENTED, call.closedStatus);
        assertEquals(0, call.sent.size());
    }

    @Test
    void handleMethodDeclaresGrpcCallParameter() throws Exception {
        byte[] code = gen("UNARY", "/demo.Svc/Update", method("update", DemoRequest.class));
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
        assertEquals("(Lio/edap/grpc/GrpcCall;)V", handle.desc);
        assertEquals(0, handle.tryCatchBlocks.size(), "生成的 handle 不该有 try-catch —— 异常直接冒泡给框架");
    }

    @Test
    void unknownMethodTypeIsRejected() {
        assertThrowsIllegalArgument(() -> new GrpcHandlerGenerator(annos("NOT_A_TYPE", "/demo.Svc/X"),
                DemoService.class, methodUnchecked("update", DemoRequest.class),
                GrpcHandlerGeneratorBytecodeTest.class.getClassLoader()));
    }

    // ---- 小工具 -----------------------------------------------------------

    private static Method methodUnchecked(String name, Class<?>... params) {
        try {
            return method(name, params);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertThrowsIllegalArgument(Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            return;
        }
        fail("应该抛 IllegalArgumentException");
    }
}
