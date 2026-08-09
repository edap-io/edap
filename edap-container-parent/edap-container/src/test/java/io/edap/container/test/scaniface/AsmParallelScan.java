package io.edap.container.test.scaniface;


import org.objectweb.asm.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@BenchmarkMode({Mode.AverageTime})
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
//测量次数,每次测量的持续时间
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AsmParallelScan {

    public static class AnnoData {
        public final String type;                                 // FQCN
        public final Map<String, Object> values = new LinkedHashMap<>();

        public AnnoData(String type) { this.type = type; }

        @Override
        public String toString() {
            return "@" + type + (values.isEmpty() ? "()" : values);
        }
    }

    private static final String ANNOTATION_FQCN = "io.edap.protobuf.annotation.ProtoService";
    private static final String JAR_PATH =
            "/Users/louis/ai-agent/mobile-stylists/stylists-frontend-parent/" +
                    "stylists-frontend-api/target/stylists-frontend-api-1.0-SNAPSHOT.jar";

    private List<JarEntry> entries = new ArrayList<>();

    @Setup(Level.Trial)
    public void setup() throws IOException {
        // 只在每次 fork 时跑一次：枚举所有 .class entry
        try (JarFile jar = new JarFile(JAR_PATH)) {
            jar.stream()
                    .filter(e -> !e.isDirectory() && e.getName().endsWith(".class"))
                    .forEach(entries::add);
        }
    }

    @Benchmark
    public List<AnnoData> scan() throws IOException {
        // 每线程独立 JarFile
        List<AnnoData> result = Collections.synchronizedList(new ArrayList<>());

        entries.parallelStream().forEach(entry -> {
            try (JarFile jar = new JarFile(JAR_PATH)) {   // 每线程开一次
                byte[] bytes;
                try (InputStream is = jar.getInputStream(entry)) {
                    bytes = is.readAllBytes();
                }
                ClassReader reader = new ClassReader(bytes);
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    AnnoData data;
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        String typeName = Type.getType(desc).getClassName();
                        if (!ANNOTATION_FQCN.equals(typeName)) return null;
                        data = new AnnoData(typeName);
                        result.add(data);
                        return collectInto(data.values);
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        return result;
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
                .include(AsmParallelScan.class.getName())
                .build();
        new Runner(opt).run();
//        AsmScan scan = new AsmScan();
//        scan.scan();
    }
}
