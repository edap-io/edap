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
 */
#include <jni.h>
#include <openssl/md5.h>
#include <openssl/sha.h>
#include <string.h>

#include "edap_jni_common.h"
#include "io_edap_jni_crypto_NativeDigest.h"

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeDigest_digest0
  (JNIEnv *env, jclass cls,
   jstring algorithm, jbyteArray data, jint offset, jint len)
{
    if (algorithm == NULL || data == NULL || offset < 0 || len < 0) {
        return NULL;
    }

    const char *algChars = (*env)->GetStringUTFChars(env, algorithm, NULL);
    if (algChars == NULL) {
        return NULL;
    }

    int digestSize;
    if (strcmp(algChars, "SHA256") == 0) {
        digestSize = EDAP_SHA256_DIGEST_SIZE;
    } else if (strcmp(algChars, "SHA1") == 0) {
        digestSize = EDAP_SHA1_DIGEST_SIZE;
    } else if (strcmp(algChars, "MD5") == 0) {
        digestSize = EDAP_MD5_DIGEST_SIZE;
    } else {
        /* 不支持的算法 —— 释放字符串后返回 NULL，调用方按「不可用」处理 */
        (*env)->ReleaseStringUTFChars(env, algorithm, algChars);
        return NULL;
    }
    (*env)->ReleaseStringUTFChars(env, algorithm, algChars);

    jbyte *dataPtr = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (dataPtr == NULL) {
        return NULL;
    }

    const unsigned char *input = (const unsigned char *)(dataPtr + offset);
    unsigned char result[EDAP_SHA256_DIGEST_SIZE];
    switch (digestSize) {
        case EDAP_SHA256_DIGEST_SIZE:
            SHA256(input, (size_t)len, result);
            break;
        case EDAP_SHA1_DIGEST_SIZE:
            SHA1(input, (size_t)len, result);
            break;
        default:
            MD5(input, (size_t)len, result);
            break;
    }

    (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr, JNI_ABORT);

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)digestSize);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)digestSize, (const jbyte *)result);
    }
    return ret;
}
