package io.edap.json.test;

import eje.com.jsoniter.benchmark.with_map_field.TestObjectEncoder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * @date : 2019/12/25
 */
public class Asm {
    public static void main(String[] args) throws FileNotFoundException, IOException {
        int flags = ClassReader.SKIP_DEBUG;

        MethodVisitor mv;

        System.out.println("short Max=" + Float.MAX_VALUE);

//        String clsPath = "/Users/louis/NetBeansProjects/edap/edap-json/target/test-classes/" +
//                "io/edap/json/test/DemoPojoDecoder.class";
        String clsPath = MapEncoder_223.class.getName();
        ClassReader cr;
        if (clsPath.endsWith(".class") || clsPath.indexOf('\\') > -1
                || clsPath.indexOf('/') > -1) {
            cr = new ClassReader(new FileInputStream(clsPath));
        } else {
            cr = new ClassReader(clsPath);
        }
        cr.accept(new TraceClassVisitor(null, new ASMifier(), new PrintWriter(
                System.out)), flags);
    }
}