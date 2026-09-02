/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.container.transactional;

import io.edap.tx.EdapTransactionManager;
import io.edap.tx.TransactionContext;
import io.edap.tx.TransactionDefinition;
import io.edap.tx.TransactionDefinitionFactory;
import io.edap.tx.TransactionManagers;
import io.edap.tx.TransactionStatus;
import io.edap.tx.annotation.ManualTransaction;
import io.edap.tx.annotation.Transactional;
import io.edap.util.AsmUtil;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static io.edap.util.AsmUtil.saveClassFile;
import static io.edap.util.AsmUtil.toInternalName;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ICONST_2;
import static org.objectweb.asm.Opcodes.ICONST_3;
import static org.objectweb.asm.Opcodes.ICONST_4;
import static org.objectweb.asm.Opcodes.ICONST_5;
import static org.objectweb.asm.Opcodes.ICONST_M1;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;

/**
 * 为带有 {@link Transactional @Transactional} 注解的 bean 生成 ASM 字节码 wrapper——
 * 在方法入口 {@code txManager.getTransaction(def)}、出口 commit / rollback 异常时 rollback。
 *
 * <p><b>wrapper 结构</b>(以 {@code UserService.create(User)} 为例):</p>
 * <pre>
 *   public final class TxProxy_X implements UserService {
 *       static final TransactionDefinition DEF_create;
 *
 *       static {
 *           DEF_create = new TransactionDefinition.Builder()
 *               .propagation(Propagation.REQUIRED)
 *               .isolation(Isolation.DEFAULT)
 *               .build();
 *       }
 *
 *       private final EdapTransactionManager txManager;
 *       private final UserService           delegate;
 *
 *       public TxProxy_X(EdapTransactionManager tm, UserService d) { ... }
 *
 *       public User create(User u) {
 *           TransactionStatus s = txManager.getTransaction(DEF_create);
 *           try {
 *               User r = delegate.create(u);
 *               txManager.commit(s);
 *               return r;
 *           } catch (Throwable t) {
 *               txManager.rollback(s);
 *               throw t;
 *           }
 *       }
 *   }
 * </pre>
 *
 * <p><b>Phase 4 简化边界</b>:</p>
 * <ul>
 *   <li>{@code @Transactional} 异常路径走 {@link TransactionDefinition#shouldRollbackOn}
 *       智能判定:默认 RuntimeException / Error 回滚,checked Exception 不回滚;
 *       业务方可通过 {@link Transactional#rollbackFor()} / {@link Transactional#noRollbackFor()}
 *       覆盖。{@code @ManualTransaction} 仍是无条件 rollback 兜底 —— 业务方主动 ctx.commit() /
 *       ctx.rollback(),wrapper 只在业务方忘了清理时兜底</li>
 *   <li>不支持 {@code final} 类(JDK Proxy + ASM 都要求非 final)</li>
 *   <li>不支持 self-invocation(wrapper 内 this.method() 绕过——AOP 通用限制)</li>
 *   <li>接口方法 + Object 方法(Object.toString 等)都实现,但只 @Transactional 方法加 tx 包裹</li>
 *   <li>{@link Transactional#name()} 字段暂未内联(Phase 5 再说,只影响日志/监控)</li>
 * </ul>
 *
 * <p><b>类名</b> = {@code TxProxy_<seq>},全局递增避免多 bean 同接口时类名冲突。</p>
 */
public class TransactionalClassGenerator {

    private static final String TM_INTERNAL = AsmUtil.toInternalName(EdapTransactionManager.class.getName());
    private static final String DEF_INTERNAL = AsmUtil.toInternalName(TransactionDefinition.class.getName());
    private static final String STATUS_INTERNAL = AsmUtil.toInternalName(TransactionStatus.class.getName());
    private static final String PROPAGATION_INTERNAL = AsmUtil.toInternalName(io.edap.tx.propagation.Propagation.class.getName());
    private static final String ISOLATION_INTERNAL = AsmUtil.toInternalName(io.edap.tx.isolation.Isolation.class.getName());
    private static final String TX_CONTEXT_INTERNAL = AsmUtil.toInternalName(TransactionContext.class.getName());
    private static final String TMS_INTERNAL = AsmUtil.toInternalName(TransactionManagers.class.getName());
    private static final String FACTORY_INTERNAL = AsmUtil.toInternalName(TransactionDefinitionFactory.class.getName());
    private static final String CLASS_INTERNAL = "java/lang/Class";

    private final Class<?> serviceInterface;
    private final List<MethodSpec> methods;
    private final String wrapperInternalName;
    /**
     * 每个被 wrapper 的方法对应的 TransactionDefinition,按生成时遍历顺序排列;
     * 每个元素同时对应 wrapper 类上的 {@code DEF_<i>} 静态字段 + {@code <clinit>} 里
     * 的一次工厂调用。<b>运行期 wrapper 方法体只 GETSTATIC 这个字段,完全不再 invoke 工厂或 builder。</b>
     *
     * <p>同一接口的不同方法,如果注解参数(propagation/isolation/timeout/readOnly/
     * rollbackFor/noRollbackFor)一致,工厂内部 CACHE 命中返回同一实例 → 多个 DEF_<i
     * 字段可能指向同一个 TransactionDefinition 对象,内存零浪费。</p>
     */
    private final List<MethodSpec> defSpecs;
    /**
     * methods 下标 → defSpecs 下标 的映射;无注解方法对应 -1。
     * 构造期一次性算好,避免 emitTxSetup 里 O(n) 扫描。
     */
    private final int[] defIndexByMethod;

    public TransactionalClassGenerator(Class<?> serviceInterface, List<MethodSpec> methods) {
        this.serviceInterface = serviceInterface;
        this.methods = methods;
        // 类名按 (serviceInterface + 方法签名) 派生 —— 同一接口的同一组方法
        // 始终生成同一类名,可被 ClassLoader 缓存复用,不会每次 instantiate 都新 define。
        this.wrapperInternalName = AsmUtil.toInternalName(
                "txp" + (serviceInterface.getPackage()==null?"":"." + serviceInterface.getPackage().getName()) +
                        ".TxProxy_" + serviceInterface.getName().replace('.', '_').replace('$', '_')
                        + "_" + Integer.toHexString(methods.hashCode()));

        // 构造期调用 TransactionDefinitionFactory:为每个带注解方法预计算 def,
        // 工厂内部 CACHE 保证同 key 只构造一份 —— JVM 范围内同 defSpec 参数的方法
        // 共享同一 TransactionDefinition 实例。
        List<MethodSpec> specs = new ArrayList<>();
        int[] idxBy = new int[methods.size()];
        int defIdx = 0;
        for (int i = 0; i < methods.size(); i++) {
            MethodSpec spec = methods.get(i);
            if (spec.manual || spec.transactional) {
                specs.add(spec);
                idxBy[i] = defIdx++;
            } else {
                idxBy[i] = -1;
            }
        }
        this.defSpecs = specs;
        this.defIndexByMethod = idxBy;
    }

    /** 给定 methods 下标,返回对应方法在 defSpecs 里的索引;无注解返回 -1。 */
    private int defIndexOf(int methodIdx) {
        return defIndexByMethod[methodIdx];
    }

    /**
     * 生成 wrapper 字节码 + 用指定 ClassLoader define —— 返回 wrapper 实例。
     *
     * <p>TM 走静态注册表 {@link TransactionManagers},wrapper 字段只剩 {@code delegate}。</p>
     *
     * @param loader    edap-container 现有 ClassLoader(沿用 {@code HandlerAsmGenerator} 模式)
     * @param delegate  真实 bean 实例(wrapper 把方法调用转发给它)
     */
    public Object instantiate(ClassLoader loader, Object delegate) {
        byte[] bytes = generate();
        Class<?> wrapperClass;
        String proxyName = wrapperInternalName;
        try {
            wrapperClass = Class.forName(proxyName, true, loader);
        } catch (ClassNotFoundException ignore) {
            // 首次生成,define 进去
            try {saveClassFile("./" + toInternalName(proxyName) + ".class", bytes);} catch (IOException e) {}
            Class<?> defined = defineClass(loader, bytes);
            wrapperClass = defined;
        }
        try {
            return wrapperClass.getDeclaredConstructor(serviceInterface)
                    .newInstance(delegate);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to instantiate transactional wrapper " + wrapperClass.getName(), e);
        }
    }

    private Class<?> defineClass(ClassLoader loader, byte[] bytes) {
        try {
            java.lang.reflect.Method defineClass = ClassLoader.class.getDeclaredMethod(
                    "defineClass", String.class, byte[].class, int.class, int.class);
            defineClass.setAccessible(true);
            return (Class<?>) defineClass.invoke(loader, null, bytes, 0, bytes.length);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to define wrapper class", e);
        }
    }

    /** 生成字节码 —— 同 package 内供 instantiate / 单测使用。 */
    byte[] generate() {
        // COMPUTE_FRAMES 需要知道类继承关系 —— 默认 ClassWriter 试图读父类 class 文件,
        // 但本生成器没有"原 class" 可读。用匿名子类把所有"公共父类"统一返回 Object
        // (我们的 wrapper 只继承 Object + implements serviceInterface,够用)。
        ClassWriter cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        String[] interfaces = new String[]{AsmUtil.toInternalName(serviceInterface.getName())};
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER | ACC_FINAL, wrapperInternalName,
                null, "java/lang/Object", interfaces);

        // 字段:txManager + delegate
        visitFields(cw);

        // <clinit>:初始化每个 @Transactional 方法的 TransactionDefinition 常量
        visitClinit(cw);

        // <init>(EdapTransactionManager, ServiceIf)
        visitCtor(cw);

        // 每个 @Transactional 方法 + Object 公共方法
        for (int methodIdx = 0; methodIdx < methods.size(); methodIdx++) {
            visitWrappedMethod(cw, methodIdx);
        }
        // Object.equals/hashCode/toString —— 透传 delegate
        visitObjectMethods(cw);

        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        // DEBUG: write to disk for inspection
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/tx-wrapper.class"), bytes);
        } catch (Exception e) { /* ignore */ }
        return bytes;
    }

    private void visitFields(ClassWriter cw) {
        // delegate 实例字段:运行期 wrapper 把方法调用转发给它
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "delegate",
                "L" + AsmUtil.toInternalName(serviceInterface.getName()) + ";", null, null).visitEnd();

        // 每个被 tx 包裹的方法一个 static final TransactionDefinition DEF_<i>:
        // <clinit> 里调一次 TransactionDefinitionFactory.getTransactionDefinition 填充,
        // 运行期 wrapper 方法体只 GETSTATIC + ASTORE —— 零分配、零工厂调用。
        for (int i = 0; i < defSpecs.size(); i++) {
            cw.visitField(ACC_PUBLIC | ACC_FINAL | ACC_STATIC, "DEF_" + i,
                    "L" + DEF_INTERNAL + ";", null, null).visitEnd();
        }

        // 每个被 tx 包裹的方法一个 static final EdapTransactionManager TM_<i>:
        // <clinit> 里调一次 TransactionManagers.get(spec.txManagerName) 填充。
        // 原来用 TransactionManagerResolver 走实例字段 + 接口调用 —— 现在 TM 数极少
        // (一般就 1 个 DataSource),静态注册表 + 静态字段直接拿更极致。
        for (int i = 0; i < defSpecs.size(); i++) {
            cw.visitField(ACC_PUBLIC | ACC_FINAL | ACC_STATIC, "TM_" + i,
                    "L" + TM_INTERNAL + ";", null, null).visitEnd();
        }
    }

    private void visitClinit(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        if (!defSpecs.isEmpty()) {
            // 工厂方法签名:(Lio/edap/tx/propagation/Propagation;
            //               Lio/edap/tx/isolation/Isolation;
            //               I Z Ljava/lang/String;
            //               [Ljava/lang/Class;[Ljava/lang/Class;)
            //               -> Lio/edap/tx/TransactionDefinition;
            String factoryDesc =
                    "(L" + PROPAGATION_INTERNAL + ";"
                  + "L" + ISOLATION_INTERNAL + ";"
                  + "I"
                  + "Z"
                  + "Ljava/lang/String;"
                  + "[L" + CLASS_INTERNAL + ";"
                  + "[L" + CLASS_INTERNAL + ";"
                  + ")L" + DEF_INTERNAL + ";";

            // TransactionManagers.get(String) -> EdapTransactionManager 描述符
            String tmsDesc = "(Ljava/lang/String;)L" + TM_INTERNAL + ";";

            for (int i = 0; i < defSpecs.size(); i++) {
                MethodSpec spec = defSpecs.get(i);

                // ====== DEF_<i> = TransactionDefinitionFactory.getTransactionDefinition(...) ======
                // 1. propagation
                pushEnum(mv, PROPAGATION_INTERNAL, spec.propagation.name());
                // 2. isolation
                pushEnum(mv, ISOLATION_INTERNAL, spec.isolation.name());
                // 3. timeout (int)
                pushInt(mv, spec.timeout);
                // 4. readOnly (boolean = int 0/1)
                pushInt(mv, spec.readOnly ? 1 : 0);
                // 5. name (String) —— @Transactional.name() 暂未支持,统一传 null
                mv.visitInsn(ACONST_NULL);
                // 6. rollbackFor (Class[]) —— 空时 emit ICONST_0 + ANEWARRAY 数组字面量
                emitClassArrayOrEmpty(mv, spec.rollbackFor);
                // 7. noRollbackFor (Class[])
                emitClassArrayOrEmpty(mv, spec.noRollbackFor);

                mv.visitMethodInsn(INVOKESTATIC, FACTORY_INTERNAL, "getTransactionDefinition",
                        factoryDesc, false);
                mv.visitFieldInsn(PUTSTATIC, wrapperInternalName, "DEF_" + i,
                        "L" + DEF_INTERNAL + ";");

                // ====== TM_<i> = TransactionManagers.get(spec.txManagerName) ======
                mv.visitLdcInsn(spec.txManagerName);
                mv.visitMethodInsn(INVOKESTATIC, TMS_INTERNAL, "get", tmsDesc, false);
                mv.visitFieldInsn(PUTSTATIC, wrapperInternalName, "TM_" + i,
                        "L" + TM_INTERNAL + ";");
            }
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** emit Class[] 数组字面量;null / 空数组时 emit 长度为 0 的 Class[]。 */
    private static void emitClassArrayOrEmpty(MethodVisitor mv, Class<? extends Throwable>[] arr) {
        if (arr == null || arr.length == 0) {
            mv.visitInsn(ICONST_0);
            mv.visitTypeInsn(ANEWARRAY, CLASS_INTERNAL);
            return;
        }
        emitClassArray(mv, arr);
    }

    private void visitCtor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(L" + AsmUtil.toInternalName(serviceInterface.getName()) + ";)V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, wrapperInternalName, "delegate",
                "L" + AsmUtil.toInternalName(serviceInterface.getName()) + ";");

        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    /**
     * 生成 wrapper 方法体 —— 包含:
     * <ol>
     *   <li>{@code tm = resolver.resolve(spec.txManagerName)} —— 运行时按 bean 名查 tm,空串走默认</li>
     *   <li>构建 TransactionDefinition(本地变量,inline 注解值)</li>
     *   <li>{@code status = tm.getTransaction(def)}</li>
     *   <li>try { delegate.method(args); commit(status); return result; } catch (Throwable) { rollback; throw; }</li>
     * </ol>
     */
    private void visitWrappedMethod(ClassWriter cw, int methodIdx) {
        MethodSpec spec = methods.get(methodIdx);
        Method method = spec.method;
        String desc = Type.getMethodDescriptor(method);
        String[] exceptions = null; // 透传异常 —— checked exception 也走 catch(Throwable),Phase 3 MVP 不细分

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method.getName(), desc, null, exceptions);
        mv.visitCode();

        // ===== 本地变量槽位规划 =====
        // 0 = this
        // 1..N = 方法参数
        // N+1 = tm (EdapTransactionManager,运行时 resolver 解析得到)
        // N+2 = status (TransactionStatus)
        // N+3 = def (TransactionDefinition,来自 DEF_<i> 静态字段)
        // N+4 = result (返回类型)
        // N+5 = throwable
        // 注意:旧实现有 callerDepthSlot / savedDepthSlot 用于 ThreadLocal wrapperDepth
        // 栈式管理;新 manager 通过 status.isCompleted() 检测 stale state,
        // 不再需要 wrapper 入口 push 防御,此处已删除 2 个槽位;
        // 改用静态字段存 def 后,本地不再需要 tmpBuilderSlot,又省 1 个。
        int firstParamSlot = 1;
        int paramCount = method.getParameterCount();
        int tmSlot = firstParamSlot + paramCount;
        int statusSlot = tmSlot + 1;
        int defSlot = statusSlot + 1;
        int resultSlot = defSlot + 1;
        int throwableSlot = resultSlot + 1;

        // ===== 路径分支 =====
        // 关键:tm 解析 + def 加载 + getTransaction + bind 必须下沉到分支内 —
        // 无注解方法不开 tx 不绑 ctx。否则会泄漏事务 + 让 TxScope.currentStatus() 误报非空。
        if (spec.manual) {
            // @ManualTransaction 路径:try { delegate } + 异常路径 + 正常路径防御 rollback
            emitTxSetup(mv, defIndexOf(methodIdx), tmSlot, defSlot, statusSlot);

            Label lbTryStart = new Label();
            Label lbTryEnd = new Label();
            Label lbCatch = new Label();
            Label lbPastCatch = new Label();
            mv.visitTryCatchBlock(lbTryStart, lbTryEnd, lbCatch, "java/lang/Throwable");

            mv.visitLabel(lbTryStart);
            emitDelegateCall(mv, method, desc, resultSlot);
            mv.visitJumpInsn(GOTO, lbPastCatch);

            mv.visitLabel(lbTryEnd);
            mv.visitLabel(lbCatch);
            mv.visitVarInsn(ASTORE, throwableSlot);
            emitIfNotCompletedRollback(mv, tmSlot, statusSlot);
            mv.visitMethodInsn(INVOKESTATIC, TX_CONTEXT_INTERNAL, "unbind", "()V", false);
            mv.visitVarInsn(ALOAD, throwableSlot);
            mv.visitInsn(ATHROW);

            mv.visitLabel(lbPastCatch);
            emitIfNotCompletedRollback(mv, tmSlot, statusSlot);
            mv.visitMethodInsn(INVOKESTATIC, TX_CONTEXT_INTERNAL, "unbind", "()V", false);
            emitReturn(mv, method, resultSlot);
        } else if (spec.transactional) {
            // @Transactional 路径(wrapper-managed)
            emitTxSetup(mv, defIndexOf(methodIdx), tmSlot, defSlot, statusSlot);

            Label lbTryStart = new Label();
            Label lbTryEnd = new Label();
            Label lbCatch = new Label();
            Label lbEnd = new Label();
            mv.visitTryCatchBlock(lbTryStart, lbTryEnd, lbCatch, "java/lang/Throwable");

            mv.visitLabel(lbTryStart);
            emitDelegateCall(mv, method, desc, resultSlot);
            // 业务方可能 ctx.commit() 后 status.completed=true —— wrapper 跳过
            emitIfNotCompletedCommit(mv, tmSlot, statusSlot);
            mv.visitMethodInsn(INVOKESTATIC, TX_CONTEXT_INTERNAL, "unbind", "()V", false);
            emitReturn(mv, method, resultSlot);

            mv.visitLabel(lbTryEnd);
            mv.visitLabel(lbCatch);
            mv.visitVarInsn(ASTORE, throwableSlot);
            // @Transactional 走 shouldRollbackOn 智能判定:
            // 业务方 rollbackFor/noRollbackFor 覆盖默认规则(RuntimeException/Error → rollback,
            // checked → commit);默认情况下 SQLException 这种 checked exception 不会被回滚。
            // 两分支仍用 isCompleted 守卫 —— 业务方可能 ctx.rollback()/ctx.commit() 后
            // status.completed=true,wrapper 跳过双 commit/rollback。
            emitShouldRollbackBranch(mv, defSlot, throwableSlot, tmSlot, statusSlot);
            mv.visitMethodInsn(INVOKESTATIC, TX_CONTEXT_INTERNAL, "unbind", "()V", false);
            mv.visitVarInsn(ALOAD, throwableSlot);
            mv.visitInsn(ATHROW);

            mv.visitLabel(lbEnd);
        } else {
            // 无注解方法 —— 纯透传 delegate,完全不开 tx、不绑 ctx。
            // 让 TxScope.currentStatus() 返回 null,业务方据此判断是否需要自己起 tx。
            // 直接 ARETURN delegate.method(args) —— 不走 emitDelegateCall + emitReturn 那条
            // ASTORE resultSlot / ALOAD resultSlot 的绕路,字节码少两指令,栈深浅一层。
            String serviceIf = AsmUtil.toInternalName(serviceInterface.getName());
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, wrapperInternalName, "delegate", "L" + serviceIf + ";");
            for (int i = 0; i < paramCount; i++) {
                mv.visitVarInsn(loadInsn(method.getParameterTypes()[i]), 1 + i);
            }
            mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(), desc, true);
            mv.visitInsn(method.getReturnType() == void.class ? RETURN : returnInsn(method.getReturnType()));
        }

        // 注意:旧实现 visitMaxs(8, savedDepthSlot + 1) 含 callerDepth / savedDepth 槽位;
        // 新实现 max locals 缩小 2(COMPUTE_MAXS 会重算,此 hint 可忽略,保留以保持改动最小)
        mv.visitMaxs(8, throwableSlot + 1);
        mv.visitEnd();
    }

    /**
     * 生成 wrapper 方法的"事务准备"代码 — 仅在有注解的分支内调用。
     *
     * <ol>
     *   <li>{@code tm = wrapperClass.TM_<defIndex>} —— <b>零分配</b>,直接 GETSTATIC
     *       <clinit> 期通过 TransactionManagers.get(spec.txManagerName) 填充的静态字段;
     *       多 DataSource 场景下不同方法可路由到不同 TM(每个方法自己的 TM_<i>)</li>
     *   <li>{@code def = wrapperClass.DEF_<defIndex>} —— 同样零分配,GETSTATIC
     *       <clinit> 期通过 TransactionDefinitionFactory 填充的静态字段</li>
     *   <li>{@code status = tm.getTransaction(def)}</li>
     *   <li>{@code TransactionContext.bind(tm, status)} —— 业务方可通过 ctx.current() 拿到</li>
     * </ol>
     */
    private void emitTxSetup(MethodVisitor mv, int defIndex,
                             int tmSlot, int defSlot, int statusSlot) {
        // ===== tm = wrapperClass.TM_<defIndex> =====
        // 原来:resolver 是实例字段,运行时 invokeinterface resolve("") 查 Map。
        // 现在:<clinit> 期 TransactionManagers.get(spec.txManagerName) 已把 TM 缓存进
        // static final TM_<defIndex>,运行期只 GETSTATIC —— 零查找、零分配、零 indirection。
        mv.visitFieldInsn(GETSTATIC, wrapperInternalName, "TM_" + defIndex,
                "L" + TM_INTERNAL + ";");
        mv.visitVarInsn(ASTORE, tmSlot);

        // ===== def = wrapperClass.DEF_<defIndex> =====
        mv.visitFieldInsn(GETSTATIC, wrapperInternalName, "DEF_" + defIndex,
                "L" + DEF_INTERNAL + ";");
        mv.visitVarInsn(ASTORE, defSlot);

        // ===== status = tm.getTransaction(def) =====
        mv.visitVarInsn(ALOAD, tmSlot);
        mv.visitVarInsn(ALOAD, defSlot);
        mv.visitMethodInsn(INVOKEINTERFACE, TM_INTERNAL, "getTransaction",
                "(L" + DEF_INTERNAL + ";)L" + STATUS_INTERNAL + ";", true);
        mv.visitVarInsn(ASTORE, statusSlot);

        // ===== TransactionContext.bind(tm, status) → TransactionContext =====
        // bind 返回 ctx 实例(wrapper 不需返回值,但描述符必须匹配新签名);
        // snapshot 中 status 由 manager 在 getTransaction 时绑定,这里追加 ctx 引用
        mv.visitVarInsn(ALOAD, tmSlot);
        mv.visitVarInsn(ALOAD, statusSlot);
        mv.visitMethodInsn(INVOKESTATIC, TX_CONTEXT_INTERNAL, "bind",
                "(L" + TM_INTERNAL + ";L" + STATUS_INTERNAL + ";)L" + TX_CONTEXT_INTERNAL + ";", false);
        mv.visitInsn(POP);   // 丢掉返回值 — wrapper 路径里业务方需要 ctx 时自己调 current()
    }

    // ============ 公共 emit 辅助 ============

    /** 调用 delegate.method(args),返回值存到 resultSlot(void 方法跳过 store)。 */
    private void emitDelegateCall(MethodVisitor mv, Method method, String desc, int resultSlot) {
        String serviceIf = AsmUtil.toInternalName(serviceInterface.getName());
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, wrapperInternalName, "delegate", "L" + serviceIf + ";");
        int paramCount = method.getParameterCount();
        for (int i = 0; i < paramCount; i++) {
            mv.visitVarInsn(loadInsn(method.getParameterTypes()[i]), 1 + i);
        }
        mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, method.getName(), desc, true);
        if (method.getReturnType() != void.class) {
            mv.visitVarInsn(storeInsn(method.getReturnType()), resultSlot);
        }
    }

    /** 方法返回:void 用 RETURN,其他类型 load from resultSlot + 对应 XRETURN。 */
    private void emitReturn(MethodVisitor mv, Method method, int resultSlot) {
        if (method.getReturnType() == void.class) {
            mv.visitInsn(RETURN);
        } else {
            mv.visitVarInsn(loadInsn(method.getReturnType()), resultSlot);
            mv.visitInsn(returnInsn(method.getReturnType()));
        }
    }

    /**
     * 推入 {@code Class<?>[]} 数组字面量 —— 用于把 {@code @Transactional(rollbackFor=...)}
     * 的 Class 引用列表直接 inline 进 wrapper 字节码。
     *
     * <p>字节码序列(以长度 N、第 i 个元素 C_i 为例):</p>
     * <pre>
     *   iconst_N
     *   anewarray java/lang/Class
     *   dup
     *   iconst_i
     *   ldc &lt;Type of C_i&gt;
     *   aastore
     *   ... 重复 i=0..N-1
     * </pre>
     *
     * <p>输入 null 或 length=0 时直接抛 IAE —— 调用方应自行 guard,
     * 避免空数组多生成无用字节码。</p>
     */
    private static void emitClassArray(MethodVisitor mv, Class<?>[] classes) {
        if (classes == null) {
            throw new IllegalArgumentException("classes == null");
        }
        pushInt(mv, classes.length);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        for (int i = 0; i < classes.length; i++) {
            mv.visitInsn(DUP);
            pushInt(mv, i);
            mv.visitLdcInsn(Type.getType(classes[i]));
            mv.visitInsn(AASTORE);
        }
    }

    /**
     * {@code if (!status.isCompleted()) tm.commit(status)} —— 守卫 wrapper-managed commit。 */
    private void emitIfNotCompletedCommit(MethodVisitor mv, int tmSlot, int statusSlot) {
        Label skip = new Label();
        mv.visitVarInsn(ALOAD, statusSlot);
        mv.visitMethodInsn(INVOKEVIRTUAL, STATUS_INTERNAL, "isCompleted", "()Z", false);
        mv.visitJumpInsn(IFNE, skip);
        mv.visitVarInsn(ALOAD, tmSlot);
        mv.visitVarInsn(ALOAD, statusSlot);
        mv.visitMethodInsn(INVOKEINTERFACE, TM_INTERNAL, "commit",
                "(L" + STATUS_INTERNAL + ";)V", true);
        mv.visitLabel(skip);
    }

    /** {@code if (!status.isCompleted()) tm.rollback(status)} —— 用于 wrapper 兜底 / 异常路径。 */
    private void emitIfNotCompletedRollback(MethodVisitor mv, int tmSlot, int statusSlot) {
        Label skip = new Label();
        mv.visitVarInsn(ALOAD, statusSlot);
        mv.visitMethodInsn(INVOKEVIRTUAL, STATUS_INTERNAL, "isCompleted", "()Z", false);
        mv.visitJumpInsn(IFNE, skip);
        mv.visitVarInsn(ALOAD, tmSlot);
        mv.visitVarInsn(ALOAD, statusSlot);
        mv.visitMethodInsn(INVOKEINTERFACE, TM_INTERNAL, "rollback",
                "(L" + STATUS_INTERNAL + ";)V", true);
        mv.visitLabel(skip);
    }

    /**
     * {@code @Transactional} 异常路径的智能判定 —— 调用
     * {@link TransactionDefinition#shouldRollbackOn(Throwable)}:
     *
     * <pre>
     *   if (def.shouldRollbackOn(t)) {
     *       if (!status.isCompleted()) tm.rollback(status);
     *   } else {
     *       if (!status.isCompleted()) tm.commit(status);
     *   }
     * </pre>
     *
     * <p>这是 {@code rollbackFor / noRollbackFor} 唯一生效的位置;
     * 默认规则(RuntimeException / Error → rollback,checked → commit)也走这里。
     * 仅 {@code @Transactional} 路径使用 —— {@code @ManualTransaction} 业务方主动管理,
     * wrapper 只做"忘了就 rollback"兜底。</p>
     */
    private void emitShouldRollbackBranch(MethodVisitor mv, int defSlot, int throwableSlot,
                                          int tmSlot, int statusSlot) {
        Label lbCommit = new Label();
        Label lbEnd = new Label();
        // if (!def.shouldRollbackOn(t)) goto lbCommit
        mv.visitVarInsn(ALOAD, defSlot);
        mv.visitVarInsn(ALOAD, throwableSlot);
        mv.visitMethodInsn(INVOKEVIRTUAL, DEF_INTERNAL, "shouldRollbackOn",
                "(Ljava/lang/Throwable;)Z", false);
        mv.visitJumpInsn(IFEQ, lbCommit);
        // rollback 分支
        emitIfNotCompletedRollback(mv, tmSlot, statusSlot);
        mv.visitJumpInsn(GOTO, lbEnd);
        // commit 分支
        mv.visitLabel(lbCommit);
        emitIfNotCompletedCommit(mv, tmSlot, statusSlot);
        mv.visitLabel(lbEnd);
    }

    private void visitObjectMethods(ClassWriter cw) {
        // hashCode/equals/toString —— 简单透传到 delegate(避免 bean 在 HashMap 里行为异常)
        String serviceIf = AsmUtil.toInternalName(serviceInterface.getName());

        // hashCode()I
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, wrapperInternalName, "delegate", "L" + serviceIf + ";");
        mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, "hashCode", "()I", true);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // equals(Ljava/lang/Object;)Z
        mv = cw.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, wrapperInternalName, "delegate", "L" + serviceIf + ";");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, "equals", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();

        // toString()Ljava/lang/String;
        mv = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, wrapperInternalName, "delegate", "L" + serviceIf + ";");
        mv.visitMethodInsn(INVOKEINTERFACE, serviceIf, "toString", "()Ljava/lang/String;", true);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    // ============ 类型 → JVM 操作码辅助 ============

    /** 加载(int / float / long / double / ref)指令。 */
    private static int loadInsn(Class<?> t) {
        if (t == int.class || t == boolean.class || t == byte.class || t == short.class || t == char.class) {
            return org.objectweb.asm.Opcodes.ILOAD;
        }
        if (t == long.class) return org.objectweb.asm.Opcodes.LLOAD;
        if (t == float.class) return org.objectweb.asm.Opcodes.FLOAD;
        if (t == double.class) return org.objectweb.asm.Opcodes.DLOAD;
        return ALOAD;
    }

    /** 存储指令。 */
    private static int storeInsn(Class<?> t) {
        if (t == int.class || t == boolean.class || t == byte.class || t == short.class || t == char.class) {
            return org.objectweb.asm.Opcodes.ISTORE;
        }
        if (t == long.class) return org.objectweb.asm.Opcodes.LSTORE;
        if (t == float.class) return org.objectweb.asm.Opcodes.FSTORE;
        if (t == double.class) return org.objectweb.asm.Opcodes.DSTORE;
        return ASTORE;
    }

    /** 返回指令。void 返回 RETURN,其他 ARETURN / IRETURN / LRETURN / FRETURN / DRETURN。 */
    private static int returnInsn(Class<?> t) {
        if (t == void.class) return RETURN;
        if (t == int.class || t == boolean.class || t == byte.class || t == short.class || t == char.class) {
            return org.objectweb.asm.Opcodes.IRETURN;
        }
        if (t == long.class) return org.objectweb.asm.Opcodes.LRETURN;
        if (t == float.class) return org.objectweb.asm.Opcodes.FRETURN;
        if (t == double.class) return org.objectweb.asm.Opcodes.DRETURN;
        return ARETURN;
    }

    /** 推入 enum 单例 —— {@code enumClass.valueOf("NAME")}。 */
    private static void pushEnum(MethodVisitor mv, String enumInternal, String name) {
        mv.visitLdcInsn(name);
        // valueOf 是 enum 的 static method —— enum 是 class 不是 interface,isInterface=false
        mv.visitMethodInsn(INVOKESTATIC, enumInternal, "valueOf",
                "(Ljava/lang/String;)L" + enumInternal + ";", false);
    }

    /** 推入 int 常量(0~5 用 ICONST,其他 BIPUSH / SIPUSH)。 */
    private static void pushInt(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) {
            switch (v) {
                case -1: mv.visitInsn(ICONST_M1); return;
                case 0: mv.visitInsn(ICONST_0); return;
                case 1: mv.visitInsn(ICONST_1); return;
                case 2: mv.visitInsn(ICONST_2); return;
                case 3: mv.visitInsn(ICONST_3); return;
                case 4: mv.visitInsn(ICONST_4); return;
                case 5: mv.visitInsn(ICONST_5); return;
            }
        }
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            mv.visitIntInsn(org.objectweb.asm.Opcodes.BIPUSH, v);
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            mv.visitIntInsn(org.objectweb.asm.Opcodes.SIPUSH, v);
        } else {
            mv.visitLdcInsn(v);
        }
    }

    /** wrapper 持有的方法 + 注解元数据。 */
    public static final class MethodSpec {
        public final Method method;
        public final boolean transactional;
        public final boolean manual;
        public final io.edap.tx.propagation.Propagation propagation;
        public final io.edap.tx.isolation.Isolation isolation;
        public final int timeout;
        public final boolean readOnly;
        /**
         * tm bean 名 —— 由运行时 {@link TransactionManagerResolver#resolve(String)} 查 tm。
         * 为 {@code ""} 时由 resolver 决定"默认 tm"。当前阶段仅 {@code @ManualTransaction} 携带此字段;
         * {@code @Transactional} 留 {@code ""}(如果需要后续可同步扩展 @Transactional)。
         */
        public final String txManagerName;
        /**
         * 触发回滚的异常类型集合 —— {@link TransactionDefinition#shouldRollbackOn} 判定时优先于默认规则。
         * 仅 {@code @Transactional} 携带此字段(从注解 {@link Transactional#rollbackFor()} 读);
         * {@code @ManualTransaction} / 无注解时为 {@code new Class[0]}。
         */
        public final Class<? extends Throwable>[] rollbackFor;
        /**
         * 强制不回滚的异常类型集合(即使默认规则会触发也跳过)。
         * 仅 {@code @Transactional} 携带此字段(从注解 {@link Transactional#noRollbackFor()} 读);
         * {@code @ManualTransaction} / 无注解时为 {@code new Class[0]}。
         */
        public final Class<? extends Throwable>[] noRollbackFor;

        @SuppressWarnings("unchecked")
        public MethodSpec(Method method, Transactional tx, ManualTransaction manualTx) {
            this.method = method;
            if (tx != null) {
                this.transactional = true;
                this.manual = false;
                this.propagation = tx.propagation();
                this.isolation = tx.isolation();
                this.timeout = tx.timeout();
                this.readOnly = tx.readOnly();
                this.txManagerName = tx.transactionManager();
                this.rollbackFor = tx.rollbackFor();
                this.noRollbackFor = tx.noRollbackFor();
            } else if (manualTx != null) {
                this.transactional = false;
                this.manual = true;
                this.propagation = manualTx.propagation();
                this.isolation = manualTx.isolation();
                this.timeout = manualTx.timeout();
                this.readOnly = manualTx.readOnly();
                this.txManagerName = manualTx.transactionManager();
                // @ManualTransaction 无 rollbackFor/noRollbackFor 字段 —— wrapper 不应用 shouldRollbackOn,
                // 业务方主动 ctx.commit()/ctx.rollback(),wrapper 只做"业务忘了就回滚"兜底
                this.rollbackFor = new Class[0];
                this.noRollbackFor = new Class[0];
            } else {
                this.transactional = false;
                this.manual = false;
                // 默认值仅占位 —— 非事务方法不读这些字段
                this.propagation = io.edap.tx.propagation.Propagation.REQUIRED;
                this.isolation = io.edap.tx.isolation.Isolation.DEFAULT;
                this.timeout = -1;
                this.readOnly = false;
                this.txManagerName = "";
                this.rollbackFor = new Class[0];
                this.noRollbackFor = new Class[0];
            }
        }

        public static MethodSpec defaultFor(Method method) {
            return new MethodSpec(method,
                    method.getAnnotation(Transactional.class),
                    method.getAnnotation(ManualTransaction.class));
        }
    }

    /** 静态工厂:扫描 serviceInterface 公共方法(非 Object / 非 default / 非 synthetic),
     * 返回 ALL 方法(含 @Transactional + @ManualTransaction + 无注解) — wrapper
     * 必须实现所有接口方法。注解仅从接口方法自身读取,不读实现类 override。
     *
     * <p>保留此重载用于纯接口注解场景(测试桩 / 手工组装 bean 场景)。生产路径
     * 推荐用 {@link #scanTransactional(Class, Class)} 以支持 edap / proto
     * 自动生成接口、业务方只能在 impl 上加注解的情况。</p>
     */
    public static List<MethodSpec> scanTransactional(Class<?> serviceInterface) {
        List<MethodSpec> result = new ArrayList<>();
        for (Method m : serviceInterface.getMethods()) {
            if (m.isDefault() || m.isSynthetic()) continue;
            result.add(new MethodSpec(m,
                    m.getAnnotation(Transactional.class),
                    m.getAnnotation(ManualTransaction.class)));
        }
        return result;
    }

    /**
     * 同 {@link #scanTransactional(Class)} 但注解源优先读实现类 override 方法,
     * fallback 到接口方法自身 — 接口由 edap / proto 自动生成、不可改,
     * 业务方只能在 impl 方法上加 {@code @Transactional} 时使用。
     *
     * <p><b>注解合并规则</b>(优先级从高到低):对每个接口方法 {@code m}:</p>
     * <ul>
     *   <li><b>方法级</b>:impl override 方法上的 {@code @Transactional} / {@code @ManualTransaction}
     *       (若存在,声明类非接口自身);</li>
     *   <li>方法级:接口方法 {@code m} 自身的同名注解;</li>
     *   <li><b>类级 fallback</b>:implClass 层级(impl 类 + 父类)上的 {@code @Transactional}
     *       / {@code @ManualTransaction} —— 仅在方法级都没注解时使用(Spring 语义:类级
     *       注解 = 该类所有方法的默认配置);</li>
     *   <li>都没注解 → 该方法不开事务(wrapper 走纯透传)。</li>
     * </ul>
     *
     * <p>impl override 的方法签名(返回类型 / checked exception)必须与接口方法
     * 一致 — JVM 验证,这里不做额外检查。</p>
     */
    public static List<MethodSpec> scanTransactional(Class<?> serviceInterface, Class<?> implClass) {
        List<MethodSpec> result = new ArrayList<>();
        // 类级注解 fallback 源 — impl 类层级(implClass + 父类),沿继承链向上找第一个
        Transactional txClassLevel = findClassLevelAnnotation(implClass, Transactional.class);
        ManualTransaction manualTxClassLevel = findClassLevelAnnotation(implClass, ManualTransaction.class);
        for (Method im : serviceInterface.getMethods()) {
            if (im.isDefault() || im.isSynthetic()) continue;
            Method implMethod = findImplOverride(implClass, im);
            // 方法级:impl override > 接口方法自身
            Transactional tx = null;
            ManualTransaction manualTx = null;
            if (implMethod != null) {
                tx = implMethod.getAnnotation(Transactional.class);
                manualTx = implMethod.getAnnotation(ManualTransaction.class);
            }
            if (tx == null) tx = im.getAnnotation(Transactional.class);
            if (manualTx == null) manualTx = im.getAnnotation(ManualTransaction.class);
            // 类级 fallback — 仅当方法级都没注解时使用
            if (tx == null && manualTx == null) {
                tx = txClassLevel;
                manualTx = manualTxClassLevel;
            }
            // MethodSpec.method 用接口方法 —— wrapper 字节码需匹配接口签名
            result.add(new MethodSpec(im, tx, manualTx));
        }
        return result;
    }

    /**
     * 在 {@code implClass} 层级(impl 类 + 父类,沿继承链向上)查找第一个出现的指定注解。
     * 用于类级 {@code @Transactional} / {@code @ManualTransaction} 的 fallback 解析。
     *
     * <p>与 {@link java.lang.annotation.Inherited} 不同 —— 我们不走注解元数据,
     * 而是手动遍历类层级(避免依赖注解声明者额外添加 {@code @Inherited})。</p>
     */
    private static <A extends java.lang.annotation.Annotation> A findClassLevelAnnotation(
            Class<?> implClass, Class<A> annotationType) {
        Class<?> c = implClass;
        while (c != null && c != Object.class) {
            A ann = c.getAnnotation(annotationType);
            if (ann != null) return ann;
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 在 {@code beanClass} 上找 override {@code ifaceMethod} 的方法。
     * 与 {@link TransactionalBeanPostProcessor#findImplOverride} 同语义 —
     * 此处独立实现一份(同包内可读,避免把 BPP 工具方法暴露为 public)。
     */
    private static Method findImplOverride(Class<?> beanClass, Method ifaceMethod) {
        if (beanClass == null) return null;
        try {
            Method m = beanClass.getMethod(ifaceMethod.getName(), ifaceMethod.getParameterTypes());
            if (m.getDeclaringClass().isInterface()) return null;
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}