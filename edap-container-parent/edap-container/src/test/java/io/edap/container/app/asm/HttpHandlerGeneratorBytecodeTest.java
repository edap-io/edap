package io.edap.container.app.asm;

import io.edap.container.mw.AnnoData;
import io.edap.http.HttpHandler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HttpHandlerGenerator 字节码断言：验证 {@code @RequireAuth} 路径生成的
 * handle() 通过 JVM 校验（stack frame 合法），且包含 set/clear 调用与 try-catch 记录。
 *
 * <p>用 ASM {@link CheckClassAdapter#verify} 做数据流分析——不需要真的 defineClass，
 * 因此不必造 AppContext / BeanContainer 全套依赖。</p>
 */
class HttpHandlerGeneratorBytecodeTest {

    // ---- 测试用 proto 接口 & DTO ------------------------------------------

    public static class DemoRequest {
        private String userId;
        private int page;
        public String getUserId()            { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public int getPage()                 { return page; }
        public void setPage(int page)        { this.page = page; }
    }

    public static class DemoResponse {
        private String msg;
        public String getMsg()         { return msg; }
        public void setMsg(String msg) { this.msg = msg; }
    }

    public interface DemoService {
        DemoResponse update(DemoRequest req);
    }

    // ---- helpers ----------------------------------------------------------

    private static List<AnnoData> annos(String httpMethod, boolean requireAuth) {
        List<AnnoData> list = new ArrayList<>();
        AnnoData http = new AnnoData("io.edap.protobuf.annotation.ProtoHttp");
        http.getValues().put("method", httpMethod);
        http.getValues().put("path", "/demo/update");
        list.add(http);
        if (requireAuth) {
            AnnoData auth = new AnnoData("io.edap.protobuf.annotation.RequireAuth");
            auth.getValues().put("resolver", "");
            list.add(auth);
        }
        return list;
    }

    private static Method demoMethod() throws NoSuchMethodException {
        return DemoService.class.getMethod("update", DemoRequest.class);
    }

    private static byte[] gen(String httpMethod, boolean requireAuth) throws Exception {
        String resolver = requireAuth
                ? HandlerAsmGenerator.requireAuthResolver(annos(httpMethod, true))
                : null;
        return new HttpHandlerGenerator(annos(httpMethod, requireAuth), DemoService.class,
                demoMethod(), HttpHandlerGeneratorBytecodeTest.class.getClassLoader(), resolver)
                .generate();
    }

    /** 跑 ASM 数据流校验；有错就把详细信息作为断言消息抛出。 */
    private static void assertVerifies(byte[] code) {
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(code),
                HttpHandlerGeneratorBytecodeTest.class.getClassLoader(), false, new PrintWriter(sw));
        String out = sw.toString();
        assertFalse(out.contains("AnalyzerException") || out.contains("java.lang.RuntimeException"),
                "字节码校验失败:\n" + out);
    }

    private static MethodNode handleMethod(byte[] code) {
        ClassNode cn = new ClassNode();
        new ClassReader(code).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if ("handle".equals(mn.name)) return mn;
        }
        throw new AssertionError("no handle() in generated class");
    }

    private static boolean hasField(byte[] code, String name) {
        ClassNode cn = new ClassNode();
        new ClassReader(code).accept(cn, 0);
        return cn.fields.stream().anyMatch(f -> f.name.equals(name));
    }

    // ---- tests ------------------------------------------------------------

    @Test
    void requireAuthResolverDefaultsToJwtUserResolver() {
        assertEquals("jwtUserResolver",
                HandlerAsmGenerator.requireAuthResolver(annos("POST", true)));
    }

    @Test
    void requireAuthResolverHonoursExplicitBeanName() {
        List<AnnoData> list = annos("POST", true);
        list.get(1).getValues().put("resolver", "myResolver");
        assertEquals("myResolver", HandlerAsmGenerator.requireAuthResolver(list));
    }

    @Test
    void noRequireAuthAnnoYieldsNullResolver() {
        assertNull(HandlerAsmGenerator.requireAuthResolver(annos("POST", false)));
    }

    @Test
    void postWithRequireAuthVerifies() throws Exception {
        assertVerifies(gen("POST", true));
    }

    @Test
    void getWithRequireAuthVerifies() throws Exception {
        assertVerifies(gen("GET", true));
    }

    @Test
    void postWithoutRequireAuthVerifies() throws Exception {
        assertVerifies(gen("POST", false));
    }

    @Test
    void getWithoutRequireAuthVerifies() throws Exception {
        assertVerifies(gen("GET", false));
    }

    @Test
    void requireAuthAddsUserResolverField() throws Exception {
        assertTrue(hasField(gen("POST", true), "userResolver"));
        assertFalse(hasField(gen("POST", false), "userResolver"));
    }

    @Test
    void requireAuthAddsHolderSetAndClear() throws Exception {
        String post = handleMethodText(gen("POST", true));
        assertTrue(post.contains("RequestContextHolder.set"), "缺少 Holder.set:\n" + post);
        assertTrue(post.contains("RequestContextHolder.clear"), "缺少 Holder.clear:\n" + post);

        String get = handleMethodText(gen("GET", true));
        assertTrue(get.contains("RequestContextHolder.set"), "缺少 Holder.set:\n" + get);
        assertTrue(get.contains("RequestContextHolder.clear"), "缺少 Holder.clear:\n" + get);
    }

    @Test
    void publicRouteHasNoHolderCalls() throws Exception {
        assertFalse(handleMethodText(gen("POST", false)).contains("RequestContextHolder"));
        assertFalse(handleMethodText(gen("GET", false)).contains("RequestContextHolder"));
    }

    @Test
    void requireAuthAddsExtraTryCatchBlock() throws Exception {
        // 公开路径只有外层 1 条；RequireAuth 额外加 1 条 inner（clear + athrow）
        assertEquals(1, handleMethod(gen("POST", false)).tryCatchBlocks.size());
        assertEquals(2, handleMethod(gen("POST", true)).tryCatchBlocks.size());
        assertEquals(1, handleMethod(gen("GET", false)).tryCatchBlocks.size());
        assertEquals(2, handleMethod(gen("GET", true)).tryCatchBlocks.size());
    }

    /** clear() 必须出现在 ATHROW 之前——否则线程复用会泄漏上一个请求的 ctx。 */
    @Test
    void clearPrecedesAthrowInCatchHandler() throws Exception {
        for (String httpMethod : new String[]{"POST", "GET"}) {
            MethodNode mn = handleMethod(gen(httpMethod, true));
            int lastClear = -1, athrow = -1;
            for (int i = 0; i < mn.instructions.size(); i++) {
                org.objectweb.asm.tree.AbstractInsnNode in = mn.instructions.get(i);
                if (in instanceof org.objectweb.asm.tree.MethodInsnNode
                        && "clear".equals(((org.objectweb.asm.tree.MethodInsnNode) in).name)
                        && ((org.objectweb.asm.tree.MethodInsnNode) in).owner
                                .endsWith("RequestContextHolder")) {
                    lastClear = i;
                }
                if (in.getOpcode() == org.objectweb.asm.Opcodes.ATHROW) {
                    athrow = i;
                }
            }
            assertTrue(lastClear >= 0 && athrow >= 0, httpMethod + ": 缺 clear 或 athrow");
            assertTrue(lastClear < athrow,
                    httpMethod + ": clear 必须在 athrow 之前, clear=" + lastClear + " athrow=" + athrow);
        }
    }

    private static String handleMethodText(byte[] code) {
        MethodNode mn = handleMethod(code);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mn.instructions.size(); i++) {
            org.objectweb.asm.tree.AbstractInsnNode in = mn.instructions.get(i);
            if (in instanceof org.objectweb.asm.tree.MethodInsnNode) {
                org.objectweb.asm.tree.MethodInsnNode m = (org.objectweb.asm.tree.MethodInsnNode) in;
                sb.append(m.owner.substring(m.owner.lastIndexOf('/') + 1))
                  .append('.').append(m.name).append('\n');
            } else if (in instanceof org.objectweb.asm.tree.FieldInsnNode) {
                org.objectweb.asm.tree.FieldInsnNode f = (org.objectweb.asm.tree.FieldInsnNode) in;
                sb.append(f.owner.substring(f.owner.lastIndexOf('/') + 1))
                  .append('#').append(f.name).append('\n');
            }
        }
        return sb.toString();
    }

    /** targetIf 派发：HttpHandler 走 HttpHandlerGenerator，字节码非空。 */
    @Test
    void handlerAsmGeneratorDispatchesHttp() throws Exception {
        byte[] code = HandlerAsmGenerator.INSTANCE.generateHandlerClass(
                HttpHandler.class, DemoService.class, demoMethod(), annos("POST", true),
                HttpHandlerGeneratorBytecodeTest.class.getClassLoader());
        assertTrue(code.length > 0);
        assertTrue(hasField(code, "userResolver"));
    }

    /**
     * 真 JVM 校验：defineClass + 强制初始化。ASM 的 CheckClassAdapter 只做数据流分析，
     * 不校验 StackMapTable；这里让 HotSpot 的 verifier 亲自过一遍。
     * {@code <clinit>} 成功即通过；VerifyError / ClassFormatError 直接 fail。
     */
    @Test
    void generatedClassPassesJvmVerifier() throws Exception {
        for (String httpMethod : new String[]{"POST", "GET"}) {
            for (boolean auth : new boolean[]{true, false}) {
                byte[] code = gen(httpMethod, auth);
                String binaryName = HandlerAsmGenerator.handlerName(
                        HttpHandler.class, DemoService.class, demoMethod());
                DefiningLoader dl = new DefiningLoader(
                        HttpHandlerGeneratorBytecodeTest.class.getClassLoader());
                try {
                    Class.forName(binaryName, true, dl.define(binaryName, code));
                } catch (VerifyError | ClassFormatError e) {
                    fail(httpMethod + "/auth=" + auth + " JVM 校验失败: " + e, e);
                }
            }
        }
    }

    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader(ClassLoader parent) { super(parent); }
        ClassLoader define(String name, byte[] code) {
            defineClass(name, code, 0, code.length);
            return this;
        }
    }

    /**
     * {@code @RequireAuth} 路径必须在 bean null-check 之外无条件查 UserResolver —— 否则
     * bean==null 时 userResolver 静默为 null,后续 bean 出现时读到陈旧静态值。
     *
     * <p>断言结构:
     * <pre>
     *   bean-null:  PUTSTATIC bean=null → GOTO lbFinish → PUTSTATIC userResolver
     *   bean-!null: PUTSTATIC bean=svc  → fall-through lbFinish → PUTSTATIC userResolver
     * </pre>
     * 关键:lbFinish(Label) 必须在两个 PUTSTATIC 之间 —— 证明两条路径汇聚后才查 resolver。</p>
     */
    @Test
    void userResolverLookupRunsRegardlessOfBeanNull() throws Exception {
        byte[] code = gen("POST", true);
        ClassNode cn = new ClassNode();
        new ClassReader(code).accept(cn, 0);

        MethodNode init = null;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name)) { init = mn; break; }
        }
        assertNotNull(init, "no <init>");

        int beanPut = -1, resolverPut = -1, labelAfterBean = -1;
        for (int i = 0; i < init.instructions.size(); i++) {
            org.objectweb.asm.tree.AbstractInsnNode in = init.instructions.get(i);
            if (in instanceof org.objectweb.asm.tree.FieldInsnNode) {
                org.objectweb.asm.tree.FieldInsnNode f = (org.objectweb.asm.tree.FieldInsnNode) in;
                if (f.owner.equals(handlerNameBin()) && "bean".equals(f.name)
                        && f.getOpcode() == org.objectweb.asm.Opcodes.PUTSTATIC) {
                    beanPut = i;          // 取最后一个（bean-!null 路径的 PUTSTATIC）
                }
                if (f.owner.equals(handlerNameBin()) && "userResolver".equals(f.name)
                        && f.getOpcode() == org.objectweb.asm.Opcodes.PUTSTATIC) {
                    resolverPut = i;
                }
            }
        }
        // 找最后一个 PUTSTATIC bean 之后的第一个 Label —— 汇聚点 lbFinish
        for (int i = beanPut + 1; i < init.instructions.size() && labelAfterBean < 0; i++) {
            if (init.instructions.get(i) instanceof org.objectweb.asm.tree.LabelNode) {
                labelAfterBean = i;
            }
        }
        assertTrue(beanPut >= 0, "<init> 缺 PUTSTATIC bean");
        assertTrue(resolverPut >= 0, "<init> 缺 PUTSTATIC userResolver");
        assertTrue(labelAfterBean > beanPut && labelAfterBean < resolverPut,
                "汇聚 Label 必须在 PUTSTATIC bean 之后、PUTSTATIC userResolver 之前;"
                        + " 否则 bean-null 路径 GOTO 会跳过 resolver 查找. beanPut=" + beanPut
                        + " labelAfterBean=" + labelAfterBean + " resolverPut=" + resolverPut);
    }

    private static String handlerNameBin() {
        try {
            return HandlerAsmGenerator.handlerName(HttpHandler.class, DemoService.class, demoMethod())
                    .replace('.', '/');
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * 业务异常日志的 INVOKEDYNAMIC 必须把 Throwable 作为捕获参数传给 LambdaMetafactory,
     * 否则 factory 在 0-capture 模式下找 lambda$handle$0(SAM) 失败,运行时
     * {@code NoSuchMethodError: lambda$handle$0(LogArgs)}。
     *
     * <p>断言:RequireAuth 与公开两条路径的 INVOKEDYNAMIC descriptor 都是
     * {@code (Throwable)Consumer},Handle 指向的 impl 方法签名是
     * {@code (Throwable, LogArgs)V}。两条必须保持一致。</p>
     */
    @Test
    void bizLogLambdaCaptureMatchesImplSignature() throws Exception {
        for (boolean auth : new boolean[]{true, false}) {
            for (String m : new String[]{"POST", "GET"}) {
                checkBizLambda(auth ? "RequireAuth " + m : "Public " + m, gen(m, auth));
            }
        }
    }

    private static void checkBizLambda(String label, byte[] code) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(code).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (!"handle".equals(mn.name)) continue;

            // auth=true 时 handle() 里有两条 accept invokedynamic:
            //   1) userResolver 日志 → lambda$handle$1 (ResolverResult, LogArgs)V
            //   2) biz 异常日志   → lambda$handle$0 (Throwable,        LogArgs)V
            // 本断言只关心 biz log 那条。按 bsmArgs 中的 lambda$handle$0 Handle 过滤。
            org.objectweb.asm.tree.InvokeDynamicInsnNode idy = null;
            for (int i = 0; i < mn.instructions.size(); i++) {
                org.objectweb.asm.tree.AbstractInsnNode in = mn.instructions.get(i);
                if (!(in instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode)) continue;
                org.objectweb.asm.tree.InvokeDynamicInsnNode cur =
                        (org.objectweb.asm.tree.InvokeDynamicInsnNode) in;
                if (!"accept".equals(cur.name)) continue;
                if (targetsBizLogLambda(cur)) { idy = cur; break; }
            }
            if (idy == null) continue;

            String desc = idy.desc;
            assertEquals("(Ljava/lang/Throwable;)Ljava/util/function/Consumer;", desc,
                    label + ": INVOKEDYNAMIC descriptor 必须捕获 Throwable,"
                            + " 否则 LambdaMetafactory 找不到 lambda$handle$0(LogArgs) —— NoSuchMethodError");

            for (Object bsmArg : idy.bsmArgs) {
                if (bsmArg instanceof org.objectweb.asm.Handle) {
                    org.objectweb.asm.Handle h = (org.objectweb.asm.Handle) bsmArg;
                    if (h.getOwner().equals(handlerNameBin()) && h.getName().equals("lambda$handle$0")) {
                        assertEquals("(Ljava/lang/Throwable;Lio/edap/log/LogArgs;)V", h.getDesc(),
                                label + ": Handle 指向的 lambda$handle$0 签名必须是 (Throwable, LogArgs)V,"
                                        + " 匹配 visitBizLogLambda 输出的方法签名");
                    }
                }
            }
        }
    }

    private static boolean targetsBizLogLambda(org.objectweb.asm.tree.InvokeDynamicInsnNode idy) {
        for (Object bsmArg : idy.bsmArgs) {
            if (bsmArg instanceof org.objectweb.asm.Handle) {
                org.objectweb.asm.Handle h = (org.objectweb.asm.Handle) bsmArg;
                if (h.getOwner().equals(handlerNameBin()) && h.getName().equals("lambda$handle$0")) {
                    return true;
                }
            }
        }
        return false;
    }
}
