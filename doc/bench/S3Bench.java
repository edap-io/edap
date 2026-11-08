import io.edap.s3.model.GetStream;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.PutStream;
import io.edap.s3.store.disk.DiskBucketStore;
import io.edap.s3.store.disk.DiskObjectStore;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class S3Bench {

    static byte[] PAYLOAD = new byte[1024];
    static PutStream ps(byte[] b) {
        return new PutStream(new ByteArrayInputStream(b), "application/octet-stream",
                Collections.emptyMap(), b.length, null);
    }
    static double med(List<Double> v) {
        Collections.sort(v);
        return v.get(v.size() / 2);
    }
    static long countEntries(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) { return s.count(); }
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : "/tmp/s3bench/data");
        int[] Ns = {1000, 10000, 50000, 100000};
        System.out.println("root=" + root + "  fs=" + Files.getFileStore(root).type());
        System.out.println(String.format("%-8s %-9s %-11s %-11s %-11s %-11s %-11s %-11s %-7s",
                "N", "entries", "LIST1k", "LISTmiss", "LISTall", "put", "head", "get", "page2==p1"));
        for (int N : Ns) {
            Path bdir = root.resolve("b" + N);
            if (Files.exists(bdir)) { deleteTree(bdir); }
            DiskBucketStore bs = new DiskBucketStore(root);
            bs.create("b" + N);
            DiskObjectStore os = new DiskObjectStore(bs);

            // ---- 填充 ----
            long tFill = System.nanoTime();
            for (int i = 0; i < N; i++) {
                os.put("b" + N, String.format("obj-%08d.dat", i), ps(PAYLOAD));
            }
            double fillMs = (System.nanoTime() - tFill) / 1e6;

            long entries = countEntries(bdir);

            // ---- A: LIST maxKeys=1000 (客户端默认) ----
            List<Double> la = new ArrayList<>();
            for (int r = 0; r < 5; r++) {
                long t = System.nanoTime();
                os.list("b" + N, "", "/", 1000, null);
                la.add((System.nanoTime() - t) / 1e6);
            }
            // ---- B: LIST 前缀只匹配 0 个 ⇒ 全目录扫描 ----
            List<Double> lb = new ArrayList<>();
            for (int r = 0; r < 5; r++) {
                long t = System.nanoTime();
                os.list("b" + N, "zzz/", "/", 1000, null);
                lb.add((System.nanoTime() - t) / 1e6);
            }
            // ---- C: LIST maxKeys=0 ⇒ 不限条数 ⇒ 全量读 .meta ----
            List<Double> lc = new ArrayList<>();
            for (int r = 0; r < 3; r++) {
                long t = System.nanoTime();
                os.list("b" + N, "", "/", 0, null);
                lc.add((System.nanoTime() - t) / 1e6);
            }
            // ---- 分页稳定性：两次 LIST 拿到的是不是同一批 key ----
            List<ObjectMeta> p1 = os.list("b" + N, "", "/", 1000, null);
            List<ObjectMeta> p2 = os.list("b" + N, "", "/", 1000, "ANY-TOKEN");
            Set<String> k1 = new HashSet<>(), k2 = new HashSet<>();
            p1.forEach(m -> k1.add(m.key())); p2.forEach(m -> k2.add(m.key()));
            Set<String> inter = new HashSet<>(k1); inter.retainAll(k2);
            String pageEq = (k1.equals(k2) && !k1.isEmpty()) ? "SAME" : ("ovl=" + inter.size());

            // ---- D: put 一个新对象(目录已有 N 个) ----
            List<Double> lp = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                long t = System.nanoTime();
                os.put("b" + N, String.format("new-%08d.dat", i), ps(PAYLOAD));
                lp.add((System.nanoTime() - t) / 1e6);
            }
            // ---- E: head 已有对象 ----
            List<Double> lh = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                long t = System.nanoTime();
                os.head("b" + N, String.format("obj-%08d.dat", i * 37 % N));
                lh.add((System.nanoTime() - t) / 1e6);
            }
            // ---- F: get 已有对象 ----
            List<Double> lg = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                long t = System.nanoTime();
                GetStream g = os.get("b" + N, String.format("obj-%08d.dat", i * 37 % N), 0, -1);
                try (java.io.InputStream in = g.content()) { in.readAllBytes(); }
                lg.add((System.nanoTime() - t) / 1e6);
            }
            System.out.println(String.format("%-8d %-9d %-11.3f %-11.3f %-11.3f %-11.4f %-11.4f %-11.4f %-7s  (fill %.1fs)",
                    N, entries, med(la), med(lb), med(lc), med(lp), med(lh), med(lg), pageEq, fillMs / 1000));
            System.out.flush();
        }
    }
    static void deleteTree(Path p) throws Exception {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (Exception e) {} });
        }
    }
}
