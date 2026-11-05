package io.edap.container.app.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成类字节码的<b>生成期</b>校验护栏——把「坏字节码」从「部署期链接期炸」提前到「生成期炸」。
 *
 * <p><b>为什么需要它</b>：ASM 的 {@code ClassWriter.COMPUTE_FRAMES} 在两条控制流路径栈高不一致时
 * <b>不会报错</b>——它合并汇合帧时取两条路径的较大栈高，把多出来的槽位填成 {@code top}，
 * 于是产出一个「语法合法、语义非法」的 class 文件。这类 class 在 {@code defineClass} 阶段
 * （只加载不校验）不报错，直到<b>链接期</b>（{@code getConstructor} / {@code getDeclaredConstructors}
 * 触发校验）才抛 {@code VerifyError: Inconsistent stackmap frames at branch target N}。
 * 对容器而言，链接期发生在 {@code AppContext.generateHandler} 的反射实例化阶段，
 * 表现为「EAR 恢复失败 / AppContext 启动失败」，而不是「这个 Handler 生成错了」——
 * 定位成本极高。本护栏把失败点前移到字节码生成的那一刻，并给出精确到分支目标的诊断。
 *
 * <p><b>三道护栏</b>（各自独立、失败原因不同，互补而非重复）：
 * <ol>
 *   <li><b>JVM 链接期校验（权威）</b>：在一次性子 ClassLoader 里 {@code defineClass} 后调
 *       {@code getDeclaredConstructors()} 触发 HotSpot verifier 亲自过一遍。
 *       这是唯一与「生产环境到底跑哪个 JVM」完全一致的检查。
 *       副作用控制：{@code getDeclaredConstructors()} 只触发链接（校验 + 准备），
 *       <b>不执行 {@code <clinit>}</b>——生成类的静态初始化块里有业务副作用，绝不能被护栏触发。</li>
 *   <li><b>ASM 数据流分析（结构检查，不依赖类加载）</b>：
 *       {@code Analyzer} + {@code BasicInterpreter} 从零重算每个指令的操作数栈高度。
 *       汇合点（分支目标）两条入边栈高不等时，ASM 在 {@code Frame.merge} 处抛
 *       {@code AnalyzerException: Incompatible stack heights}——这正是 JVM
 *       「Inconsistent stackmap frames at branch target N」的静态等价物。
 *       它<b>不读 class 文件里的 StackMapTable</b>（{@code Analyzer} 遇到 {@code FrameNode} 只做直落合并），
 *       所以能独立发现「代码本身栈不平衡」，而不是只复述 class 文件的自我声明。
 *       附带价值：{@code BasicInterpreter} 不做类型解析，不需要类路径里能加载所有被引用类型。</li>
 *   <li><b>ASM 静态校验（类型检查）</b>：{@code CheckClassAdapter.verify} 走
 *       {@code SimpleVerifier}——比护栏 2 更严，会检查类型可赋值性、初始化前使用、
 *       返回类型匹配等 {@code BasicInterpreter} 不管的问题。</li>
 * </ol>
 *
 * <p><b>失败语义</b>：任一护栏判定失败 → 抛 {@link VerifyError}（{@code extends LinkageError}），
 * 由调用方（{@link HandlerAsmGenerator}）包装成 {@code RouteBindException}，
 * 与既有「ASM 生成失败 / 类加载失败」走同一条失败通道（deploy fail(104)）。
 *
 * <p><b>不误伤原则</b>：三道护栏里任何一道因<b>环境原因</b>无法给出结论（依赖类型在 loader 里找不到、
 * {@code defineClass} 名字不匹配之外的加载异常）时，一律<b>跳过该道</b>而不是判定失败——
 * 环境问题不该阻断部署，真正的字节码问题由另外两道护栏兜底。
 *
 * <p><b>线程安全</b>：本类无可变状态，全部方法静态、可并发调用。
 */
public final class GeneratedClassVerifier {

    private GeneratedClassVerifier() {}

    /**
     * 校验生成的 Handler class 字节码；不通过则抛 {@link VerifyError}。
     *
     * @param bytes      生成的 class 字节码；{@code null} 或空数组表示「没生成」（ERPC/gRPC 占位）→ 跳过
     * @param binaryName 生成类的 binary name（点号形式），必须与 class 文件里的 {@code this_class} 一致，
     *                   否则 {@code defineClass} 会报 {@code wrong name}
     * @param loader     解析被引用类型的 ClassLoader（通常是 appCL）；{@code null} 表示用默认 ClassLoader
     * @throws VerifyError 任一护栏判定字节码非法
     */
    public static void verify(byte[] bytes, String binaryName, ClassLoader loader) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        List<String> problems = new ArrayList<>(3);

        String jvm = jvmVerify(bytes, binaryName, loader);
        if (jvm != null) {
            problems.add("[1] JVM 链接期校验: " + jvm);
        }
        String flow = dataFlowVerify(bytes);
        if (flow != null) {
            problems.add("[2] ASM 数据流分析(Analyzer+BasicInterpreter): " + flow);
        }
        String stat = staticVerify(bytes, loader);
        if (stat != null) {
            problems.add("[3] ASM 静态校验(CheckClassAdapter/SimpleVerifier): " + stat);
        }

        if (!problems.isEmpty()) {
            throw new VerifyError("生成类字节码校验失败: " + binaryName
                    + "\n  " + String.join("\n  ", problems));
        }
    }

    // ---- 护栏 1：JVM 链接期校验（权威）-------------------------------------

    /**
     * 在一次性子 ClassLoader 里 defineClass，再 {@code getDeclaredConstructors()} 触发 HotSpot 校验。
     *
     * <p>{@code getDeclaredConstructors()} 触发的是<b>链接期</b>（校验 + 准备 + 解析），
     * <b>不是初始化期</b>——{@code <clinit>} 不会被执行。这一点是护栏能安全放在生产路径上的前提：
     * 生成类的静态初始化块里有业务副作用（bean 查找 / 日志初始化），护栏触发它就是事故。</p>
     *
     * @return null 表示通过或因环境原因跳过；否则返回失败描述
     */
    private static String jvmVerify(byte[] bytes, String binaryName, ClassLoader parent) {
        try {
            Class<?> defined = new ThrowawayLoader(parent).define(binaryName, bytes);
            defined.getDeclaredConstructors();
            return null;
        } catch (VerifyError | ClassFormatError e) {
            // 这两类是「字节码本身非法」——判定失败。VerifyError 的 message 里带完整的
            // 字节码 dump / 异常处理器表 / StackMapTable，是最有价值的诊断信息，原样保留。
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (Throwable t) {
            // 其余（NoClassDefFoundError / ClassNotFoundException / TypeNotPresentException /
            // SecurityException ...）都是「环境无法完成校验」，不判定失败——由另外两道护栏兜底。
            return null;
        }
    }

    /**
     * 一次性 ClassLoader：只为触发一次链接期校验，校验完即可回收。
     * parent 指向业务 appCL，生成的 class 引用 app 里的 DTO / HttpRequest 等类型时才能解析。
     */
    private static final class ThrowawayLoader extends ClassLoader {
        ThrowawayLoader(ClassLoader parent) {
            super(parent);
        }
        Class<?> define(String name, byte[] code) {
            return defineClass(name, code, 0, code.length);
        }
    }

    // ---- 护栏 2：ASM 数据流分析（结构检查）-----------------------------------

    /**
     * {@code Analyzer} + {@code BasicInterpreter} 从零重算操作数栈高度。
     *
     * <p>汇合点栈高不一致时 ASM 在 {@code Frame.merge} 抛 {@code Incompatible stack heights}，
     * 与 JVM 的「Inconsistent stackmap frames at branch target N」一一对应。</p>
     *
     * @return null 表示通过；否则返回失败描述
     */
    private static String dataFlowVerify(byte[] bytes) {
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(bytes).accept(cn, 0);
        } catch (RuntimeException e) {
            return "class 文件解析失败: " + e;
        }
        for (MethodNode mn : cn.methods) {
            if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            try {
                new Analyzer<BasicValue>(new BasicInterpreter()).analyze(cn.name, mn);
            } catch (AnalyzerException e) {
                return "方法 " + mn.name + mn.desc + " 校验失败: " + e.getMessage();
            } catch (RuntimeException e) {
                return "方法 " + mn.name + mn.desc + " 分析异常: " + e;
            }
        }
        return null;
    }

    // ---- 护栏 3：ASM 静态校验（类型检查）-------------------------------------

    /**
     * {@code CheckClassAdapter.verify} —— 用 {@code SimpleVerifier} 做类型级校验。
     *
     * <p>{@code SimpleVerifier} 会按需 {@code Class.forName} 解析被引用的类型；解析不到时抛
     * {@code TypeNotPresentException}，属于环境问题，跳过本道护栏（不判定失败）。</p>
     *
     * @return null 表示通过或跳过；否则返回失败描述
     */
    private static String staticVerify(byte[] bytes, ClassLoader loader) {
        StringWriter sw = new StringWriter();
        try {
            CheckClassAdapter.verify(new ClassReader(bytes), loader, false, new PrintWriter(sw));
        } catch (Throwable t) {
            // 类型解析不了等环境问题 —— 跳过本道
            return null;
        }
        String out = sw.toString().trim();
        if (out.isEmpty()) {
            return null;
        }
        return compact(out);
    }

    /**
     * 把 {@code CheckClassAdapter.verify} 的输出压成一行摘要。
     * 输出形如「异常首行 + at xxx(行号) 若干 + Caused by: ...」，栈帧行对定位没价值，丢掉。
     */
    private static String compact(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("at ") || t.startsWith("... ")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(t);
            if (sb.length() > 1500) {
                sb.append(" ...");
                break;
            }
        }
        return sb.toString();
    }
}
