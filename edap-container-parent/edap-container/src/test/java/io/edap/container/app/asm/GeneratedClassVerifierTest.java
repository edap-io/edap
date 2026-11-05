package io.edap.container.app.asm;

import io.edap.container.mw.AnnoData;
import io.edap.http.HttpHandler;
import io.edap.protobuf.annotation.KeyValuePair;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GeneratedClassVerifier} 的护栏测试。
 *
 * <p>三组用例：
 * <ol>
 *   <li><b>ASM 造的「好类 / 坏类」</b>——坏类精确复刻生产事故的形状：汇合点两条入边栈高不一致
 *       （正常路径多压了一个没被消费的值，异常路径栈空）。ASM 的 {@code COMPUTE_FRAMES} 对此
 *       <b>不报错</b>，产出一个 JVM 拒绝的 class。</li>
 *   <li><b>副作用控制</b>——护栏触发的是链接期校验，不是初始化期；{@code <clinit>} 绝不能被执行。</li>
 *   <li><b>真实生成器回归</b>——{@link HttpHandlerParamParseGenerator} 真实产出的 Handler class
 *       必须三道护栏全过（防误报）。</li>
 * </ol>
 */
class GeneratedClassVerifierTest {

    // ---- ASM 造的好类 / 坏类 ------------------------------------------------

    /**
     * 造一个只有一条方法 {@code m()} 的 class。
     *
     * @param strayReference true = 正常路径多压一个没被消费的值（复刻生产事故的 dangling reference）
     */
    private static byte[] buildClass(String name, boolean strayReference) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m", "()V", null, null);
        mv.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Label join = new Label();
        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        mv.visitLabel(start);
        mv.visitInsn(Opcodes.NOP);          // 保护范围内至少一条指令（空 range 是 ClassFormatError）
        if (strayReference) {
            // ★ 多余的 ALOAD：这个引用没有被任何调用消费，留在操作数栈上直到 join 点
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }
        mv.visitLabel(end);
        mv.visitJumpInsn(Opcodes.GOTO, join);
        // 异常路径：栈高 0
        mv.visitLabel(handler);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitJumpInsn(Opcodes.GOTO, join);
        // 汇合点：正常路径栈高 1（坏类）/ 0（好类），异常路径栈高 0
        mv.visitLabel(join);
        if (strayReference) {
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void goodClassPassesAllGuardrails() {
        byte[] code = buildClass("Good", false);
        assertDoesNotThrow(() ->
                GeneratedClassVerifier.verify(code, "Good", GeneratedClassVerifierTest.class.getClassLoader()));
    }

    @Test
    void badClassIsRejectedByGuardrails() {
        byte[] code = buildClass("Bad", true);
        VerifyError e = assertThrows(VerifyError.class, () ->
                GeneratedClassVerifier.verify(code, "Bad", GeneratedClassVerifierTest.class.getClassLoader()));
        String msg = e.getMessage();
        // 三道护栏都应给出结论
        assertTrue(msg.contains("[1] JVM 链接期校验"), "缺少 JVM 校验结论:\n" + msg);
        assertTrue(msg.contains("[2] ASM 数据流分析"), "缺少数据流分析结论:\n" + msg);
        assertTrue(msg.contains("[3] ASM 静态校验"), "缺少静态校验结论:\n" + msg);
        // JVM 的诊断信息应指出具体分支目标
        assertTrue(msg.contains("branch target"), "缺少分支目标定位:\n" + msg);
    }

    @Test
    void emptyBytesAreSkipped() {
        assertDoesNotThrow(() ->
                GeneratedClassVerifier.verify(new byte[0], "Nothing", GeneratedClassVerifierTest.class.getClassLoader()));
        assertDoesNotThrow(() ->
                GeneratedClassVerifier.verify(null, "Nothing", GeneratedClassVerifierTest.class.getClassLoader()));
    }

    // ---- 副作用控制：护栏不能执行 <clinit> ---------------------------------

    /** {@code <clinit>} 的执行探针——静态字段被写就说明初始化被触发了。 */
    public static class ClinitWitness {
        public static volatile boolean ran = false;
    }

    @Test
    void guardrailDoesNotExecuteStaticInitializer() {
        ClinitWitness.ran = false;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "WithClinit", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "io/edap/container/app/asm/GeneratedClassVerifierTest$ClinitWitness",
                "ran", "Z");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, "io/edap/container/app/asm/GeneratedClassVerifierTest$ClinitWitness",
                "ran", "Z");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] code = cw.toByteArray();
        assertDoesNotThrow(() ->
                GeneratedClassVerifier.verify(code, "WithClinit", GeneratedClassVerifierTest.class.getClassLoader()));
        assertFalse(ClinitWitness.ran, "护栏执行了 <clinit> —— 生成类的静态初始化块有业务副作用，不能被触发");
    }

    // ---- 真实生成器回归（防误报）-------------------------------------------

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

    private static List<AnnoData> payAnnos() {
        List<AnnoData> list = new ArrayList<>();
        AnnoData http = new AnnoData("io.edap.protobuf.annotation.ProtoHttp");
        http.getValues().put("method", "POST");
        http.getValues().put("path", "/pay/notify");
        list.add(http);

        List<Map<String, Object>> configs = new ArrayList<>();
        configs.add(pair("headers", "headers"));
        configs.add(pair("body", "body"));
        AnnoData cfg = new AnnoData("io.edap.protobuf.annotation.ParamParseConfig");
        cfg.getValues().put("configs", configs);
        list.add(cfg);
        return list;
    }

    private static Map<String, Object> pair(String key, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("@type", KeyValuePair.class.getName());
        m.put("key", key);
        m.put("value", value);
        return m;
    }

    /**
     * 真实生成器（{@link HttpHandlerParamParseGenerator}）产出的 Handler class
     * 必须三道护栏全过——护栏的价值建立在「不误伤」之上。
     */
    @Test
    void realGeneratorOutputPassesGuardrail() throws Exception {
        Method m = PayService.class.getMethod("notify", PayDto.class);
        byte[] code = HandlerAsmGenerator.INSTANCE.generateHandlerClass(
                HttpHandler.class, PayService.class, m, payAnnos(),
                GeneratedClassVerifierTest.class.getClassLoader());
        assertTrue(code.length > 0, "生成器没产出字节码");
        assertDoesNotThrow(() -> GeneratedClassVerifier.verify(
                code,
                HandlerAsmGenerator.handlerName(HttpHandler.class, PayService.class, m),
                GeneratedClassVerifierTest.class.getClassLoader()));
    }

    /**
     * {@code HandlerAsmGenerator} 走真实生成器时同样不能抛——护栏接在生成器出口，
     * 好类必须一路畅通。
     */
    @Test
    void handlerAsmGeneratorDoesNotRejectGoodClass() throws Exception {
        Method m = PayService.class.getMethod("notify", PayDto.class);
        byte[] code = HandlerAsmGenerator.INSTANCE.generateHandlerClass(
                HttpHandler.class, PayService.class, m, payAnnos(),
                GeneratedClassVerifierTest.class.getClassLoader());
        assertTrue(code.length > 0);
    }
}
