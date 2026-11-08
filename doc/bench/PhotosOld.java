import io.edap.s3.model.ObjectMeta;
import io.edap.s3.store.disk.DiskBucketStore;
import io.edap.s3.store.disk.DiskObjectStore;

import java.nio.file.*;
import java.util.List;

/** 修前实现:看真实 user-uploads 桶在 LIST 里能看见多少 key。 */
public class PhotosOld {
    public static void main(String[] a) throws Exception {
        Path root = Paths.get(a[0]);
        DiskBucketStore bs = new DiskBucketStore(root);
        DiskObjectStore os = new DiskObjectStore(bs);

        for (int r = 0; r < 3; r++) {
            long t = System.nanoTime();
            List<ObjectMeta> all = os.list("user-uploads", "", "/", 1000, null);
            System.out.printf("OLD list(all, maxKeys=1000)      -> %3d keys  %8.3f ms%n",
                    all.size(), (System.nanoTime() - t) / 1e6);
        }
        for (int r = 0; r < 3; r++) {
            long t = System.nanoTime();
            List<ObjectMeta> p = os.list("user-uploads", "photos/", "/", 1000, null);
            System.out.printf("OLD list(prefix=photos/)          -> %3d keys  %8.3f ms%n",
                    p.size(), (System.nanoTime() - t) / 1e6);
        }
        List<ObjectMeta> all = os.list("user-uploads", "", "/", 1000, null);
        System.out.println("OLD 可见的 key:");
        for (ObjectMeta m : all) System.out.println("    " + m.key());

        String k = "photos/036a7f92-f6e0-44c2-a93f-e8fff47d0386.jpg";
        for (int r = 0; r < 3; r++) {
            long t = System.nanoTime();
            os.head("user-uploads", k);
            System.out.printf("OLD head(photo)                   -> %8.4f ms%n", (System.nanoTime() - t) / 1e6);
        }
        for (int r = 0; r < 3; r++) {
            long t = System.nanoTime();
            try (java.io.InputStream in = os.get("user-uploads", k, 0, -1).content()) {
                in.readAllBytes();
            }
            System.out.printf("OLD get(photo, 127KB)              -> %8.4f ms%n", (System.nanoTime() - t) / 1e6);
        }
        System.out.println("OLD isEmpty(user-uploads) = " + os.isEmpty("user-uploads"));
    }
}
