/*
 * HMAC-SHA256 native impl, backed by OpenSSL libcrypto.
 *
 * Stateless one-shot HMAC: a single JNI call computes HMAC(key, data[offset..offset+len]).
 * Performance 比 Java Mac.reset()+update()+doFinal() 省 4 次 JNI 切换。
 *
 * 若 profile 显示 key 初始化是热点，再升级为 thread-local HMAC_CTX 复用。
 */
#include <jni.h>
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <string.h>

#include "io_edap_jni_crypto_NativeHmacSha256.h"

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeHmacSha256_sign0
  (JNIEnv *env, jclass cls, jbyteArray key, jbyteArray data, jint offset, jint len)
{
    if (key == NULL || data == NULL) {
        return NULL;
    }
    jbyte *keyBytes = (*env)->GetByteArrayElements(env, key, NULL);
    if (keyBytes == NULL) {
        return NULL;
    }
    jsize keyLen = (*env)->GetArrayLength(env, key);

    jbyte *dataBytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (dataBytes == NULL) {
        (*env)->ReleaseByteArrayElements(env, key, keyBytes, JNI_ABORT);
        return NULL;
    }

    unsigned char result[EVP_MAX_MD_SIZE];
    unsigned int resultLen = 0;

    /* OpenSSL 3.x HMAC() is one-shot: init + update + final in one call.
       libcrypto handle is internal; no need to manage EVP_MAC_CTX here. */
    HMAC(EVP_sha256(),
         (const unsigned char *)keyBytes, (size_t)keyLen,
         (const unsigned char *)(dataBytes + offset), (size_t)len,
         result, &resultLen);

    (*env)->ReleaseByteArrayElements(env, key, keyBytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, data, dataBytes, JNI_ABORT);

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)resultLen);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)resultLen, (const jbyte *)result);
    }
    return ret;
}
