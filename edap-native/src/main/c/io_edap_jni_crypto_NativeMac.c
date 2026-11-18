/*
 * HMAC native impl（SHA-256 / SHA-1）。
 *
 * 与 NativeHmacSha256.c 同样的思路：legacy SHA*_ 一次性 API，无 provider dispatch。
 * HMAC 展开逻辑在 edap_jni_common.h 里，与 NativeHmacSha256.c 共用同一份实现。
 *
 * ★ 这两个入口是**遗留的一次性入口**（edap-crypto 的 MAC provider 已改走
 *   NativeStream 的流式接口）。本文件里的 HMAC 本体用的是 legacy 的
 *   SHA256_Init/Update/Final（不是一次性 API）⇒ 在 Solaris 上没有
 *   一次性 API 那 ~7µs 的 provider dispatch 开销（见
 *   edap-crypto/doc/DESIGN.md §10.11.2）。
 */
#include <jni.h>
#include <openssl/sha.h>
#include <string.h>

#include "edap_jni_array.h"
#include "edap_jni_common.h"
#include "io_edap_jni_crypto_NativeMac.h"

/**
 * key → k_pad 的回调上下文。
 */
typedef struct {
    int isSha1;
    unsigned char k_pad[EDAP_SHA256_BLOCK_SIZE];
} edap_mac_key_ctx;

/**
 * edap_jni_consume_bytes 的回调：拿到 key ⇒ RFC 2104 §2 的 key 预处理。
 */
static void edap_mac_key_apply(void *vctx, const unsigned char *key, size_t keyLen) {
    edap_mac_key_ctx *c = (edap_mac_key_ctx *)vctx;
    if (c->isSha1) {
        edap_sha1_key_pad(key, keyLen, c->k_pad);
    } else {
        edap_sha256_key_pad(key, keyLen, c->k_pad);
    }
}

/**
 * data → MAC 结果的回调上下文。
 */
typedef struct {
    int isSha1;
    const unsigned char *k_pad;
    unsigned char result[EDAP_SHA256_DIGEST_SIZE];
} edap_mac_data_ctx;

/**
 * edap_jni_consume_bytes 的回调：拿到消息 ⇒ 算 HMAC。
 */
static void edap_mac_data_apply(void *vctx, const unsigned char *data, size_t len) {
    edap_mac_data_ctx *c = (edap_mac_data_ctx *)vctx;
    if (c->isSha1) {
        edap_hmac_sha1(c->k_pad, data, len, c->result);
    } else {
        edap_hmac_sha256(c->k_pad, data, len, c->result);
    }
}

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeMac_hmacSha2560
  (JNIEnv *env, jclass cls,
   jbyteArray key, jbyteArray data, jint offset, jint len)
{
    if (key == NULL || data == NULL || offset < 0 || len < 0) {
        return NULL;
    }

    /* key 长度在进临界区之前取 —— 临界区里不做 JNI 调用 */
    jsize keyLen = (*env)->GetArrayLength(env, key);

    edap_mac_key_ctx kctx;
    kctx.isSha1 = 0;
    if (!edap_jni_consume_bytes(env, key, 0, keyLen, edap_mac_key_apply, &kctx)) {
        return NULL;
    }

    edap_mac_data_ctx dctx;
    dctx.isSha1 = 0;
    dctx.k_pad = kctx.k_pad;
    if (!edap_jni_consume_bytes(env, data, offset, len, edap_mac_data_apply, &dctx)) {
        return NULL;
    }

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)EDAP_SHA256_DIGEST_SIZE);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)EDAP_SHA256_DIGEST_SIZE, (const jbyte *)dctx.result);
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

    edap_mac_key_ctx kctx;
    kctx.isSha1 = 1;
    if (!edap_jni_consume_bytes(env, key, 0, keyLen, edap_mac_key_apply, &kctx)) {
        return NULL;
    }

    edap_mac_data_ctx dctx;
    dctx.isSha1 = 1;
    dctx.k_pad = kctx.k_pad;
    if (!edap_jni_consume_bytes(env, data, offset, len, edap_mac_data_apply, &dctx)) {
        return NULL;
    }

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)EDAP_SHA1_DIGEST_SIZE);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)EDAP_SHA1_DIGEST_SIZE, (const jbyte *)dctx.result);
    }
    return ret;
}
