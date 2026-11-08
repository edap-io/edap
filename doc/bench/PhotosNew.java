import io.edap.s3.model.ListOptions;
import io.edap.s3.model.ListResult;
import io.edap.s3.store.disk.DiskBucketStore;
import io.edap.s3.store.disk.DiskObjectStore;

import java.nio.file.*;

/** 修后实现:同样的真实桶,init() 触发迁移 + 索引,再看 LIST。 */
public class PhotosNew {
    public static void main(String[] a) throws Exception {
        Path root = Paths.get(a[0]);
        DiskBucketStore bs = new DiskBucketStore(root);
        DiskObjectStore os = new DiskObjectStore(bs);

        long t0 = System.nanoTime();
        os.init();      // 启动期钩子:回放索引 log,没有 log 就迁移老 .meta 布局
        System.out.printf("NEW init(迁移+回放)                -> %8.1f ms%n", (System.nanoTime() - t0) / 1e6);

        for (int r = 0; r < 3; r++) {
            long t = System.nanoTime();
            ListResult all = os.list("user-uploads", ListOptions.of("", "/", 1000));
            System.out.printf("NEW list(all, maxKeys=1000)      -> %3d contents, %d commonPrefixes, keyCount=%d  %8.3f ms%n",
                    all.contents().size(), all.commonPrefixes().size(), all.keyCount(),
                    (System.nanoTime() - t) / 1e6);
        }
        for (int r = 0; r < 3; r++) {
            long t = System.nanoTime();
            ListResult p = os.list("user-uploads", ListOptions.of("photos/", "/", 1000));
            System.out.printf("NEW list(prefix=photos/)          -> %3d keys  %8.3f ms%n",
                    p.contents().size(), (System.nanoTime() - t) / 1e6);
        }
        ListResult all = os.list("user-uploads", ListOptions.of("", "/", 1000));
        System.out.println("NEW 可见的 key(前 5 个):");
        all.contents().stream().limit(5).forEach(m -> System.out.println("    " + m.key()));

        String k = "photos/036a7f92-f6e0-44c2-a93f-e8fff47d0386.jpg";
        for (int r = 0; r < 3; r++) {
            long t = System.nanoTime();
            os.head("user-uploads", k);
            System.out.printf("NEW head(photo)                   -> %8.4f ms%n", (System.nanoTime() - t) / 1e6);
        }
        for (int r = 0; r < 3; r++) {
            long t = System.nanoTime();
            try (java.io.InputStream in = os.get("user-uploads", k, 0, -1).content()) {
                in.readAllBytes();
            }
            System.out.printf("NEW get(photo, 127KB)              -> %8.4f ms%n", (System.nanoTime() - t) / 1e6);
        }
        System.out.println("NEW isEmpty(user-uploads) = " + os.isEmpty("user-uploads"));

        Path idx = root.resolve("user-uploads/.index/log");
        System.out.println("索引 log 存在 = " + Files.exists(idx)
                + (Files.exists(idx) ? ", size=" + Files.size(idx) + "B" : ""));
        try (var s = Files.list(root.resolve("user-uploads/photos"))) {
            System.out.println("photos/ 目录项数 = " + s.count());
        }
    }
}
