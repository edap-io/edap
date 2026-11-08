import io.edap.s3.model.GetStream;
import io.edap.s3.model.ListOptions;
import io.edap.s3.model.PutStream;
import io.edap.s3.store.disk.DiskBucketStore;
import io.edap.s3.store.disk.DiskObjectStore;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 定点 IO 对比(修后版)—— 与 {@link S3BenchIoOld} 等价:先把 body 全读一遍把
 * page cache 焐热,再测 head / get。
 *
 * <p>修后 get 不再读 .meta(元数据在内存索引里)⇒ 焐热阶段也只需要 body。
 */
public class S3BenchIoNew {

    static byte[] PAYLOAD = new byte[1024];
    static PutStream ps(byte[] b) {
        return new PutStream(new ByteArrayInputStream(b), "application/octet-stream",
                Collections.emptyMap(), b.length, null);
    }
    static double med(List<Double> v) {
        Collections.sort(v);
        return v.get(v.size() / 2);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        int N = Integer.parseInt(args[1]);
        if (Files.exists(root)) del(root);
        Files.createDirectories(root);
        DiskBucketStore bs = new DiskBucketStore(root);
        bs.create("b");
        DiskObjectStore os = new DiskObjectStore(bs);
        for (int i = 0; i < N; i++) os.put("b", String.format("obj-%08d.dat", i), ps(PAYLOAD));

        // ---- 焐热:body 全读一遍(修后不再有 .meta 要读)----
        Path dir = root.resolve("b");
        long t0 = System.nanoTime();
        long bytes = 0;
        for (int i = 0; i < N; i++) {
            bytes += Files.readAllBytes(dir.resolve(String.format("obj-%08d.dat", i))).length;
        }
        double warmMs = (System.nanoTime() - t0) / 1e6;

        List<Double> lh = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long t = System.nanoTime();
            os.head("b", String.format("obj-%08d.dat", i * 37 % N));
            lh.add((System.nanoTime() - t) / 1e6);
        }
        List<Double> lg = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long t = System.nanoTime();
            GetStream g = os.get("b", String.format("obj-%08d.dat", i * 37 % N), 0, -1);
            try (java.io.InputStream in = g.content()) { in.readAllBytes(); }
            lg.add((System.nanoTime() - t) / 1e6);
        }
        System.out.printf("NEW  N=%d  warm=%.0fms (%d bytes)  head=%.4f ms  get=%.4f ms%n",
                N, warmMs, bytes, med(lh), med(lg));
    }

    static void del(Path p) throws Exception {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (Exception e) {} });
        }
    }
}
