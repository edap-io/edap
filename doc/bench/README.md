# edap-s3 磁盘索引改造 —— 基准程序

这些是 [S3_INDEX_DESIGN.md](../S3_INDEX_DESIGN.md) §9 性能复测用的基准程序。
**它们不在 Maven 构建里**（`doc/` 不是源码根，`.java` 不会被编译），
因为 `S3Bench.java` / `S3BenchIoOld.java` / `PhotosOld.java` 用的是**改造前**的
`ObjectStore.list(bucket, prefix, delimiter, maxKeys, token)` 签名 ——
在当前代码里根本编译不过。

| 文件 | 对应实现 | 说明 |
|---|---|---|
| `S3Bench.java` | 修前 | 主基准（老 API），N = 1k/10k/50k/100k |
| `S3BenchAfter.java` | 修后 | 主基准（**与 `S3Bench.java` 逐行等价**，只有 `list()` 调用方式不同） |
| `S3BenchIoOld.java` | 修前 | 受控 IO 对比：显式焐热 page cache 再测 head/get |
| `S3BenchIoNew.java` | 修后 | 同上 |
| `PhotosOld.java` | 修前 | 真实桶 `user-uploads` 的 LIST 可见性（§10.1） |
| `PhotosNew.java` | 修后 | 同上（§10.2 / §10.3） |

原始数据：[`../S3_INDEX_BENCH_RAW.txt`](../S3_INDEX_BENCH_RAW.txt)

## 怎么跑

**修前**（需要一个改造前的 worktree）：

```bash
git worktree add --detach /tmp/s3old HEAD        # HEAD = 改造前的提交
cd /tmp/s3old && mvn -o package                   # 出旧的 core/disk classes

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
OLDCORE=/tmp/s3old/edap-s3-parent/edap-s3-core/target/classes
OLDDISK=/tmp/s3old/edap-s3-parent/edap-s3-disk/target/classes
CP="$OLDCORE:$OLDDISK:$HOME/.m2/repository/io/edap/edap-common/0.1.3-SNAPSHOT/edap-common-0.1.3-SNAPSHOT.jar:$HOME/.m2/repository/io/edap/edap-log-api/0.1.3-SNAPSHOT/edap-log-api-0.1.3-SNAPSHOT.jar"

javac -cp "$CP" -d /tmp/bench-old doc/bench/S3Bench.java
java -cp "/tmp/bench-old:$CP" S3Bench /tmp/s3bench/data-before
```

**修后**（当前工作区）：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
mvn -o -pl edap-s3-parent/edap-s3-core,edap-s3-parent/edap-s3-disk package
NEWCORE=edap-s3-parent/edap-s3-core/target/classes
NEWDISK=edap-s3-parent/edap-s3-disk/target/classes
CP="$NEWCORE:$NEWDISK:$HOME/.m2/repository/io/edap/edap-common/0.1.3-SNAPSHOT/edap-common-0.1.3-SNAPSHOT.jar:$HOME/.m2/repository/io/edap/edap-log-api/0.1.3-SNAPSHOT/edap-log-api-0.1.3-SNAPSHOT.jar"

javac -cp "$CP" -d /tmp/bench-new doc/bench/S3BenchAfter.java
java -cp "/tmp/bench-new:$CP" S3BenchAfter /tmp/s3bench/data-after
```

## 纪律

- **修前/修后两批数据都留**（不要只留改后的）。
- **跨轮次数字不可混用**：本目录的表格全部是「同一台机器、同一份基准逻辑、
  同一组 N」下跑出来的。
- **跑批必须记 loadavg**（`sysctl -n vm.loadavg`）—— 机器负载高的时候数字会漂。
- 改阈值/参数 ⇒ ≥5 轮 + 轮次奇偶对调格子顺序。
