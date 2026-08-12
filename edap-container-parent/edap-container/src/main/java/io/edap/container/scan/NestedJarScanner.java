package io.edap.container.scan;

import io.edap.container.mw.*;
import io.edap.launcher.NestedJarFile;
import io.edap.microservice.annotation.Bean;
import io.edap.microservice.annotation.MicroServiceBean;
import io.edap.protobuf.annotation.ProtoService;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static io.edap.container.utils.JarUtils.scanMavenInfo;
import static io.edap.util.AsmUtil.toLangName;

public class NestedJarScanner {

    private NestedJarFile nestedJarFile;

    public NestedJarScanner(NestedJarFile nestedJarFile) {
        this.nestedJarFile = nestedJarFile;
    }

    public DeployComponent scan() {
        DeployComponent dc = new DeployComponent();
        NestedJarFile          njar  = nestedJarFile;
        Set<String>            names = njar.entryNames();
        List<ProtoServiceData> infoList = new ArrayList<>();
        Map<String, ServiceMeta> serviceMetaMap = dc.getServiceMetaMap();
        for (String name : names) {
            if (name.startsWith("META-INF") && name.endsWith("/pom.properties")) {
                dc.setMavenInfo(scanMavenInfo(nestedJarFile, name));
            }
            if (name.endsWith(".class")) {
                try (InputStream in = njar.getInputStream(name)) {
                    ProtoServiceData psi = visitProtoService(in, serviceMetaMap);
                    if (psi != null) {
                        infoList.add(psi);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        dc.setProtoServiceInfos(infoList);

        return dc;
    }

    private ProtoServiceData visitProtoService(InputStream in, Map<String, ServiceMeta> serviceMetaMap) throws IOException {
        ProtoServiceData psi        = new ProtoServiceData();
        String           protoAnn      = ProtoService.class.getName();
        String           msBeanAnn     = MicroServiceBean.class.getName();
        String           beanAnn       = Bean.class.getName();
        ClassReader      reader        = new ClassReader(in);
        List<ProtoMethodData> methodInfos = new ArrayList<>();

        Boolean[] isProtoServices = new Boolean[]{null};
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
                String typeName = Type.getType(desc).getClassName();
                AnnoData d = new AnnoData(typeName);
                String className = toLangName(reader.getClassName());
                ServiceMeta serviceMeta = serviceMetas[0];
                if (serviceMeta != null) {
                    serviceMeta.putAnnoData(typeName, d);
                }
                if (protoAnn.equals(typeName)) {
                    psi.setTypeName(className);
                    psi.setMethodInfos(methodInfos);
                    psi.getAnnoDatas().add(d);
                    isProtoServices[0] = true;
                    return collectInto(d.getValues());
                } else if (msBeanAnn.equals(typeName)) {
                    serviceMetaMap.put(className, serviceMeta);
                    return collectInto(d.getValues());
                } else if (beanAnn.equals(typeName)) {
                    serviceMetaMap.put(className, serviceMeta);
                    return collectInto(d.getValues());
                }
                if (serviceMeta != null) {
                    return collectInto(d.getValues());
                }

                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (isProtoServices[0] != null) {
                    ProtoMethodData pmi = new ProtoMethodData();
                    pmi.setExceptions(ex);
                    pmi.setName(name);
                    pmi.setAccess(access);
                    String paramType;
                    String respType;
                    int index = desc.indexOf(')');
                    if (index != -1) {
                        paramType = Type.getType(desc.substring(1, index)).getClassName();
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
                return null;  // 不访问方法体
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (isProtoServices[0] != null) {
            return psi;
        }

        return null;
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

}
