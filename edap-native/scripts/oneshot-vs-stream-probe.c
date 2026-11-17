/*
 * one-shot vs 流式 的 SHA-256 微对比（不涉及 JNI，纯 C）。
 *
 * 回答一个问题：把 edap-native 的摘要从「一次性 API + Java 侧累积」改成
 * 「native 侧持有上下文、数据喂一次算一次」，native 侧自己会不会变慢？
 *
 * 四个 arm：
 *   oneshot  —— SHA256(data, len, md)（早先 native digest 用的路径）
 *   stream1  —— SHA256_Init + Update(整块) + Final（流式，单次 update）
 *   streamN  —— SHA256_Init + N 次 Update(分块) + Final（流式，分块 update）
 *   evp      —— EVP_Digest（OpenSSL 3.x 的 provider 路径，仅作参照）
 *
 * ★ 为什么要单独测这个：native 侧省掉的是「Java 侧把整份数据拷一遍」
 *   （Solaris/SPARC 上 64KB 实测 ~17.7µs），但流式化后 native 侧多了一次
 *   Init/Update/Final 的调用开销 —— 两者相加才是净收益。
 *   「Init/Update/Final 比一次性 API 慢多少」在 Solaris/SPARC 上<b>没有实测数据</b>，
 *   只能靠推演 —— 而「机制同源」的推演不能替代实测。
 *
 * 编译 / 运行（Solaris）：
 *   cc -O2 -I/opt/csw/include -o /tmp/oneshot_probe oneshot-vs-stream-probe.c -lcrypto
 *   /tmp/oneshot_probe                 # 跑 64 / 1024 / 8192 / 65536 四档
 *   /tmp/oneshot_probe 65536 2000      # 指定载荷字节数与每档迭代次数
 *
 * 编译期会有一堆 SHA256_Init/Update/Final 的 deprecation 警告 —— 那是**预期的**，
 * 本探针比的就是「legacy（OpenSSL 3.0 已标 deprecated）vs EVP vs 一次性」。
 *
 * 四个 arm 的输出逐字节相同（正确性 OK）。
 */
#include <openssl/evp.h>
#include <openssl/sha.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define DIGEST 32

/* ---- arm 1：一次性（当前 native digest 走的路） ---- */
static void oneshot_sha256(const unsigned char *data, size_t len, unsigned char *out) {
    SHA256(data, len, out);
}

/* ---- arm 2：legacy 流式，单次 update ---- */
static void stream1_sha256(const unsigned char *data, size_t len, unsigned char *out) {
    SHA256_CTX ctx;
    SHA256_Init(&ctx);
    SHA256_Update(&ctx, data, len);
    SHA256_Final(out, &ctx);
}

/* ---- arm 3：legacy 流式，分块 update ---- */
static void streamn_sha256(const unsigned char *data, size_t len, unsigned char *out,
        size_t chunk) {
    SHA256_CTX ctx;
    SHA256_Init(&ctx);
    size_t off = 0;
    while (off < len) {
        size_t n = (len - off < chunk) ? (len - off) : chunk;
        SHA256_Update(&ctx, data + off, n);
        off += n;
    }
    SHA256_Final(out, &ctx);
}

/* ---- arm 4：EVP（provider dispatch 路径，仅作参照） ---- */
static void evp_sha256(const unsigned char *data, size_t len, unsigned char *out) {
    unsigned int outLen = 0;
    EVP_Digest(data, len, out, &outLen, EVP_sha256(), NULL);
}

static double now_sec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

/* ---- 统计：多轮取中位数 + CV（单 JVM 内不适用「一个候选一个 JVM」，但仍要看离散度） ---- */
#define REPS 7

static int cmp_double(const void *a, const void *b) {
    double x = *(const double *)a;
    double y = *(const double *)b;
    return (x < y) ? -1 : ((x > y) ? 1 : 0);
}

static void summarize(const char *name, double *samples, double *out_median, double *out_cv) {
    double sorted[REPS];
    memcpy(sorted, samples, sizeof(sorted));
    qsort(sorted, REPS, sizeof(double), cmp_double);
    double median = sorted[REPS / 2];
    double mean = 0;
    for (int i = 0; i < REPS; i++) {
        mean += samples[i];
    }
    mean /= REPS;
    double var = 0;
    for (int i = 0; i < REPS; i++) {
        double d = samples[i] - mean;
        var += d * d;
    }
    var /= REPS;
    double sd = (mean > 0) ? (var > 0 ? sqrt(var) : 0) : 0;
    *out_median = median;
    *out_cv = (mean > 0) ? (sd / mean * 100.0) : 0;
    printf("  %-9s median %10.1f ns/op   (CV %5.1f%%)\n", name, median, *out_cv);
}

/* 单次 arm 的计时（一轮 = iters 次） */
static double time_arm(int arm, const unsigned char *data, size_t len, int iters,
        unsigned char *out, size_t chunk) {
    double t0 = now_sec();
    for (int i = 0; i < iters; i++) {
        switch (arm) {
            case 0: oneshot_sha256(data, len, out); break;
            case 1: stream1_sha256(data, len, out); break;
            case 2: streamn_sha256(data, len, out, chunk); break;
            default: evp_sha256(data, len, out); break;
        }
    }
    return (now_sec() - t0) / iters * 1e9;
}

static void run_size(size_t payload, int iters, size_t chunk) {
    unsigned char *data = (unsigned char *)malloc(payload ? payload : 1);
    for (size_t i = 0; i < payload; i++) {
        data[i] = (unsigned char)((i * 31 + 17) & 0x7f);
    }

    unsigned char o0[DIGEST], o1[DIGEST], o2[DIGEST], o3[DIGEST];
    oneshot_sha256(data, payload, o0);
    stream1_sha256(data, payload, o1);
    streamn_sha256(data, payload, o2, chunk);
    evp_sha256(data, payload, o3);

    printf("payload=%zu iters=%d chunk=%zu\n", payload, iters, chunk);
    printf("  oneshot == stream1 : %s\n", memcmp(o0, o1, DIGEST) == 0 ? "OK" : "MISMATCH");
    printf("  oneshot == streamN : %s\n", memcmp(o0, o2, DIGEST) == 0 ? "OK" : "MISMATCH");
    printf("  oneshot == evp     : %s\n", memcmp(o0, o3, DIGEST) == 0 ? "OK" : "MISMATCH");

    double s[REPS];
    double median, cv;

    for (int r = 0; r < REPS; r++) s[r] = time_arm(0, data, payload, iters, o0, chunk);
    summarize("oneshot", s, &median, &cv);
    double m_oneshot = median;

    for (int r = 0; r < REPS; r++) s[r] = time_arm(1, data, payload, iters, o1, chunk);
    summarize("stream1", s, &median, &cv);
    double m_stream1 = median;

    for (int r = 0; r < REPS; r++) s[r] = time_arm(2, data, payload, iters, o2, chunk);
    summarize("streamN", s, &median, &cv);
    double m_streamn = median;

    for (int r = 0; r < REPS; r++) s[r] = time_arm(3, data, payload, iters, o3, chunk);
    summarize("evp", s, &median, &cv);
    double m_evp = median;

    printf("  ratio stream1/oneshot = %.3f   streamN/oneshot = %.3f   evp/oneshot = %.3f\n",
            m_stream1 / m_oneshot, m_streamn / m_oneshot, m_evp / m_oneshot);

    free(data);
}

int main(int argc, char **argv) {
    if (argc > 1) {
        size_t payload = (size_t)atol(argv[1]);
        int iters = (argc > 2) ? atoi(argv[2]) : 2000;
        size_t chunk = (argc > 3) ? (size_t)atol(argv[3]) : 4096;
        run_size(payload, iters, chunk);
        return 0;
    }

    /* 默认四档：与 JvmVsJniPerfTest 的载荷档位对齐 */
    run_size(64, 20000, 4096);
    run_size(1024, 20000, 4096);
    run_size(8192, 5000, 4096);
    run_size(65536, 1000, 4096);
    return 0;
}
