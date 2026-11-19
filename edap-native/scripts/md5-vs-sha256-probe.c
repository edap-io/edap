/*
 * MD5 vs SHA-256 的 **raw OpenSSL** 微对比（不涉及 JNI、不涉及 JVM）。
 *
 * 回答一个问题：**在目标平台（Solaris/SPARC）上，OpenSSL 的 MD5 与 SHA-256
 * 每字节成本是否几乎相同？**
 *
 * 为什么问这个（2026-11-18 的实测异常）：
 *   build-01 上的 JCE-vs-JNI 基准测出 —— MD5 与 SHA-256 的**每字节成本几乎相同**
 *   （native 侧 1.1453 vs 1.1528 ns/B，jce 侧 1.3957 vs 1.3998 ns/B，差 <0.7%），
 *   而这两个算法的复杂度差了 ~2.8 倍（软件实现里 SHA-256 应当明显慢于 MD5）。
 *   macOS 上同一对数字是 1.42 vs 0.41 ns/B（差 3.5 倍，符合预期）。
 *   ⇒ 要判断 SPARC 上「两算法同价」是 OpenSSL 没有汇编、还是被别的因素掩盖了，
 *     必须**在 build-01 上直接测 OpenSSL 本体**（不经过 JVM、不经过 JCE）。
 *
 * ★ 第二个、更值钱的问题：**native 臂的固定成本里有多少是 JNI、多少是 OpenSSL？**
 *   JNI-vs-JCE 基准在 SPARC 上测出 native 臂固定成本 **2,363 ns**，
 *   而 macOS 上只有 **380 ns**（差 6.2 倍）；macOS 上 raw OpenSSL 流式的固定成本
 *   实测约 **411 ns**（edap-crypto/doc/DESIGN.md §10.14）
 *   ⇒ 若 SPARC 上 OpenSSL 那一份也是 ~400 ns，则剩下 ~1.9 μs 是 **JNI 往返本身**
 *   ⇒ 「SPARC 的 JNI 往返很贵」这个结论会影响所有 JNI 加速项的取舍。
 *   ⇒ 本探针的 `stream` 臂输出的「固定成本」就是纯 OpenSSL 那一份。
 *
 * ★ 与 `oneshot-vs-stream-probe.c` 的区别：那个探针比的是「一次性 API vs 流式」
 *   （同一个算法）；这个探针比的是「**算法之间**」，并额外做**线性分解**
 *   （每字节边际成本 + 固定成本），因为 JCE-vs-JNI 的结论正是由这两项推出来的。
 *
 * ★ 为什么每个算法测三个 arm：
 *   stream —— legacy `Init/Update/Final`（**edap-native 的摘要走的正是这条路**）
 *   oneshot —— legacy 一次性 API（OpenSSL 3.x 下每次都要 provider fetch）
 *   evp —— EVP（provider 路径，仅作参照）
 *   ⇒ 三个 arm 一比，就能把「算法差异」与「API 差异」拆开。
 *
 * 编译 / 运行（Solaris/SPARC，build-01）：
 *   gcc -O2 -I/opt/csw/include -o /tmp/md5_vs_sha256 md5-vs-sha256-probe.c -lcrypto -lm
 *   /tmp/md5_vs_sha256                 # 跑 64 / 1024 / 8192 / 65536 四档
 *   /tmp/md5_vs_sha256 65536 2000      # 指定载荷字节数与每档迭代次数
 *
 * ⚠️ 编译期会有一堆 MD5_ 与 SHA256_ 系列函数的 deprecation 警告 —— 那是**预期的**。
 * ⚠️ **必须在目标机上跑**：本机（Mac）跑出来的 C 层数字是调度噪声
 *   （进程被反复剥夺 CPU），见 edap-crypto/doc/DESIGN.md §10.15.5。
 * ⚠️ 读数纪律：每 arm 跑 REPS 轮取**中位数**并报 CV；不取 min。
 */
#include <openssl/evp.h>
#include <openssl/md5.h>
#include <openssl/sha.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define DIGEST_MAX 32
#define REPS 7

/* ------------------------------------------------------------------ */
/* MD5                                                                */
/* ------------------------------------------------------------------ */

static void md5_oneshot(const unsigned char *data, size_t len, unsigned char *out) {
    MD5(data, len, out);
}

static void md5_stream(const unsigned char *data, size_t len, unsigned char *out) {
    MD5_CTX ctx;
    MD5_Init(&ctx);
    MD5_Update(&ctx, data, len);
    MD5_Final(out, &ctx);
}

static void md5_evp(const unsigned char *data, size_t len, unsigned char *out) {
    unsigned int outLen = 0;
    EVP_Digest(data, len, out, &outLen, EVP_md5(), NULL);
}

/* ------------------------------------------------------------------ */
/* SHA-256                                                            */
/* ------------------------------------------------------------------ */

static void sha256_oneshot(const unsigned char *data, size_t len, unsigned char *out) {
    SHA256(data, len, out);
}

static void sha256_stream(const unsigned char *data, size_t len, unsigned char *out) {
    SHA256_CTX ctx;
    SHA256_Init(&ctx);
    SHA256_Update(&ctx, data, len);
    SHA256_Final(out, &ctx);
}

static void sha256_evp(const unsigned char *data, size_t len, unsigned char *out) {
    unsigned int outLen = 0;
    EVP_Digest(data, len, out, &outLen, EVP_sha256(), NULL);
}

/* ------------------------------------------------------------------ */
/* 计时与统计                                                        */
/* ------------------------------------------------------------------ */

static double now_sec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static int cmp_double(const void *a, const void *b) {
    double x = *(const double *)a;
    double y = *(const double *)b;
    return (x < y) ? -1 : ((x > y) ? 1 : 0);
}

static double median_of(const double *samples, int n) {
    double sorted[REPS];
    memcpy(sorted, samples, sizeof(double) * n);
    qsort(sorted, n, sizeof(double), cmp_double);
    return sorted[n / 2];
}

static double cv_of(const double *samples, int n) {
    double mean = 0;
    int i;
    for (i = 0; i < n; i++) {
        mean += samples[i];
    }
    mean /= n;
    double var = 0;
    for (i = 0; i < n; i++) {
        double d = samples[i] - mean;
        var += d * d;
    }
    var /= n;
    double sd = (mean > 0) ? (var > 0 ? sqrt(var) : 0) : 0;
    return (mean > 0) ? (sd / mean * 100.0) : 0;
}

/* arm 取值 */
#define ARM_ONESHOT 0
#define ARM_STREAM  1
#define ARM_EVP     2

static void call_arm(int alg, int arm, const unsigned char *data, size_t len,
        unsigned char *out) {
    if (alg == 0) { /* MD5 */
        if (arm == ARM_ONESHOT)      md5_oneshot(data, len, out);
        else if (arm == ARM_STREAM)  md5_stream(data, len, out);
        else                         md5_evp(data, len, out);
    } else { /* SHA-256 */
        if (arm == ARM_ONESHOT)      sha256_oneshot(data, len, out);
        else if (arm == ARM_STREAM)  sha256_stream(data, len, out);
        else                         sha256_evp(data, len, out);
    }
}

static int digest_len_of(int alg) {
    return (alg == 0) ? 16 : 32;
}

static const char *alg_name(int alg) {
    return (alg == 0) ? "MD5" : "SHA-256";
}

static const char *arm_name(int arm) {
    switch (arm) {
        case ARM_ONESHOT: return "oneshot";
        case ARM_STREAM:  return "stream";
        default:          return "evp";
    }
}

/* 跑一个 (alg, arm) 的 REPS 轮，返回中位数 ns/op；CV 通过 out_cv 回传
 * （中位数与 CV 来自**同一批**样本 —— 分两批跑会把时间翻倍，还会让两批的
 *   机器状态不一致）*/
static double measure(int alg, int arm, const unsigned char *data, size_t len,
        int iters, unsigned char *out, double *out_cv) {
    double s[REPS];
    int r;
    for (r = 0; r < REPS; r++) {
        double t0 = now_sec();
        int i;
        for (i = 0; i < iters; i++) {
            call_arm(alg, arm, data, len, out);
        }
        s[r] = (now_sec() - t0) / iters * 1e9;
    }
    if (out_cv != NULL) {
        *out_cv = cv_of(s, REPS);
    }
    return median_of(s, REPS);
}

/* ------------------------------------------------------------------ */

static double g_md5_stream[4];
static double g_sha256_stream[4];
static size_t g_sizes[4];
static int g_nsize = 0;

/* 线性分解：marginal = (t_hi - t_lo) / (size_hi - size_lo)；fixed = t_lo - marginal*size_lo */
static void decompose(const char *label, double t_lo, size_t lo, double t_hi, size_t hi) {
    double marginal = (t_hi - t_lo) / (double)(hi - lo);
    double fixed = t_lo - marginal * (double)lo;
    double mbps = (marginal > 0) ? (1000.0 / marginal) : 0;
    printf("  %-14s marginal %8.4f ns/B  (%7.1f MB/s)   fixed %10.1f ns\n",
            label, marginal, mbps, fixed);
}

int main(int argc, char **argv) {
    size_t payload = 0;
    int iters = 0;
    int explicit = 0;

    printf("=== MD5 vs SHA-256 raw OpenSSL probe ===\n");
    printf("openssl: %s\n", OPENSSL_VERSION_TEXT);

    if (argc > 1) {
        payload = (size_t)atol(argv[1]);
        iters = (argc > 2) ? atoi(argv[2]) : 2000;
        explicit = 1;
    }

    for (;;) {
        size_t sizes[4];
        int n;
        if (explicit) {
            sizes[0] = payload;
            n = 1;
        } else {
            sizes[0] = 64; sizes[1] = 1024; sizes[2] = 8192; sizes[3] = 65536;
            n = 4;
        }

        int si;
        for (si = 0; si < n; si++) {
            size_t len = sizes[si];
            int it = iters;
            if (!explicit) {
                if (len == 64)       it = 20000;
                else if (len == 1024) it = 20000;
                else if (len == 8192) it = 5000;
                else                  it = 1000;
            }
            if (it <= 0) {
                it = 2000;
            }

            unsigned char *data = (unsigned char *)malloc(len ? len : 1);
            size_t i;
            for (i = 0; i < len; i++) {
                data[i] = (unsigned char)((i * 31 + 17) & 0x7f);
            }

            printf("\npayload=%zu iters=%d\n", len, it);

            /* 正确性：三个 arm 的输出必须一致 */
            int alg;
            for (alg = 0; alg < 2; alg++) {
                int dl = digest_len_of(alg);
                unsigned char o0[DIGEST_MAX], o1[DIGEST_MAX], o2[DIGEST_MAX];
                call_arm(alg, ARM_ONESHOT, data, len, o0);
                call_arm(alg, ARM_STREAM, data, len, o1);
                call_arm(alg, ARM_EVP, data, len, o2);
                printf("  %-8s oneshot==stream: %s   oneshot==evp: %s\n",
                        alg_name(alg),
                        memcmp(o0, o1, dl) == 0 ? "OK" : "MISMATCH",
                        memcmp(o0, o2, dl) == 0 ? "OK" : "MISMATCH");
            }

            for (alg = 0; alg < 2; alg++) {
                int arm;
                for (arm = 0; arm < 3; arm++) {
                    unsigned char out[DIGEST_MAX];
                    double cv = 0;
                    double med = measure(alg, arm, data, len, it, out, &cv);
                    printf("  %-8s %-8s median %10.1f ns/op   (CV %5.1f%%)\n",
                            alg_name(alg), arm_name(arm), med, cv);
                    if (arm == ARM_STREAM) {
                        if (alg == 0) {
                            g_md5_stream[g_nsize] = med;
                        } else {
                            g_sha256_stream[g_nsize] = med;
                        }
                    }
                }
            }

            g_sizes[g_nsize] = len;
            g_nsize++;
            free(data);
        }

        if (explicit) {
            break;
        }
        break;
    }

    if (g_nsize >= 2) {
        printf("\n=== 线性分解（用最小与最大两档外推）===\n");
        decompose("MD5 stream", g_md5_stream[0], g_sizes[0],
                g_md5_stream[g_nsize - 1], g_sizes[g_nsize - 1]);
        decompose("SHA-256 stream", g_sha256_stream[0], g_sizes[0],
                g_sha256_stream[g_nsize - 1], g_sizes[g_nsize - 1]);
        printf("  => SHA-256/MD5 每字节成本比 = %.3f\n",
                (g_md5_stream[0] != 0)
                    ? ((g_sha256_stream[g_nsize - 1] - g_sha256_stream[0])
                       / (g_md5_stream[g_nsize - 1] - g_md5_stream[0]))
                    : 0);
        printf("  （软件实现里这个比值通常是 1.3~2.0；接近 1.0 说明两边都没有汇编"
               "或都被别的因素掩盖了）\n");
    }

    return 0;
}
