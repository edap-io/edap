/*
 * HMAC native impl（SHA-256 / SHA-1）。
 *
 * 与 NativeHmacSha256.c 同样的思路：legacy SHA*_ 一次性 API，无 provider dispatch。
 * HMAC 展开逻辑在 edap_jni_common.h 里，与 NativeHmacSha256.c 共用同一份实现。
 */
#include <jni.h>
#include <openssl/sha.h>
#include <string.h>

#include "edap_jni_common.h"
#include "io_edap_jni_crypto_NativeMac.h"

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeMac_hmacSha2560
  (JNIEnv *env, jclass cls,
   jbyteArray key, jbyteArray data, jint offset, jint len)
{
    if (key == NULL || data == NULL || offset < 0 || len < 0) {
        return NULL;
    }

    /* key 长度在进 critical section 之前取 —— critical 区间里不做 JNI 调用 */
    jsize keyLen = (*env)->GetArrayLength(env, key);

    jbyte *keyPtr = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, key, NULL);
    if (keyPtr == NULL) {
        return NULL;
    }
    unsigned char k_pad[EDAP_SHA256_BLOCK_SIZE];
    edap_sha256_key_pad((const unsigned char *)keyPtr, (size_t)keyLen, k_pad);
    (*env)->ReleasePrimitiveArrayCritical(env, key, keyPtr, JNI_ABORT);

    jbyte *dataPtr = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (dataPtr == NULL) {
        return NULL;
    }
    unsigned char result[EDAP_SHA256_DIGEST_SIZE];
    edap_hmac_sha256(k_pad, (const unsigned char *)(dataPtr + offset), (size_t)len, result);
    (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr, JNI_ABORT);

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)EDAP_SHA256_DIGEST_SIZE);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)EDAP_SHA256_DIGEST_SIZE, (const jbyte *)result);
    }
    return ret;
}

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeMac_hmacSha10
  (JNIEnv *env, jclass cls,
   jbyteArray key, jbyteArray data, jint offset, jint len)
{
    if (key == NULL || data == NULL || offset < 0 || len < 0) {
        return NULL;
    }

    jsize keyLen = (*env)->GetArrayLength(env, key);

    jbyte *keyPtr = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, key, NULL);
    if (keyPtr == NULL) {
        return NULL;
    }
    unsigned char k_pad[EDAP_SHA1_BLOCK_SIZE];
    edap_sha1_key_pad((const unsigned char *)keyPtr, (size_t)keyLen, k_pad);
    (*env)->ReleasePrimitiveArrayCritical(env, key, keyPtr, JNI_ABORT);

    jbyte *dataPtr = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (dataPtr == NULL) {
        return NULL;
    }
    unsigned char result[EDAP_SHA1_DIGEST_SIZE];
    edap_hmac_sha1(k_pad, (const unsigned char *)(dataPtr + offset), (size_t)len, result);
    (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr, JNI_ABORT);

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)EDAP_SHA1_DIGEST_SIZE);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)EDAP_SHA1_DIGEST_SIZE, (const jbyte *)result);
    }
    return ret;
}
