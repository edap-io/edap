# edap-crypto 基准程序

对应 [`../DESIGN.md`](../DESIGN.md) §八（基准）与 §十（实测）。

## 基准类

| 类 | 对应实现 | 说明 |
|---|---|---|
| `CryptoBaselineBenchmark` | **修前** | 裸 JCE，每次 `getInstance` 新建实例（`CryptUtil.md5/sha1`、`SigV4Verifier` 的形态） |
| `CryptoProviderBenchmark` | **修后** | 走 `CryptoProvider`（JCE 实现，实例按线程复用） |
| `NativeVsJceBenchmark` | 对照 | native vs jce 直接对比（两边都走 provider，唯一变量是底层实现） |

**实验设计**：`before` 与 `after` 两组**都用 JCE 实现**，
唯一变量是「每次 `getInstance`」vs「provider 复用实例」；
native 相对 JCE 的收益单独在 `NativeVsJceBenchmark` 里测。

维度：payload 64 / 1024 / 8192 / 65536 字节 × 线程 1 / 4 / 16 × arm（md5 / sha1 / hmac-sha256）。

## 怎么跑

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./scripts/run-bench.sh 5        # 5 轮（默认）
```

脚本会：编译 test classes → 拉 test scope classpath → 逐轮跑三个基准类 →
每轮前后记录 loadavg → 汇总中位数 + 最小/最大 + 变异系数。

## 产出

| 文件 | 说明 |
|---|---|
| `before-CryptoBaseline-<ts>-r<N>.json` | 修前裸 JCE，N 轮 |
| `after-CryptoProvider-<ts>-r<N>.json` | 修后走 provider，N 轮 |
| `after-NativeVsJce-<ts>-r<N>.json` | native vs jce，N 轮 |
| `summary-<ts>.md` | 中位数 + 最小/最大 + 均值 + CV% |
| `bench-<ts>.log` | 原始日志 + 环境元数据（date / uname / java / openssl / git HEAD / 每轮 loadavg） |

## 纪律

- **修前/修后两批数据都留**（不要只留改后的）。`CryptoBaselineBenchmark`
  **永久保留**，用来复现对比。
- **跨轮次数字不可混用**：所有表格都来自同一台机器、同一份基准逻辑、同一组参数。
- **跑批必须记 loadavg**（`sysctl -n vm.loadavg`）。开发机上 IDE / 编辑器 / 其它后台
  进程会让 loadavg 长期高于「核数的一半」，脚本等 3×30s 后仍高就继续跑并记录。
- **≥5 轮**，报**中位数 + 最小/最大 + 变异系数**，不用单轮数字下结论。
- 改阈值/参数 ⇒ 重跑 5 轮，**新旧两批一起留**。

## 手工跑单个基准

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
CP_FILE=/tmp/edap-crypto-cp.txt
mvn -pl edap-crypto dependency:build-classpath \
    -DincludeScope=test -Dmdep.outputFile=$CP_FILE
CP="edap-crypto/target/test-classes:edap-crypto/target/classes:$(cat $CP_FILE)"

# 结果文件由 benchmark 自己的 main 通过 -Dedap.bench.out 配置
java -Dedap.bench.out=/tmp/out.json -cp "$CP" \
     io.edap.crypto.benchmark.CryptoProviderBenchmark
```

> JMH 1.37 没有 `Assumptions` 类；`NativeVsJceBenchmark` 在 native 不可用时
> 用 `OptionsBuilder.include(regex)` 只跑 jce 那两个 arm，不产出失败的 iteration。

## 两处必须知道的坑

### 1. native 与 jce 的对比要「零拷贝」才公平

`NativeDigestSession.digest()` / `NativeMacSession.doFinal()` 早期版本用
`ByteAccumulator.toArray()` —— **多一次整负载拷贝**（JNI 侧 buffer → Java byte[]）。
那样 native arm 会白白多一次拷贝，对比不公平。

现在改成 `ByteAccumulator.buffer()` 零拷贝访问内部缓冲，
native 与 jce 两边的拷贝次数对齐。**改这一处必须重跑基准**（新旧两批一起留）。

### 2. `NativeVsJceBenchmark` 的 native arm 可能整个不跑

已提交的 `.o` 动态链接到**绝对路径**
`/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib`
（见 [`../DESIGN.md`](../DESIGN.md) §6.5）⇒ **只有装了 Homebrew openssl@3
且路径完全一致**的机器能加载。其它机器上 `System.load` 失败 ⇒ native 不可用 ⇒
该 arm 被 `include(regex)` 排除，JSON 里**只有 jce 的结果**。

**读 JSON 前先确认 native arm 真的产出过**，否则不能拿「native vs jce」下结论。
