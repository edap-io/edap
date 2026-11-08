import io.edap.s3.model.GetStream;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.PutStream;
import io.edap.s3.store.disk.DiskBucketStore;
import io.edap.s3.store.disk.DiskObjectStore;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 定点 IO 对比(修前版)—— 先把 body + .meta 全部读一遍把 page cache 焐热,
 * 再测 head / get,排除「缓存冷热」这个混淆变量。
 */
public class S3BenchIoOld {

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

        // ---- 焐热:body + .meta 全读一遍 ----
        Path dir = root.resolve("b");
        long t0 = System.nanoTime();
        long bytes = 0;
        for (int i = 0; i < N; i++) {
            String k = String.format("obj-%08d.dat", i);
            bytes += Files.readAllBytes(dir.resolve(k)).length;
            bytes += Files.readAllBytes(dir.resolve(k + ".meta")).length;
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
        System.out.printf("OLD  N=%d  warm=%.0fms (%d bytes)  head=%.4f ms  get=%.4f ms%n",
                N, warmMs, bytes, med(lh), med(lg));
    }

    static void del(Path p) throws Exception {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (Exception e) {} });
        }
    }
}
