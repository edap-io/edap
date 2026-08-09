package io.edap.container.test.scaniface;


import org.objectweb.asm.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

@BenchmarkMode({Mode.AverageTime})
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
//测量次数,每次测量的持续时间
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AsmScan {

    public static class AnnoData {
        public final String type;                                 // FQCN
        public final Map<String, Object> values = new LinkedHashMap<>();

        public AnnoData(String type) { this.type = type; }

        @Override
        public String toString() {
            return "@" + type + (values.isEmpty() ? "()" : values);
        }
    }

    @Benchmark
    public void scan() throws IOException {
        String pkg = "com";
        String routeAnnotation = "io.edap.protobuf.annotation.ProtoService";
        String jarPath = "/Users/louis/ai-agent/mobile-stylists/stylists-frontend-parent/" +
                "stylists-frontend-api/target/stylists-frontend-api-1.0-SNAPSHOT.jar";
        try (JarFile jar = new JarFile(jarPath)) {
            jar.stream()
                    //.parallel()
                    .forEach(e -> {
                if (e !=null && !e.isDirectory() && e.getName().endsWith(".class")) {
                    ClassReader reader = null;
                    AnnoData[] result = {null};
                    try (InputStream is = jar.getInputStream(e)) {
                        reader = new ClassReader(is);
                        reader.accept(new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                String typeName = Type.getType(desc).getClassName();
                                if (!routeAnnotation.equals(typeName)) {
                                    return null;   // 不是我们要的注解，跳过
                                }
                                AnnoData data = new AnnoData(typeName);
                                result[0] = data;
                                return collectInto(data.values);
                            }
                        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        if (result[0] != null) {
                            for (Map.Entry<String, Object> entry : result[0].values.entrySet()) {

                            }
                        }
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    } finally {

                    }
                }
            });
        }
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

                    @Override public void visit(String n, Object v) {
                        list.add(normalize(v));
                    }

                    @Override public void visitEnum(String n, String desc, String v) {
                        list.add(v);
                    }

                    @Override public AnnotationVisitor visitAnnotation(String n, String desc) {
                        Map<String, Object> nested = new LinkedHashMap<>();
                        nested.put("@type", Type.getType(desc).getClassName());
                        list.add(nested);
                        return collectInto(nested);
                    }

                    @Override public AnnotationVisitor visitArray(String n) {
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

    public static void main(String[] args) throws RunnerException, IOException {
        Options opt = new OptionsBuilder()
                .include(AsmScan.class.getName())
                .build();
        new Runner(opt).run();
//        AsmScan scan = new AsmScan();
//        scan.scan();
    }
}
