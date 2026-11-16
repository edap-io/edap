/*
 * EVP vs legacy 的 HMAC-SHA256 微对比（不涉及 JNI，纯 C）。
 *
 * 回答一个问题：把 edap-native 的 HMAC 从 legacy（手工展开 ipad/opad + SHA256_*）
 * 换成 OpenSSL 3.x 的 EVP_MAC（fetch 缓存版），值不值得？
 *
 * 三个 arm：
 *   legacy      —— edap_jni_common.h 里那套手工展开
 *   evp_cached  —— EVP_MAC_fetch(NULL,"HMAC",NULL) 只做一次，之后复用
 *   evp_uncached—— 每次都 EVP_MAC_fetch（= NATIVE_DESIGN.md §6.6 说的 dispatch 元凶）
 *
 * 编译 / 运行：
 *   cc -O2 -I/opt/homebrew/opt/openssl@3/include -L/opt/homebrew/opt/openssl@3/lib \
 *      -o /tmp/evp_probe evp-vs-legacy-probe.c -lcrypto
 *   /tmp/evp_probe <payload字节> <迭代次数>      # 默认 64 / 200000
 *
 * 编译期会有一堆 SHA256_Init/Update/Final 的 deprecation 警告 —— 那是**预期的**，
 * 本探针比的就是「legacy（OpenSSL 3.0 已标 deprecated）vs EVP」。
 *
 * 三个 arm 的输出逐字节相同（正确性 OK）；耗时结论与数据见
 * edap-crypto/doc/DESIGN.md §10.5 —— EVP 缓存版追不上 legacy，故不迁移。
 */
#include <openssl/evp.h>
#include <openssl/sha.h>
#include <openssl/opensslv.h>
#include <openssl/core_names.h>
#include <openssl/params.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define BLOCK 64
#define DIGEST 32

/* ---- legacy：与 edap_jni_common.h 的 edap_hmac_sha256 等价 ---- */
static void legacy_hmac(const unsigned char *key, size_t keyLen,
        const unsigned char *data, size_t len, unsigned char *out) {
    unsigned char k_pad[BLOCK], inner_pad[BLOCK], outer_pad[BLOCK];
    if (keyLen > BLOCK) {
        SHA256(key, keyLen, k_pad);
        memset(k_pad + DIGEST, 0, BLOCK - DIGEST);
    } else {
        memcpy(k_pad, key, keyLen);
        memset(k_pad + keyLen, 0, BLOCK - keyLen);
    }
    for (int i = 0; i < BLOCK; i++) {
        inner_pad[i] = (unsigned char)(k_pad[i] ^ 0x36);
        outer_pad[i] = (unsigned char)(k_pad[i] ^ 0x5c);
    }
    /* 与 edap-native/src/main/c/edap_jni_common.h 的 edap_hmac_sha256 逐行等价 */
    SHA256_CTX inner;
    SHA256_Init(&inner);
    SHA256_Update(&inner, inner_pad, BLOCK);
    SHA256_Update(&inner, data, len);
    unsigned char inner_hash[DIGEST];
    SHA256_Final(inner_hash, &inner);

    SHA256_CTX outer;
    SHA256_Init(&outer);
    SHA256_Update(&outer, outer_pad, BLOCK);
    SHA256_Update(&outer, inner_hash, DIGEST);
    SHA256_Final(out, &outer);
}

/* ---- EVP_MAC：cached（fetch 一次）/ uncached（每次 fetch） ---- */
static int evp_hmac(EVP_MAC *mac, const unsigned char *key, size_t keyLen,
        const unsigned char *data, size_t len, unsigned char *out, size_t *outLen) {
    OSSL_PARAM params[2];
    params[0] = OSSL_PARAM_construct_utf8_string("digest", "SHA256", 0);
    params[1] = OSSL_PARAM_construct_end();

    EVP_MAC_CTX *ctx = EVP_MAC_CTX_new(mac);
    if (ctx == NULL) return 0;
    if (EVP_MAC_init(ctx, key, keyLen, params) != 1) { EVP_MAC_CTX_free(ctx); return 0; }
    if (EVP_MAC_update(ctx, data, len) != 1) { EVP_MAC_CTX_free(ctx); return 0; }
    if (EVP_MAC_final(ctx, out, outLen, 32) != 1) { EVP_MAC_CTX_free(ctx); return 0; }
    EVP_MAC_CTX_free(ctx);
    return 1;
}

static double now_sec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
    size_t payload = (argc > 1) ? (size_t)atoi(argv[1]) : 64;
    int iters = (argc > 2) ? atoi(argv[2]) : 200000;

    unsigned char key[48];
    for (int i = 0; i < 48; i++) key[i] = (unsigned char)(i * 7 + 3);

    unsigned char *data = malloc(payload);
    for (size_t i = 0; i < payload; i++) data[i] = (unsigned char)((i * 31 + 17) & 0x7f);

    unsigned char out_legacy[DIGEST], out_evp[DIGEST], out_unc[DIGEST];
    size_t outLen = DIGEST;

    /* correctness */
    legacy_hmac(key, sizeof(key), data, payload, out_legacy);
    EVP_MAC *cached = EVP_MAC_fetch(NULL, "HMAC", NULL);
    if (cached == NULL) { fprintf(stderr, "EVP_MAC_fetch failed\n"); return 1; }
    evp_hmac(cached, key, sizeof(key), data, payload, out_evp, &outLen);
    EVP_MAC *unc = EVP_MAC_fetch(NULL, "HMAC", NULL);
    evp_hmac(unc, key, sizeof(key), data, payload, out_unc, &outLen);
    EVP_MAC_free(unc);

    printf("payload=%zu iters=%d\n", payload, iters);
    printf("  legacy == evp_cached : %s\n",
            memcmp(out_legacy, out_evp, DIGEST) == 0 ? "OK" : "MISMATCH");
    printf("  legacy == evp_uncach: %s\n",
            memcmp(out_legacy, out_unc, DIGEST) == 0 ? "OK" : "MISMATCH");

    double best_legacy = 1e30, best_cached = 1e30, best_unc = 1e30;
    volatile unsigned sink = 0;

    for (int rep = 0; rep < 5; rep++) {
        double t0 = now_sec();
        for (int i = 0; i < iters; i++) legacy_hmac(key, sizeof(key), data, payload, out_legacy);
        double d = now_sec() - t0;
        if (d < best_legacy) best_legacy = d;
        sink += out_legacy[0];
    }

    for (int rep = 0; rep < 5; rep++) {
        double t0 = now_sec();
        for (int i = 0; i < iters; i++) evp_hmac(cached, key, sizeof(key), data, payload, out_evp, &outLen);
        double d = now_sec() - t0;
        if (d < best_cached) best_cached = d;
        sink += out_evp[0];
    }

    for (int rep = 0; rep < 5; rep++) {
        double t0 = now_sec();
        for (int i = 0; i < iters; i++) {
            EVP_MAC *m = EVP_MAC_fetch(NULL, "HMAC", NULL);
            evp_hmac(m, key, sizeof(key), data, payload, out_unc, &outLen);
            EVP_MAC_free(m);
        }
        double d = now_sec() - t0;
        if (d < best_unc) best_unc = d;
        sink += out_unc[0];
    }

    double n = (double)iters;
    double ns_legacy = best_legacy / n * 1e9;
    double ns_cached = best_cached / n * 1e9;
    double ns_unc    = best_unc / n * 1e9;
    printf("  legacy       : %10.1f ns/op\n", ns_legacy);
    printf("  evp_cached   : %10.1f ns/op   (legacy/evp = %.2fx)\n", ns_cached, ns_legacy / ns_cached);
    printf("  evp_uncached : %10.1f ns/op   (legacy/evp = %.2fx)\n", ns_unc, ns_legacy / ns_unc);
    (void)sink;

    EVP_MAC_free(cached);
    free(data);
    return 0;
}
