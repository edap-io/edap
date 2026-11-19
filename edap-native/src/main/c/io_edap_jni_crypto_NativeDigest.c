/*
 * 摘要 native impl（SHA-256 / SHA-1 / MD5）。
 *
 * 与 NativeHmacSha256.c 同源思路：绕开 OpenSSL 3.x 的 provider dispatch，
 * 直接调 legacy 的 SHA256() / SHA1() / MD5() 一次性 API。
 *
 * 为什么用一次性 API 而不是 SHA256_Init/Update/Final：
 * - edap-crypto 的 DigestSession 是流式的，但 native 侧一次性、Java 侧累积
 *   （见 io.edap.crypto.digest.NativeDigestProvider）—— 落到 native 时数据已经完整；
 * - 一次性 API 内部没有 provider dispatch，比 EVP 快（详见 NATIVE_DESIGN.md §6.6）。
 *
 * ★ 为什么按算法拆成三个入口（digestSha2560 / digestSha10 / digestMd50），
 *   而不是一个带 jstring 的 digest0(algorithm, ...)：
 *   老入口每次调用都要 GetStringUTFChars + strcmp + ReleaseStringUTFChars。
 *   在 Solaris/SPARC 上实测，这段固定开销约 9.5µs/次 —— native digest 的
 *   固定成本比 native HMAC（无字符串入口）多出的部分几乎全来自这里。
 *   拆开后算法在 Java 侧一次性绑定，native 侧零字符串 —— 与 NativeMac.c 同一形状。
 *
 * ★★ 这三个入口是**遗留的一次性入口**（edap-crypto 的 provider 已改走
 *   NativeStream 的流式接口，见 io_edap_jni_crypto_NativeStream.c）。
 *   ⚠️ **不要把流式语义再接回这里**：Solaris 上 OpenSSL 的一次性 API
 *   （本文件用的 SHA256() / SHA1() / MD5()）有 **~7µs 固定开销**
 *   （它同样走 provider dispatch；实测 build-01 上一次性 7463ns@64B
 *   vs legacy 流式 411ns ⇒ 差 18 倍）。
 *   ⇒ 需要流式摘要就用 NativeStream（update / finish / free）。
 *
 * ⚠️ **2026-11-19 更正**：上面那个「~7µs / 18 倍」**只对 SHA-256 成立**。
 *   另一个纯 C 探针（edap-native/scripts/md5-vs-sha256-probe.c，同机 build-01）
 *   实测 **MD5 的一次性 API 没有 provider dispatch 税**：
 *     一次性 413.0ns@64B vs legacy 流式 371.9ns ⇒ 只差 1.11 倍。
 *   ⇒ 「不要接回流式」这个结论仍然成立，但**依据是 SHA-256 那条**；
 *   **MD5 若改回一次性入口，省不到也丢不掉什么**。
 *   ⇒ 详见 edap-crypto/doc/DESIGN.md §10.11.2 与 §10.15.7。
 */
#include <jni.h>
#include <openssl/md5.h>
#include <openssl/sha.h>
#include <string.h>

#include "edap_jni_array.h"
#include "edap_jni_common.h"
#include "io_edap_jni_crypto_NativeDigest.h"

/*
 * 三个一次性摘要函数的签名一致（OpenSSL legacy API）：
 *   unsigned char *SHA256(const unsigned char *d, size_t n, unsigned char *md);
 *   unsigned char *SHA1  (const unsigned char *d, size_t n, unsigned char *md);
 *   unsigned char *MD5   (const unsigned char *d, size_t n, unsigned char *md);
 * ⇒ 用函数指针统一，避免三份几乎一样的 JNI 样板。
 */
typedef unsigned char *(*edap_digest_fn)(const unsigned char *, size_t, unsigned char *);

/**
 * 一次摘要计算的上下文（喂给 edap_jni_consume_bytes 的回调）。
 */
typedef struct {
    edap_digest_fn fn;
    unsigned char result[EDAP_SHA256_DIGEST_SIZE];
} edap_digest_ctx;

/**
 * edap_jni_consume_bytes 的回调：拿到一段数据 ⇒ 算摘要。
 */
static void edap_digest_apply(void *vctx, const unsigned char *data, size_t len) {
    edap_digest_ctx *c = (edap_digest_ctx *)vctx;
    c->fn(data, len, c->result);
}

/**
 * 三个入口的公共实现。
 *
 * <p>★ 取数走 edap_jni_consume_bytes：小载荷（≤ 1 KB）拷栈缓冲，
 * 大载荷走 critical section（不拷贝）—— 与流式入口共用同一份阈值与分流逻辑。</p>
 *
 * @param digestSize 输出长度（32 / 20 / 16）
 */
static jbyteArray edap_digest_common(JNIEnv *env, jbyteArray data, jint offset, jint len,
        edap_digest_fn fn, int digestSize)
{
    if (data == NULL || offset < 0 || len < 0) {
        return NULL;
    }

    edap_digest_ctx ctx;
    ctx.fn = fn;
    if (!edap_jni_consume_bytes(env, data, offset, len, edap_digest_apply, &ctx)) {
        return NULL;
    }

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)digestSize);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)digestSize, (const jbyte *)ctx.result);
    }
    return ret;
}

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeDigest_digestSha2560
  (JNIEnv *env, jclass cls, jbyteArray data, jint offset, jint len)
{
    return edap_digest_common(env, data, offset, len, SHA256, EDAP_SHA256_DIGEST_SIZE);
}

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeDigest_digestSha10
  (JNIEnv *env, jclass cls, jbyteArray data, jint offset, jint len)
{
    return edap_digest_common(env, data, offset, len, SHA1, EDAP_SHA1_DIGEST_SIZE);
}

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeDigest_digestMd50
  (JNIEnv *env, jclass cls, jbyteArray data, jint offset, jint len)
{
    return edap_digest_common(env, data, offset, len, MD5, EDAP_MD5_DIGEST_SIZE);
}
