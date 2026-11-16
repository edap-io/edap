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
 */
#include <jni.h>
#include <openssl/md5.h>
#include <openssl/sha.h>
#include <string.h>

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
 * 三个入口的公共实现。
 *
 * @param digestSize 输出长度（32 / 20 / 16）
 */
static jbyteArray edap_digest_common(JNIEnv *env, jbyteArray data, jint offset, jint len,
        edap_digest_fn fn, int digestSize)
{
    if (data == NULL || offset < 0 || len < 0) {
        return NULL;
    }

    jbyte *dataPtr = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (dataPtr == NULL) {
        return NULL;
    }

    unsigned char result[EDAP_SHA256_DIGEST_SIZE];
    fn((const unsigned char *)(dataPtr + offset), (size_t)len, result);

    (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr, JNI_ABORT);

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)digestSize);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)digestSize, (const jbyte *)result);
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
