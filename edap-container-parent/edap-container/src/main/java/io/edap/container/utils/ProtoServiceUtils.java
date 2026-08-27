package io.edap.container.utils;

import io.edap.container.mw.*;
import io.edap.microservice.annotation.Bean;
import io.edap.microservice.annotation.Configuration;
import io.edap.microservice.annotation.MicroServiceBean;
import io.edap.protobuf.annotation.ProtoService;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static io.edap.util.AsmUtil.toLangName;

public class ProtoServiceUtils {

    private ProtoServiceUtils() {}

    /**
     * 目标注解的"匹配种类"——比单纯 boolean 更精细，告诉调用方"命中了哪个 +
     * 是否需要扫方法体"。{@link #detectTargetKind} 一次扫描返回这个，
     * 调用方按 {@link #requiresMethodScan} 分支处理。
     */
    public enum TargetKind {
        /** 类上标了 {@code @ProtoService}——需要扫方法体（rpc 方法生成 Handler） */
        PROTO_SERVICE,
        /** 类上标了 {@code @MicroServiceBean}——纯类级，无需扫方法 */
        MICRO_SERVICE,
        /** 类上标了 {@code @Bean}——纯类级，无需扫方法 */
        BEAN,
        /** 类上标了 {@code @Configuration}——纯类级（{@code @Bean} 方法级解析待运行时层落地） */
        CONFIGURATION,
        /** 无任何目标注解 */
        NONE;

        /**
         * 该命中是否需要扫方法体。
         * <ul>
         *   <li>{@code true}：
         *     <ul>
         *       <li>{@link #PROTO_SERVICE} —— rpc method → {@code ProtoMethodData} → Handler</li>
         *       <li>{@link #CONFIGURATION} —— 找方法上的 {@code @Bean} 工厂方法，
         *           Phase 1 扩展生成 {@code BeanDef.factoryMethod}</li>
         *     </ul>
         *   </li>
         *   <li>{@code false}：{@link #MICRO_SERVICE} / {@link #BEAN} —— 纯类级，
         *       业务方法不由目标注解驱动解析</li>
         * </ul>
         */
        public boolean requiresMethodScan() {
            return this == PROTO_SERVICE || this == CONFIGURATION;
        }
    }

    /**
     * 目标注解全限定名集合（类级）——只有命中这些的类才走完整解析。
     * 部署期 EAR 内的"普通业务类"（不含目标注解的）是绝大多数，
     * {@link #detectTargetKind} 早退出能省下大头开销。
     *
     * <p>两套形式并存：</p>
     * <ul>
     *   <li>{@code PROTO_ANN} 等 className 形式 —— 完整解析路径需要写 AnnoData(typeName)，
     *       必须用裸类名（{@code io.edap.microservice.annotation.Bean}）</li>
     *   <li>{@code PROTO_DESC} 等 descriptor 形式 —— {@link #detectTargetKind} 早退出
     *       路径直接拿 ClassReader 给的 descriptor 字符串（{@code Lio/edap/microservice/annotation/Bean;}），
     *       不调 {@code Type.getType(...).getClassName()} 即可比对，省一次对象分配 + 字符串裁切</li>
     * </ul>
     */
    private static final String PROTO_ANN   = ProtoService.class.getName();
    private static final String MS_BEAN_ANN = MicroServiceBean.class.getName();
    private static final String BEAN_ANN    = Bean.class.getName();
    private static final String CONF_ANN    = Configuration.class.getName();

    /** descriptor 形式：{@code Lio/edap/microservice/annotation/Bean;} 风格，0 分配比对。 */
    private static final String PROTO_DESC   = Type.getDescriptor(ProtoService.class);
    private static final String MS_BEAN_DESC = Type.getDescriptor(MicroServiceBean.class);
    private static final String BEAN_DESC    = Type.getDescriptor(Bean.class);
    private static final String CONF_DESC    = Type.getDescriptor(Configuration.class);

    /**
     * 快速探测：类上命中了哪些目标注解（仅匹配 descriptor，不解析值）。
     * 返回的 {@link TargetKind} 按优先级取唯一一个——调用方按
     * {@link TargetKind#requiresMethodScan()} 决定是否扫方法体。
     *
     * <p>实现：一次 ClassReader.accept + 极简 ClassVisitor，每个 visitAnnotation
     * 直接用 descriptor 字符串比对白名单（不调 {@code Type.getType(...).getClassName()}，
     * 避免每次新建 Type 对象 + 字符串裁切）。对"无目标注解"的普通业务类几乎 0 开销——
     * 只读 class 字节码的 annotations attribute 列表 + 至多 4 次 String.equals 比对。</p>
     *
     * <p><b>互斥校验</b>：{@code @Configuration}（配置类语义）与 {@code @ProtoService} /
     * {@code @MicroServiceBean} / {@code @Bean}（业务类语义）互斥——同一类上同时标
     * {@code @Configuration} + 任意业务注解 → 抛 {@link IllegalStateException}。
     * 业务注解之间的组合（{@code @ProtoService} + {@code @Bean} 等）允许。
     * 校验放在最早期：早退出路径命中即抛，调用方 fail-fast。</p>
     */
    public static TargetKind detectTargetKind(ClassReader reader) {
        // [proto, msBean, bean, conf] —— 用 4 元素数组扁平表达"哪些命中了"
        boolean[] present = new boolean[4];
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (PROTO_DESC.equals(descriptor))         present[0] = true;
                else if (MS_BEAN_DESC.equals(descriptor))  present[1] = true;
                else if (BEAN_DESC.equals(descriptor))     present[2] = true;
                else if (CONF_DESC.equals(descriptor))     present[3] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // @Configuration 互斥校验（与任意业务注解共存即抛）
        if (present[3] && (present[0] || present[1] || present[2])) {
            String conflict = present[0] ? "@ProtoService"
                            : present[1] ? "@MicroServiceBean"
                                         : "@Bean";
            throw new IllegalStateException(
                    "@Configuration cannot coexist with " + conflict
                            + " on class " + reader.getClassName());
        }

        // 按优先级返回（CONF 单独走自己分支——命中即返回，且互斥校验已保证无业务注解共存）
        if (present[3]) return TargetKind.CONFIGURATION;
        if (present[0]) return TargetKind.PROTO_SERVICE;
        if (present[1]) return TargetKind.MICRO_SERVICE;
        if (present[2]) return TargetKind.BEAN;
        return TargetKind.NONE;
    }

    public static ProtoServiceData visitProtoService(InputStream in,
                                                     Map<String, ServiceMeta> serviceMetaMap,
                                                     Map<String, ConfigurationMetaData> configurationMetaDataMap)
            throws IOException {
        ClassReader reader = new ClassReader(in);
        TargetKind kind = detectTargetKind(reader);
        if (kind == TargetKind.NONE) {
            return null;
        }
        // 按是否需要扫方法体分派：PROTO/CONF → parseWithMethods；BEAN/MS_BEAN → parseWithoutMethods
        if (kind.requiresMethodScan()) {
            return parseWithMethods(reader, serviceMetaMap, configurationMetaDataMap);
        }
        parseWithoutMethods(reader, serviceMetaMap);
        return null;
    }

    /**
     * 解析 {@code @ProtoService} / {@code @Configuration} 类 —— 需扫方法体。
     *
     * <p>visitAnnotation 同时处理 {@code @ProtoService} / {@code @Configuration} / {@code @Bean} /
     * {@code @MicroServiceBean}：{@code detectTargetKind} 的互斥校验保证 {@code @Configuration}
     * 不会与 {@code @Bean} / {@code @MicroServiceBean} 共存，但 {@code @ProtoService} 可以与
     * {@code @Bean} / {@code @MicroServiceBean} 共存（都要注册到 serviceMetaMap）。</p>
     *
     * <p>visitMethod 始终扫：进到此函数时 kind 必为 PROTO_SERVICE 或 CONFIGURATION，二者都需要
     * 方法级数据（rpc 方法 → Handler / {@code @Bean} 工厂方法 → BeanDef.factoryMethod）。</p>
     */
    private static ProtoServiceData parseWithMethods(ClassReader reader,
                                                    Map<String, ServiceMeta> serviceMetaMap,
                                                    Map<String, ConfigurationMetaData> configurationMetaDataMap) {
        ProtoServiceData psi = new ProtoServiceData();
        ConfigurationMetaData configurationMd = new ConfigurationMetaData();
        List<ProtoMethodData> methodInfos = new ArrayList<>();

        Boolean[] isProtoServices = new Boolean[]{null};
        Boolean[] isConfigurations = new Boolean[]{null};
        ServiceMeta[] serviceMetas   = new ServiceMeta[]{null};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {

            @Override
            public void visit(int version, int access, String name,
                              String signature, String superName,
                              String[] interfaces) {
                String className = Type.getObjectType(name).getClassName();
                ServiceMeta serviceMeta = new ServiceMeta();
                serviceMeta.setClassName(className);
                serviceMeta.setSuperName(superName != null
                        ? Type.getObjectType(superName).getClassName() : null);
                serviceMeta.setInterfaceList(interfaces != null
                        ? Arrays.asList(interfaces) : new ArrayList<>());
                serviceMetas[0] = serviceMeta;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                String typeName;
                if (PROTO_DESC.equals(desc))         typeName = PROTO_ANN;
                else if (MS_BEAN_DESC.equals(desc))  typeName = MS_BEAN_ANN;
                else if (BEAN_DESC.equals(desc))     typeName = BEAN_ANN;
                else if (CONF_DESC.equals(desc))     typeName = CONF_ANN;
                else return null;

                String className = toLangName(reader.getClassName());
                ServiceMeta serviceMeta = serviceMetas[0];

                AnnoData d = new AnnoData(typeName);
                if (serviceMeta != null) {
                    serviceMeta.putAnnoData(typeName, d);
                }
                configurationMd.getAnnoDatas().add(d);

                if (PROTO_ANN.equals(typeName)) {
                    if (isProtoServices[0] == null) {
                        psi.setTypeName(className);
                        psi.setMethodInfos(methodInfos);
                        isProtoServices[0] = true;
                    }
                    psi.getAnnoDatas().add(d);
                } else if (MS_BEAN_ANN.equals(typeName) || BEAN_ANN.equals(typeName)) {
                    serviceMetaMap.put(className, serviceMeta);
                } else { // CONF_ANN
                    if (isConfigurations[0] == null) {
                        configurationMd.setTypeName(className);
                        configurationMd.setMethodInfos(methodInfos);
                        isConfigurations[0] = true;
                    }
                }

                return collectInto(d.getValues());
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                ProtoMethodData pmi = new ProtoMethodData();
                pmi.setExceptions(ex);
                pmi.setName(name);
                pmi.setAccess(access);
                String paramType;
                String respType;
                int index = desc.indexOf(')');
                if (index != -1) {
                    String ptStr = desc.substring(1, index);
                    if (ptStr.trim().length() > 0) {
                        paramType = Type.getType(ptStr).getClassName();
                    } else {
                        paramType = "";
                    }
                    respType  = Type.getType(desc.substring(index + 1)).getClassName();
                } else {
                    paramType = "";
                    respType  = "";
                }
                pmi.setParamType(paramType);
                pmi.setRespType(respType);
                pmi.setInterfaceName(toLangName(reader.getClassName()));
                methodInfos.add(pmi);

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        String typeName = Type.getType(desc).getClassName();
                        AnnoData d = new AnnoData(typeName);
                        pmi.getAnnoDatas().add(d);
                        return collectInto(d.getValues());
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (isConfigurations[0] != null) {
            configurationMetaDataMap.put(reader.getClassName(), configurationMd);
        }
        return isProtoServices[0] != null ? psi : null;
    }

    /**
     * 解析 {@code @Bean} / {@code @MicroServiceBean} 类 —— 不扫方法体（纯类级注册）。
     *
     * <p>visitAnnotation 只处理 {@code @Bean} / {@code @MicroServiceBean}：进到此函数时
     * {@code detectTargetKind} 已保证类上无 {@code @ProtoService} / {@code @Configuration}
     * （否则会走 {@link #parseWithMethods}）。</p>
     *
     * <p>不重写 visitMethod —— ClassVisitor 基类默认返回 null，ASM 不回调方法体。</p>
     */
    private static void parseWithoutMethods(ClassReader reader,
                                            Map<String, ServiceMeta> serviceMetaMap) {
        ServiceMeta[] serviceMetas = new ServiceMeta[]{null};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {

            @Override
            public void visit(int version, int access, String name,
                              String signature, String superName,
                              String[] interfaces) {
                String className = Type.getObjectType(name).getClassName();
                ServiceMeta serviceMeta = new ServiceMeta();
                serviceMeta.setClassName(className);
                serviceMeta.setSuperName(superName != null
                        ? Type.getObjectType(superName).getClassName() : null);
                serviceMeta.setInterfaceList(interfaces != null
                        ? Arrays.asList(interfaces) : new ArrayList<>());
                serviceMetas[0] = serviceMeta;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                // 只接受 BEAN / MS_BEAN descriptor（detectTargetKind 已保证类上无 PROTO/CONF）
                String typeName;
                if (BEAN_DESC.equals(desc))          typeName = BEAN_ANN;
                else if (MS_BEAN_DESC.equals(desc))  typeName = MS_BEAN_ANN;
                else return null;

                String className = toLangName(reader.getClassName());
                ServiceMeta serviceMeta = serviceMetas[0];

                AnnoData d = new AnnoData(typeName);
                if (serviceMeta != null) {
                    serviceMeta.putAnnoData(typeName, d);
                }
                serviceMetaMap.put(className, serviceMeta);

                return collectInto(d.getValues());
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /**
     * 返回一个 AnnotationVisitor，把解析结果写入 target map。
     * 关键：把每个 visit 方法里的 name==null（注解里的隐式 value）映射成 "value" key。
     */
    private static AnnotationVisitor collectInto(Map<String, Object> target) {
        return new AnnotationVisitor(Opcodes.ASM9) {

            @Override
            public void visit(String name, Object value) {
                // value 可能是: 包装基本类型 / String / Type (即 Class) / 数组元素
                // enum 也会走这里（value 是 String 名字）或走 visitEnum
                target.put(name == null ? "value" : name, normalize(value));
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                // enum 值：descriptor 是 Lcom/example/Mode;，value 是字面名字
                target.put(name == null ? "value" : name, value);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                // 嵌套注解：递归建一个 map
                Map<String, Object> nested = new LinkedHashMap<>();
                nested.put("@type", Type.getType(descriptor).getClassName());
                target.put(name == null ? "value" : name, nested);
                return collectInto(nested);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                // 数组：连续 visit 一串元素
                List<Object> list = new ArrayList<>();
                target.put(name == null ? "value" : name, list);
                return new AnnotationVisitor(Opcodes.ASM9) {

                    @Override
                    public void visit(String n, Object v) {
                        list.add(normalize(v));
                    }

                    @Override
                    public void visitEnum(String n, String desc, String v) {
                        list.add(v);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String n, String desc) {
                        Map<String, Object> nested = new LinkedHashMap<>();
                        nested.put("@type", Type.getType(desc).getClassName());
                        list.add(nested);
                        return collectInto(nested);
                    }

                    @Override
                    public AnnotationVisitor visitArray(String n) {
                        // 不处理数组里再嵌数组（罕见）；这里返回 null 让 ASM 跳过
                        return null;
                    }
                };
            }

            @Override
            public void visitEnd() {
                // 可选：所有 visit 完成后做一些收尾工作
            }
        };
    }

    /** 把 ASM 给的值做轻度规范化（Type → 类名字符串，避免强加载） */
    private static Object normalize(Object v) {
        if (v instanceof Type) {
            return ((Type) v).getClassName();   // 例如 "java.lang.StringBuilder"
        }
        return v;   // String / Integer / Boolean / ... 原样
    }

    public static void filterCapabilityMethod(List<ProtoServiceData> protoServices,
                                              Map<String, ProtoMethodData> capabilityMethods,
                                              String... annotationTypeNames) {
        if (CollectionUtils.isEmpty(protoServices)) {
            return;
        }
        for (ProtoServiceData psd : protoServices) {
            List<ProtoMethodData> ms = psd.getMethodInfos();
            if (ms == null || ms.isEmpty()) {
                continue;
            }
            for (ProtoMethodData pmd : ms) {
                filterAnnoType(pmd, capabilityMethods, annotationTypeNames);
            }
        }
    }

    private static void filterAnnoType(ProtoMethodData pmd,
                                       Map<String, ProtoMethodData> capabilityMethods,
                                       String... annotationTypeNames) {
        List<AnnoData> annoDatas = pmd.getAnnoDatas();
        for (AnnoData annoData : annoDatas) {
            String type = annoData.getType();
            if (StringUtil.isEmpty(type)) {
                continue;
            }
            for (String name : annotationTypeNames) {
                if (type.equals(name)) {
                    capabilityMethods.put(name, pmd);
                }
            }
        }
    }

}
