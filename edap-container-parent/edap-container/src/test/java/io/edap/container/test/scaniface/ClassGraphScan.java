package io.edap.container.test.scaniface;

import io.github.classgraph.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.AverageTime})
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
//测量次数,每次测量的持续时间
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ClassGraphScan {

    public ClassGraphScan() {

    }

    @Benchmark
    public void scan() {
        String pkg = "com";
        String routeAnnotation = "io.edap.protobuf.annotation.ProtoService";
        try (ScanResult scanResult =
                     new ClassGraph()
                             .overrideClasspath("/Users/louis/ai-agent/mobile-stylists/stylists-frontend-parent/" +
                                     "stylists-frontend-api/target/stylists-frontend-api-1.0-SNAPSHOT.jar")
                             .enableAnnotationInfo()         // Scan classes, methods, fields, annotations
                             .acceptPackages(pkg)     // Scan com.xyz and subpackages (omit to scan all packages)
                             .scan()) {
            // Start the scan
            for (ClassInfo routeClassInfo : scanResult.getClassesWithAnnotation(routeAnnotation)) {
                AnnotationInfo routeAnnotationInfo = routeClassInfo.getAnnotationInfo(routeAnnotation);
                List<AnnotationParameterValue> routeParamVals = routeAnnotationInfo.getParameterValues();
                // @com.xyz.Route has one required parameter
                String route = (String) routeParamVals.get(0).getValue();
                //System.out.println(routeClassInfo.getName() + " is annotated with route " + route);
            }
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ClassGraphScan.class.getName())
                .build();
        new Runner(opt).run();
    }
}
