package io.edap.container.app.asm;

import io.edap.container.mw.AnnoData;
import io.edap.http.HttpHandler;
import io.edap.protobuf.annotation.KeyValuePair;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpHandlerParamParseGenerator} 的字节码断言。
 *
 * <p><b>为什么单独一个类</b>：{@link HttpHandlerGeneratorBytecodeTest} 覆盖的是
 * {@link HttpHandlerGenerator}（无 {@code @ParamParseConfig} 的老路径），
 * 而 {@link HttpHandlerParamParseGenerator} 是独立的一份实现——它的
 * {@code visitInvokeBean} 在 POST 分支重写了参数装配（按 config 从 headers/body 取值），
 * 且 {@code @RequireAuth} 分支多套了两层 try-catch。
 * 两者的控制流形状完全不同，不能互相代替验证。</p>
 *
 * <p><b>覆盖缺口的历史</b>：本类落地前，{@code HttpHandlerParamParseGenerator}
 * 的 {@code @RequireAuth} 分支（handle() 里 4 条 try-catch）<b>没有任何测试碰过</b>——
 * 唯一引用它的 {@link GeneratedClassVerifierTest} 只用 POST + 无 auth 的注解组合，
 * 走的是公开分支（else 分支）。也就是说护栏当时只在一条路径上被验证过。</p>
 */
class HttpHandlerParamParseGeneratorBytecodeTest {

    // ---- 测试用 proto 接口 & DTO ------------------------------------------

    public static class PayDto {
        private Map<String, String> headers;
        private String body;
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
    }

    public static class PayResp {
        private String message;
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public interface PayService {
        PayResp notify(PayDto dto);
    }

    // ---- helpers ----------------------------------------------------------

    private static Map<String, Object> pair(String key, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("@type", KeyValuePair.class.getName());
        m.put("key", key);
        m.put("value", value);
        return m;
    }

    /**
     * 组装注解：{@code @ProtoHttp} + {@code @ParamParseConfig}（可选 {@code @RequireAuth}）。
     *
     * <p>{@code @ParamParseConfig.configs} 的结构是 {@code List<KeyValuePair>}，
     * 在 AnnoData 里表现为「{@code @type} = KeyValuePair FQCN + key/value」的 Map 列表。</p>
     */
    private static List<AnnoData> annos(String httpMethod, boolean requireAuth) {
        List<AnnoData> list = new ArrayList<>();
        AnnoData http = new AnnoData("io.edap.protobuf.annotation.ProtoHttp");
        http.getValues().put("method", httpMethod);
        http.getValues().put("path", "/pay/notify");
        list.add(http);

        List<Map<String, Object>> configs = new ArrayList<>();
        configs.add(pair("headers", "headers"));
        configs.add(pair("body", "body"));
        AnnoData cfg = new AnnoData("io.edap.protobuf.annotation.ParamParseConfig");
        cfg.getValues().put("configs", configs);
        list.add(cfg);

        if (requireAuth) {
            AnnoData auth = new AnnoData("io.edap.protobuf.annotation.RequireAuth");
            auth.getValues().put("resolver", "");
            list.add(auth);
        }
        return list;
    }

    private static Method payMethod() throws NoSuchMethodException {
        return PayService.class.getMethod("notify", PayDto.class);
    }

    /** 走真实入口 {@link HandlerAsmGenerator}——含生成期护栏。 */
    private static byte[] gen(String httpMethod, boolean requireAuth) throws Exception {
        return HandlerAsmGenerator.INSTANCE.generateHandlerClass(
                HttpHandler.class, PayService.class, payMethod(), annos(httpMethod, requireAuth),
                HttpHandlerParamParseGeneratorBytecodeTest.class.getClassLoader());
    }

    private static MethodNode handleMethod(byte[] code) {
        ClassNode cn = new ClassNode();
        new ClassReader(code).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if ("handle".equals(mn.name)) return mn;
        }
        throw new AssertionError("生成的 class 里没有 handle()");
    }

    /**
     * 收集 handle() 里调用过的方法名（{@code 简单类名.方法名}），便于断言。
     *
     * <p>owner 取「包名之后、嵌套类前缀 {@code Outer$} 之后」的部分——
     * 测试 DTO 是嵌套类，其 internal name 形如
     * {@code io/edap/container/app/asm/XxxTest$PayDto}，直接取最后一段会得到
     * {@code XxxTest$PayDto}，断言里写 {@code PayDto.setHeaders} 就匹配不上。</p>
     */
    private static List<String> calledMethods(byte[] code) {
        List<String> out = new ArrayList<>();
        MethodNode mn = handleMethod(code);
        for (org.objectweb.asm.tree.AbstractInsnNode in : mn.instructions) {
            if (in instanceof org.objectweb.asm.tree.MethodInsnNode) {
                org.objectweb.asm.tree.MethodInsnNode m = (org.objectweb.asm.tree.MethodInsnNode) in;
                out.add(simpleName(m.owner) + "." + m.name);
            }
        }
        return out;
    }

    /** 取 internal name 的简单类名：去包名、去嵌套类前缀 {@code Outer$}。 */
    private static String simpleName(String internalName) {
        String s = internalName.substring(internalName.lastIndexOf('/') + 1);
        int dollar = s.lastIndexOf('$');
        return dollar < 0 ? s : s.substring(dollar + 1);
    }

    // ---- tests ------------------------------------------------------------

    /**
     * 四种组合（POST/GET × 有无 {@code @RequireAuth}）的字节码必须全部通过生成期护栏。
     *
     * <p>护栏 = {@link GeneratedClassVerifier} 的三道检查（JVM 链接期校验 / ASM 数据流分析 /
     * ASM 静态校验）。{@code @RequireAuth} 分支 handle() 里有 4 条 try-catch，
     * 是栈高最容易在汇合点失衡的形状——必须真的生成一遍才算覆盖到。</p>
     */
    @Test
    void allMethodAndAuthCombinationsPassGuardrail() throws Exception {
        for (String httpMethod : new String[]{"POST", "GET"}) {
            for (boolean auth : new boolean[]{false, true}) {
                byte[] code = gen(httpMethod, auth);
                assertTrue(code.length > 0, httpMethod + "/auth=" + auth + ": 没产出字节码");
                assertDoesNotThrow(() -> GeneratedClassVerifier.verify(
                                code,
                                HandlerAsmGenerator.handlerName(HttpHandler.class, PayService.class, payMethod()),
                                HttpHandlerParamParseGeneratorBytecodeTest.class.getClassLoader()),
                        httpMethod + "/auth=" + auth + ": 生成期护栏未通过");
            }
        }
    }

    /** {@code @RequireAuth} 分支必须有 UserResolver 查找 + RequestContextHolder 生命周期。 */
    @Test
    void requireAuthBranchWiresUserResolverAndContextHolder() throws Exception {
        List<String> calls = calledMethods(gen("POST", true));
        assertTrue(calls.contains("UserResolver.resolve"), "缺 UserResolver.resolve:\n" + calls);
        assertTrue(calls.contains("RequestContextHolder.set"), "缺 RequestContextHolder.set:\n" + calls);
        assertTrue(calls.contains("RequestContextHolder.clear"), "缺 RequestContextHolder.clear:\n" + calls);
    }

    /** 公开分支不碰 UserResolver / RequestContextHolder。 */
    @Test
    void publicBranchHasNoAuthWiring() throws Exception {
        List<String> calls = calledMethods(gen("POST", false));
        assertFalse(calls.contains("UserResolver.resolve"), "公开分支不应查 UserResolver:\n" + calls);
        assertFalse(calls.contains("RequestContextHolder.set"), "公开分支不应设 RequestContext:\n" + calls);
    }

    /**
     * POST 分支必须按 {@code @ParamParseConfig} 装配参数：
     * {@code headers} ← {@code HttpRequest.getHeaderMap()}，
     * {@code body} ← {@code HttpBody.toByteArray()}。
     */
    @Test
    void postBranchHonorsParamParseConfig() throws Exception {
        List<String> calls = calledMethods(gen("POST", false));
        assertTrue(calls.contains("HttpRequest.getHeaderMap"), "未从 headers 装配:\n" + calls);
        assertTrue(calls.contains("HttpBody.toByteArray"), "未从 body 装配:\n" + calls);
        assertTrue(calls.contains("PayDto.setHeaders"), "未调用 setHeaders:\n" + calls);
        assertTrue(calls.contains("PayDto.setBody"), "未调用 setBody:\n" + calls);
    }

    /**
     * GET 分支<b>不</b>走 {@code @ParamParseConfig}——它沿用老行为：把 DTO 的<b>每个</b>字段
     * 按字段名从 {@code HttpRequest.getParameter}（query/form）取值。
     *
     * <p>注意：字段名叫 {@code body} 的 DTO 属性在 GET 下<b>仍然</b>会被 set，
     * 但值来自 query 参数而不是 HTTP body。所以断言只能钉「不读 HttpBody、不读 headers」，
     * 不能断言「不调 setBody」。这是当前实现的既定行为，此处把它钉住，
     * 避免以后有人误以为 GET 也支持 config 装配而写出错误断言。</p>
     */
    @Test
    void getBranchUsesQueryParametersNotParamParseConfig() throws Exception {
        List<String> calls = calledMethods(gen("GET", false));
        assertTrue(calls.contains("HttpRequest.getParameter"), "GET 分支应按参数名取值:\n" + calls);
        assertFalse(calls.contains("HttpRequest.getHeaderMap"), "GET 分支不读 headers:\n" + calls);
        assertFalse(calls.contains("HttpBody.toByteArray"), "GET 分支不读 HTTP body:\n" + calls);
    }
}
