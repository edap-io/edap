/*
 * Copyright 2026 The edap Project
 *
 * The edap Project licenses this file to you under the Apache License,
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

package io.edap.json;

import io.edap.json.model.JsonFieldInfo;
import io.edap.json.util.JsonUtil;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

import static io.edap.json.util.JsonUtil.buildMapBeanDecoderName;
import static io.edap.json.util.JsonUtil.getCodecFieldInfos;
import static io.edap.json.util.JsonUtil.getJsonFieldName;
import static io.edap.util.AsmUtil.isList;
import static io.edap.util.AsmUtil.isPojo;
import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.ClazzUtil.getDescriptor;
import org.objectweb.asm.Opcodes;
import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ACC_BRIDGE;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.IF_ICMPLT;
import static org.objectweb.asm.Opcodes.IFNONNULL;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INSTANCEOF;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;

/**
 * ASM 生成 {@code Map<String, Object> → JavaBean} 解码器，平行于 {@link JsonDecoderGenerator}。
 *
 * <p><b>生成类形态</b>：
 * <pre>{@code
 *   public class ejmb.<pkg>.<SimpleName>MapBeanDecoder
 *           extends AbstractMapBeanDecoder
 *           implements MapBeanDecoder<<SimpleName>> {
 *
 *       private static MapBeanDecoder<<NestType>> <nestSimpleName>Decoder;
 *
 *       public <init>() {
 *           super();
 *           if (<nestSimpleName>Decoder == null)
 *               <nestSimpleName>Decoder = JsonCodecRegister.instance()
 *                                          .getMapBeanDecoder(<NestType>.class);
 *       }
 *
 *       public <SimpleName> decode(Map<String, Object> map) {
 *           <SimpleName> bean = new <SimpleName>();
 *           // 字段驱动：per field → map.get → null check → cast/set
 *           ...
 *           return bean;
 *       }
 *
 *       public synthetic Object decode(Map<String, Object> map) {
 *           return decode(map);  // 桥接
 *       }
 *   }
 * }</pre></p>
 *
 * <p><b>类型转换策略</b>：primitive 字段走 {@code JsonUtil.getIntValue(Long)} 等静态
 *     helper（{@code INVOKESTATIC}），不发射 per-class 私有 helper。</p>
 *
 * <p><b>嵌套 POJO 缓存</b>：生成类持 {@code private static MapBeanDecoder<Nest> <name>Decoder}
 *     字段，{@code <init>} 阶段 lazy init（双检锁）。后续 decode 阶段 GETSTATIC，无 cache
 *     反复查表。</p>
 *
 * <p><b>Frame 策略</b>：{@code ClassWriter.COMPUTE_FRAMES} —— ClassWriter 自动计算所有
 *     栈帧与局部变量表，不必手动 emit {@code visitFrame} 调用。</p>
 */
public class MapBeanDecoderGenerator {

    static final String IFACE_NAME         = toInternalName(MapBeanDecoder.class.getName());
    static final String PARENT_NAME        = toInternalName(AbstractMapBeanDecoder.class.getName());
    static final String MAP_NAME           = toInternalName(java.util.Map.class.getName());
    static final String LIST_NAME          = toInternalName(java.util.List.class.getName());
    static final String ARRAY_LIST_NAME    = toInternalName(java.util.ArrayList.class.getName());
    static final String ITERATOR_NAME      = toInternalName(java.util.Iterator.class.getName());
    static final String JSON_UTIL_NAME     = toInternalName(JsonUtil.class.getName());
    static final String REGISTER_NAME      = toInternalName(JsonCodecRegister.class.getName());

    private ClassWriter cw;
    private String pojoName;
    private String decoderName;
    private final Class<?> pojoCls;

    /** 嵌套 POJO 类型列表（每种类型对应一个 static field + lazy init）。 */
    private final List<java.lang.reflect.Type> nestedPojoTypes = new ArrayList<>();

    public MapBeanDecoderGenerator(Class<?> pojoClass) {
        this.pojoCls = pojoClass;
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        pojoName = toInternalName(pojoCls.getName());
        decoderName = toInternalName(buildMapBeanDecoderName(pojoCls));
        gci.clazzName = decoderName;

        String[] ifaceName = new String[]{IFACE_NAME};
        // 使用 raw 类型作为 class signature（不参数化 MapBeanDecoder<T>）。
        // AbstractMapBeanDecoder 已 implements MapBeanDecoder<Object>，生成类通过
        // 继承满足契约；不需要在 class signature 上重新参数化为 MapBeanDecoder<Pojo>。
        // 经验：参数化签名在某些 JVM 上会触发 'illegal signature' 校验失败。
        String signature = null;
        List<JsonFieldInfo> fields = getCodecFieldInfos(pojoCls);

        // 收集嵌套 POJO 类型（POJO 字段、List<T> 的 T、POJO[] 元素类型）
        for (JsonFieldInfo pfi : fields) {
            collectNestedTypes(pfi.field.getGenericType());
        }

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, decoderName, signature, PARENT_NAME, ifaceName);

        visitNestedPojoFields();
        visitInit();
        visitDecodeMethod(fields);
        visitDecodeBridgeMethod();

        cw.visitEnd();
        gci.clazzBytes = cw.toByteArray();
        return gci;
    }

    private void collectNestedTypes(java.lang.reflect.Type genericType) {
        if (isPojo(genericType)) {
            if (!nestedPojoTypes.contains(genericType)) {
                nestedPojoTypes.add(genericType);
            }
        } else if (isList(genericType) && genericType instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType) genericType;
            java.lang.reflect.Type itemType = ptype.getActualTypeArguments()[0];
            if (isPojo(itemType) && !nestedPojoTypes.contains(itemType)) {
                nestedPojoTypes.add(itemType);
            }
        } else if (genericType instanceof Class) {
            Class<?> cls = (Class<?>) genericType;
            if (cls.isArray() && !cls.getComponentType().isPrimitive()) {
                java.lang.reflect.Type compType = cls.getComponentType();
                if (isPojo(compType) && !nestedPojoTypes.contains(compType)) {
                    nestedPojoTypes.add(compType);
                }
            }
        }
    }

    private void visitNestedPojoFields() {
        // 不再为嵌套 POJO 生成 static field —— 改用 inline 调用
        // {@code JsonCodecRegister.instance().getMapBeanDecoder(NestType.class)}
        // 每次 decode 走 cache（HashMap lookup），避免自递归类型的
        // {@code <init>} → {@code getMapBeanDecoder(self)} → 新 <init> 死循环。
    }

    /**
     * {@code <init>()}：仅 super()。嵌套 sub-decoder 通过 inline 模式在 decode 时获取，
     *     不在构造期 eager 初始化（避免 self-referential 类型的递归加载死循环）。
     */
    private void visitInit() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code <T> decode(Map<String, Object> map)}：field-driven 直行代码。
     */
    private void visitDecodeMethod(List<JsonFieldInfo> fields) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "decode",
                "(L" + MAP_NAME + ";)L" + pojoName + ";", null, null);
        mv.visitCode();

        // <T> bean = new <T>();
        mv.visitTypeInsn(NEW, pojoName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, pojoName, "<init>", "()V", false);
        int varBean = 2;
        mv.visitVarInsn(ASTORE, varBean);

        int varV = 3;
        for (JsonFieldInfo pfi : fields) {
            String jsonFieldName = getJsonFieldName(pfi.field, pfi.jsonFieldName);
            emitFieldAssignment(mv, pfi, jsonFieldName, varBean, varV);
        }

        // return bean;
        mv.visitVarInsn(ALOAD, varBean);
        mv.visitInsn(ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * 单字段赋值。
     *
     * <p>栈契约：方法开始时栈空，方法结束时栈空。内部用 {@code varV}（slot 3）暂存
     *     {@code map.get(name)} 返回值，null 跳过；非 null 按字段类型 dispatch。</p>
     */
    private void emitFieldAssignment(MethodVisitor mv, JsonFieldInfo pfi, String jsonFieldName,
                                     int varBean, int varV) {
        // v = map.get("name");
        mv.visitVarInsn(ALOAD, 1);                              // map
        mv.visitLdcInsn(jsonFieldName);                          // key
        mv.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitVarInsn(ASTORE, varV);

        // if (v == null) skip;
        Label lbSet = new Label();
        mv.visitVarInsn(ALOAD, varV);
        mv.visitJumpInsn(IFNONNULL, lbSet);
        Label lbSkip = new Label();
        mv.visitJumpInsn(GOTO, lbSkip);
        mv.visitLabel(lbSet);

        // 类型 dispatch
        java.lang.reflect.Type genericType = pfi.field.getGenericType();
        if (isPojo(genericType)) {
            emitNestedPojoAssign(mv, pfi, (Class<?>) genericType, varBean, varV);
        } else if (isList(genericType) && genericType instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType) genericType;
            java.lang.reflect.Type itemType = ptype.getActualTypeArguments()[0];
            emitListAssign(mv, pfi, itemType, varBean, varV);
        } else if (genericType instanceof Class
                && ((Class<?>) genericType).isArray()
                && !((Class<?>) genericType).getComponentType().isPrimitive()) {
            emitArrayAssign(mv, pfi, ((Class<?>) genericType).getComponentType(), varBean, varV);
        } else {
            emitScalarAssign(mv, pfi, varBean, varV);
        }

        mv.visitLabel(lbSkip);
    }

    // ───────────────────────── 标量字段 ─────────────────────────

    /**
     * 标量字段赋值（primitive + 包装 + String + BigDecimal + Date + Object）。
     *
     * <p>栈契约：开始时栈空；输出时栈空。</p>
     */
    private void emitScalarAssign(MethodVisitor mv, JsonFieldInfo pfi, int varBean, int varV) {
        Class<?> rawType = pfi.field.getType();

        if (rawType == int.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getIntValue",
                    "(Ljava/lang/Object;)I", false);
            invokeSetterOrPutField(mv, pfi, "I");
        } else if (rawType == Integer.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getIntValue",
                    "(Ljava/lang/Object;)I", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                    "(I)Ljava/lang/Integer;", false);
            invokeSetterOrPutField(mv, pfi, "Ljava/lang/Integer;");
        } else if (rawType == long.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getLongValue",
                    "(Ljava/lang/Object;)J", false);
            invokeSetterOrPutField(mv, pfi, "J");
        } else if (rawType == Long.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getLongValue",
                    "(Ljava/lang/Object;)J", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                    "(J)Ljava/lang/Long;", false);
            invokeSetterOrPutField(mv, pfi, "Ljava/lang/Long;");
        } else if (rawType == boolean.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getBooleanValue",
                    "(Ljava/lang/Object;)Z", false);
            invokeSetterOrPutField(mv, pfi, "Z");
        } else if (rawType == Boolean.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getBooleanValue",
                    "(Ljava/lang/Object;)Z", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                    "(Z)Ljava/lang/Boolean;", false);
            invokeSetterOrPutField(mv, pfi, "Ljava/lang/Boolean;");
        } else if (rawType == float.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getFloatValue",
                    "(Ljava/lang/Object;)F", false);
            invokeSetterOrPutField(mv, pfi, "F");
        } else if (rawType == Float.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getFloatValue",
                    "(Ljava/lang/Object;)F", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf",
                    "(F)Ljava/lang/Float;", false);
            invokeSetterOrPutField(mv, pfi, "Ljava/lang/Float;");
        } else if (rawType == double.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getDoubleValue",
                    "(Ljava/lang/Object;)D", false);
            invokeSetterOrPutField(mv, pfi, "D");
        } else if (rawType == Double.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getDoubleValue",
                    "(Ljava/lang/Object;)D", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf",
                    "(D)Ljava/lang/Double;", false);
            invokeSetterOrPutField(mv, pfi, "Ljava/lang/Double;");
        } else if (rawType == String.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitTypeInsn(CHECKCAST, "java/lang/String");
            invokeSetterOrPutField(mv, pfi, "Ljava/lang/String;");
        } else if (rawType == java.math.BigDecimal.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitTypeInsn(CHECKCAST, "java/math/BigDecimal");
            invokeSetterOrPutField(mv, pfi, "Ljava/math/BigDecimal;");
        } else if (rawType == java.util.Date.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitTypeInsn(CHECKCAST, "java/util/Date");
            invokeSetterOrPutField(mv, pfi, "Ljava/util/Date;");
        } else if (rawType == Object.class) {
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            invokeSetterOrPutField(mv, pfi, "Ljava/lang/Object;");
        } else {
            // 兜底：CHECKCAST 到目标类型
            pushBean(mv, varBean);
            mv.visitVarInsn(ALOAD, varV);
            mv.visitTypeInsn(CHECKCAST, toInternalName(rawType.getName()));
            invokeSetterOrPutField(mv, pfi, getDescriptor(rawType));
        }
    }

    private void pushBean(MethodVisitor mv, int varBean) {
        mv.visitVarInsn(ALOAD, varBean);
    }

    /**
     * 栈契约：进入时栈顶是 bean（pushBean 之后），栈顶下一是 value（与 valDesc 类型匹配）。
     *        退出时栈空。
     */
    private void invokeSetterOrPutField(MethodVisitor mv, JsonFieldInfo pfi, String valDesc) {
        if (pfi.setMethod != null) {
            String rtnDesc = getDescriptor(pfi.setMethod.getGenericReturnType());
            mv.visitMethodInsn(INVOKEVIRTUAL, pojoName, pfi.setMethod.getName(),
                    "(" + valDesc + ")" + rtnDesc, false);
            if (!"V".equals(rtnDesc)) {
                mv.visitInsn(POP);
            }
        } else {
            mv.visitFieldInsn(PUTFIELD, pojoName, pfi.field.getName(), valDesc);
        }
    }

    // ───────────────────────── 嵌套 POJO 字段 ─────────────────────────

    /**
     * 嵌套 POJO 字段赋值：
     * <pre>
     *   if (v instanceof Map) {
     *       bean.field = nestDecoder.decode((Map<String, Object>) v);
     *   }
     * </pre>
     */
    private void emitNestedPojoAssign(MethodVisitor mv, JsonFieldInfo pfi, Class<?> nestCls,
                                      int varBean, int varV) {
        // if (v instanceof Map) {
        mv.visitVarInsn(ALOAD, varV);
        mv.visitTypeInsn(INSTANCEOF, MAP_NAME);
        Label lbSkip = new Label();
        mv.visitJumpInsn(IFEQ, lbSkip);

        // bean.field = JsonCodecRegister.instance()
        //                  .getMapBeanDecoder(NestType.class)
        //                  .decode((Map<String, Object>) v);
        mv.visitVarInsn(ALOAD, varBean);
        mv.visitMethodInsn(INVOKESTATIC, REGISTER_NAME, "instance",
                "()L" + REGISTER_NAME + ";", false);
        mv.visitLdcInsn(Type.getType(getDescriptor(nestCls)));
        mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getMapBeanDecoder",
                "(Ljava/lang/Class;)L" + IFACE_NAME + ";", false);
        mv.visitVarInsn(ALOAD, varV);
        mv.visitTypeInsn(CHECKCAST, MAP_NAME);
        mv.visitMethodInsn(INVOKEINTERFACE, IFACE_NAME, "decode",
                "(L" + MAP_NAME + ";)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, toInternalName(nestCls.getName()));
        invokeSetterOrPutField(mv, pfi, getDescriptor(nestCls));
        mv.visitLabel(lbSkip);
    }

    // ───────────────────────── List<T> 字段 ─────────────────────────

    /**
     * List<T> 字段赋值：
     * <pre>
     *   if (v instanceof List) {
     *       List<T> list = new ArrayList<>(((List) v).size());
     *       for (;;) {
     *           if (!it.hasNext()) { bean.field = list; break; }
     *           Object o = it.next();
     *           list.add(o == null ? null : (T) o);
     *       }
     *   }
     * </pre>
     *
     * <p>注意：用 {@code for(;;) + break} 模式而非 {@code while(hasNext)}。
     *     ASM {@code COMPUTE_FRAMES} 在 {@code GOTO loop_back → 赋值 → lbEnd}
     *     模式下会把"赋值"替换为 5 NOPs + athrow（frame 计算 bug）。
     *     把赋值放进 break 分支（紧跟 break 跳转））能避开这个 bug。</p>
     */
    private void emitListAssign(MethodVisitor mv, JsonFieldInfo pfi, java.lang.reflect.Type itemType,
                                int varBean, int varV) {
        // if (v instanceof List) {
        mv.visitVarInsn(ALOAD, varV);
        mv.visitTypeInsn(INSTANCEOF, LIST_NAME);
        Label lbEnd = new Label();
        mv.visitJumpInsn(IFEQ, lbEnd);

        // List<T> list = new ArrayList<>(((List) v).size());
        // 正确顺序：NEW → DUP → push args → INVOKESPECIAL <init>
        // INVOKESPECIAL 会消费 uninit receiver + int arg → 留下 initialized 引用
        mv.visitTypeInsn(NEW, ARRAY_LIST_NAME);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, varV);
        mv.visitTypeInsn(CHECKCAST, LIST_NAME);
        mv.visitMethodInsn(INVOKEINTERFACE, LIST_NAME, "size", "()I", true);
        mv.visitMethodInsn(INVOKESPECIAL, ARRAY_LIST_NAME, "<init>", "(I)V", false);
        int varList = 4;
        mv.visitVarInsn(ASTORE, varList);

        // java.util.Iterator it = ((List) v).iterator();
        mv.visitVarInsn(ALOAD, varV);
        mv.visitTypeInsn(CHECKCAST, LIST_NAME);
        mv.visitMethodInsn(INVOKEINTERFACE, LIST_NAME, "iterator",
                "()L" + ITERATOR_NAME + ";", true);
        int varIt = 5;
        mv.visitVarInsn(ASTORE, varIt);

        // for (;;) {
        Label lbLoop = new Label();
        mv.visitLabel(lbLoop);

        // if (!it.hasNext()) { bean.field = list; break; }
        mv.visitVarInsn(ALOAD, varIt);
        mv.visitMethodInsn(INVOKEINTERFACE, ITERATOR_NAME, "hasNext", "()Z", true);
        Label lbBody = new Label();
        mv.visitJumpInsn(IFNE, lbBody);

        // false 分支：赋值 + break
        mv.visitVarInsn(ALOAD, varBean);
        mv.visitVarInsn(ALOAD, varList);
        invokeSetterOrPutField(mv, pfi, "L" + LIST_NAME + ";");
        mv.visitJumpInsn(GOTO, lbEnd);

        mv.visitLabel(lbBody);

        // Object o = it.next();
        mv.visitVarInsn(ALOAD, varIt);
        mv.visitMethodInsn(INVOKEINTERFACE, ITERATOR_NAME, "next",
                "()Ljava/lang/Object;", true);
        int varO = 6;
        mv.visitVarInsn(ASTORE, varO);

        // list.add(o == null ? null : cast(o));
        mv.visitVarInsn(ALOAD, varList);
        mv.visitVarInsn(ALOAD, varO);
        Label lbNonNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbNonNull);
        mv.visitInsn(Opcodes.ACONST_NULL);
        Label lbAfterNonNull = new Label();
        mv.visitJumpInsn(GOTO, lbAfterNonNull);
        mv.visitLabel(lbNonNull);
        mv.visitVarInsn(ALOAD, varO);
        emitListItemCast(mv, itemType);
        mv.visitLabel(lbAfterNonNull);
        mv.visitMethodInsn(INVOKEINTERFACE, LIST_NAME, "add",
                "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitJumpInsn(GOTO, lbLoop);
        // }

        mv.visitLabel(lbEnd);
    }

    /**
     * List<T> 元素类型转换。
     *
     * <p>栈契约：进入时栈顶是 Object（已经被 null check 过）；退出时栈顶是 T 类型。</p>
     *
     * <p>POJO 元素走 {@code JsonCodecRegister.instance().getMapBeanDecoder(T.class)
     *     .decode((Map)v)} 递归路径 —— 元素仍是 Map（典型：{@code JsonObject}），需要
     *     递归解码成 T 实例。简单 wrapper / String 直接 CHECKCAST；primitive 走
     *     {@code JsonUtil.getXxxValue} 容错转换（Long → Integer 等）。</p>
     */
    private void emitListItemCast(MethodVisitor mv, java.lang.reflect.Type itemType) {
        if (isPojo(itemType)) {
            Class<?> nestCls = (Class<?>) itemType;
            // Stack: [..., o]
            // Save o → tmpSlot
            int tmpSlot = 7;
            mv.visitVarInsn(ASTORE, tmpSlot);                 // Stack: [...]
            // Re-load o, CHECKCAST to Map, save back
            mv.visitVarInsn(ALOAD, tmpSlot);
            mv.visitTypeInsn(CHECKCAST, MAP_NAME);
            mv.visitVarInsn(ASTORE, tmpSlot);                 // tmpSlot = (Map)o
            // decoder = JsonCodecRegister.instance().getMapBeanDecoder(T.class)
            mv.visitMethodInsn(INVOKESTATIC, REGISTER_NAME, "instance",
                    "()L" + REGISTER_NAME + ";", false);
            mv.visitLdcInsn(Type.getType(getDescriptor(nestCls)));
            mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getMapBeanDecoder",
                    "(Ljava/lang/Class;)L" + IFACE_NAME + ";", false);
            // decoder.decode((Map) o)
            mv.visitVarInsn(ALOAD, tmpSlot);
            mv.visitMethodInsn(INVOKEINTERFACE, IFACE_NAME, "decode",
                    "(L" + MAP_NAME + ";)Ljava/lang/Object;", true);
            // (T) result
            mv.visitTypeInsn(CHECKCAST, toInternalName(nestCls.getName()));
        } else if (itemType == String.class) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/String");
        } else if (itemType == Integer.class) {
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getIntValue",
                    "(Ljava/lang/Object;)I", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                    "(I)Ljava/lang/Integer;", false);
        } else if (itemType == Long.class) {
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getLongValue",
                    "(Ljava/lang/Object;)J", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                    "(J)Ljava/lang/Long;", false);
        } else if (itemType == Boolean.class) {
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getBooleanValue",
                    "(Ljava/lang/Object;)Z", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                    "(Z)Ljava/lang/Boolean;", false);
        } else if (itemType == Double.class) {
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getDoubleValue",
                    "(Ljava/lang/Object;)D", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf",
                    "(D)Ljava/lang/Double;", false);
        } else if (itemType == Float.class) {
            mv.visitMethodInsn(INVOKESTATIC, JSON_UTIL_NAME, "getFloatValue",
                    "(Ljava/lang/Object;)F", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf",
                    "(F)Ljava/lang/Float;", false);
        } else {
            mv.visitTypeInsn(CHECKCAST, toInternalName(itemType.getTypeName()));
        }
    }

    // ───────────────────────── 数组 T[] 字段 ─────────────────────────

    /**
     * 数组 T[] 字段赋值（反射 Array.newInstance）：
     * <pre>
     *   if (v instanceof List) {
     *       List tmp = (List) v;
     *       T[] arr = (T[]) Array.newInstance(T.class, tmp.size());
     *       for (int i = 0; ; i++) {
     *           if (i >= tmp.size()) { bean.field = arr; break; }
     *           arr[i] = (T) tmp.get(i);
     *       }
     *   }
     * </pre>
     *
     * <p>同样用 {@code for(;;) + break} 而非传统 {@code for(i<size)} —— 避开
     *     ASM {@code COMPUTE_FRAMES} 的"赋值后跟 GOTO" frame 计算 bug。</p>
     */
    private void emitArrayAssign(MethodVisitor mv, JsonFieldInfo pfi, Class<?> compType,
                                 int varBean, int varV) {
        // if (v instanceof List) {
        mv.visitVarInsn(ALOAD, varV);
        mv.visitTypeInsn(INSTANCEOF, LIST_NAME);
        Label lbEnd = new Label();
        mv.visitJumpInsn(IFEQ, lbEnd);

        // List tmp = (List) v;
        mv.visitVarInsn(ALOAD, varV);
        mv.visitTypeInsn(CHECKCAST, LIST_NAME);
        int varTmp = 4;
        mv.visitVarInsn(ASTORE, varTmp);

        // T[] arr = (T[]) Array.newInstance(T.class, tmp.size());
        mv.visitLdcInsn(Type.getType(getDescriptor(compType)));
        mv.visitVarInsn(ALOAD, varTmp);
        mv.visitMethodInsn(INVOKEINTERFACE, LIST_NAME, "size", "()I", true);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/reflect/Array", "newInstance",
                "(Ljava/lang/Class;I)Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, "[" + toInternalName(compType.getName()));
        int varArr = 5;
        mv.visitVarInsn(ASTORE, varArr);

        // for (int i = 0; ; i++) {
        mv.visitInsn(ICONST_0);
        int varI = 6;
        mv.visitVarInsn(ISTORE, varI);
        Label lbTop = new Label();
        mv.visitLabel(lbTop);
        // if (i >= tmp.size()) { bean.field = arr; break; }
        mv.visitVarInsn(ILOAD, varI);
        mv.visitVarInsn(ALOAD, varTmp);
        mv.visitMethodInsn(INVOKEINTERFACE, LIST_NAME, "size", "()I", true);
        Label lbBody = new Label();
        mv.visitJumpInsn(IF_ICMPLT, lbBody);
        // false 分支：赋值 + break
        mv.visitVarInsn(ALOAD, varBean);
        mv.visitVarInsn(ALOAD, varArr);
        invokeSetterOrPutField(mv, pfi, "[" + toInternalName(compType.getName()));
        mv.visitJumpInsn(GOTO, lbEnd);
        mv.visitLabel(lbBody);
        // arr[i] = (T) tmp.get(i);
        mv.visitVarInsn(ALOAD, varArr);
        mv.visitVarInsn(ILOAD, varI);
        mv.visitVarInsn(ALOAD, varTmp);
        mv.visitVarInsn(ILOAD, varI);
        mv.visitMethodInsn(INVOKEINTERFACE, LIST_NAME, "get",
                "(I)Ljava/lang/Object;", true);
        emitListItemCast(mv, compType);
        mv.visitInsn(Opcodes.AASTORE);
        // i++;
        mv.visitIincInsn(varI, 1);
        mv.visitJumpInsn(GOTO, lbTop);
        // }

        mv.visitLabel(lbEnd);
    }

    // ───────────────────────── 桥接方法 ─────────────────────────

    /**
     * 泛型擦除桥接：基类 {@link AbstractMapBeanDecoder} 实现 {@code MapBeanDecoder<Object>}
     *     要求 {@code Object decode(Map)}。生成类实现 {@code MapBeanDecoder<T>} 提供
     *     {@code T decode(Map)} —— 字节码层面需要桥接方法满足基类契约。
     */
    private void visitDecodeBridgeMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "decode",
                "(L" + MAP_NAME + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, decoderName, "decode",
                "(L" + MAP_NAME + ";)L" + pojoName + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }
}
