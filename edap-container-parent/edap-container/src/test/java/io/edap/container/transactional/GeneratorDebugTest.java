package io.edap.container.transactional;

import io.edap.tx.annotation.Transactional;
import io.edap.tx.isolation.Isolation;
import io.edap.tx.propagation.Propagation;
import org.junit.jupiter.api.Test;

import java.util.List;

/** DEBUG: just dump generated wrapper class to /tmp without loading. */
public class GeneratorDebugTest {

    public interface If {
        @Transactional(propagation = Propagation.REQUIRED)
        void create(int x);
    }

    @Test
    void dump() throws Exception {
        java.lang.reflect.Method m = If.class.getMethod("create", int.class);
        TransactionalClassGenerator.MethodSpec spec = new TransactionalClassGenerator.MethodSpec(
                m, m.getAnnotation(Transactional.class),
                m.getAnnotation(io.edap.tx.annotation.ManualTransaction.class));
        TransactionalClassGenerator gen = new TransactionalClassGenerator(If.class, List.of(spec));
        byte[] bytes = gen.generate();
        java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/tx-wrapper.class"), bytes);
        System.out.println("DUMPED: " + bytes.length + " bytes");
    }
}