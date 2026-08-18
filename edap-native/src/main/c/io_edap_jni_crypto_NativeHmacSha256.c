/*
 * HMAC-SHA256 native impl.
 *
 * 设计思路（详见 edap-native/doc/NATIVE_DESIGN.md §6）：
 *
 * Tier 1（§6.2）：JNI 数组传递优化
 * - GetByteArrayElements → GetPrimitiveArrayCritical
 * - 传 keyLen 代替 GetArrayLength
 * - 反向 Release，data/key 用 JNI_ABORT（无回写）
 * - NewByteArray + SetByteArrayRegion 在 critical section 之外
 *
 * Tier 1.5（§6.5）：OpenSSL HMAC 复用
 * - 进程级 cache EVP_sha256()
 * - pthread_key_t 每线程 cache HMAC_CTX
 * - HMAC_CTX_reset + HMAC_Init_ex/Update/Final
 *
 * Tier 2（§6.6，本版本）：绕开 OpenSSL 3.x provider dispatch
 * 16t profile 关键发现（详见 commit message）：
 *   HMAC_Init_ex 内部会调 evp_md_init_internal(md)；默认 provider 有
 *   dispatch tables (md->prov->dbs != NULL)，导致每次都走：
 *     evp_generic_fetch → inner_evp_generic_fetch → ossl_method_store_cache_get
 *     → ossl_namemap_name2num → ossl_ht_get → pthread_rwlock_{rd,un}lock
 *   单线程就 16% 时间在 dispatch；16 线程因为 rwlock + atomic refcount
 *   退化到 17.5% 在 HMAC_Init_ex、12% 在 rwlock。
 *
 *   Tier 1.5 的 EVP_sha256() cache 完全没用——dispatch 发生在 HMAC_Init_ex
 *   内部，跟 cached md 无关。
 *
 * 改用 OpenSSL 1.1 风格的 legacy SHA256_*（直接 C，无 provider dispatch）；
 * HMAC 手工展开：H((K xor opad) || H((K xor ipad) || m))。
 *
 * Per-thread 优化可选：
 * - 当前实现每次都重算 k_pad / ipad / opad（~128 字节 XOR，几十 cycles）
 * - 16 线程各自独立，不构成竞争点
 * - 进一步省可以缓存 SHA256_CTX（key 已预处理的 inner/outer state），
 *   但这需要 key validation 逻辑；当前 HmacSha256Native Java 端固定 key，
 *   但 sign0 是 static 不强制保证，先保持简单。
 */
#include <jni.h>
#include <openssl/sha.h>   /* legacy SHA256_{Init,Update,Final} — direct C, no provider dispatch */
#include <string.h>

#include "io_edap_jni_crypto_NativeHmacSha256.h"

#define SHA256_BLOCK_SIZE 64   /* SHA-256 block size (bytes) */
#define SHA256_DIGEST_SIZE 32  /* SHA-256 output size (bytes) */

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeHmacSha256_sign0
  (JNIEnv *env, jclass cls,
   jbyteArray key, jint keyLen,
   jbyteArray data, jint offset, jint len)
{
    if (key == NULL || data == NULL || keyLen < 0 || offset < 0 || len < 0) {
        return NULL;
    }

    /*
     * Critical section 1：拿 key 指针，复制到栈上 k_pad[64]
     * - key ≤ 64 bytes：直接 memcpy + zero-pad
     * - key > 64 bytes：SHA256(K) → 32 bytes hash + zero-pad（RFC 2104 §2）
     * 复制完后立即 ReleasePrimitiveArrayCritical，不在 critical 区间做 SHA256 计算
     */
    jbyte *keyPtr = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, key, NULL);
    if (keyPtr == NULL) {
        return NULL;
    }

    unsigned char k_pad[SHA256_BLOCK_SIZE];
    if (keyLen > SHA256_BLOCK_SIZE) {
        SHA256((const unsigned char *)keyPtr, (size_t)keyLen, k_pad);
        memset(k_pad + SHA256_DIGEST_SIZE, 0, SHA256_BLOCK_SIZE - SHA256_DIGEST_SIZE);
    } else {
        memcpy(k_pad, keyPtr, (size_t)keyLen);
        memset(k_pad + keyLen, 0, SHA256_BLOCK_SIZE - keyLen);
    }

    (*env)->ReleasePrimitiveArrayCritical(env, key, keyPtr, JNI_ABORT);

    /*
     * Critical section 2：拿 data 指针，做 HMAC 计算
     * HMAC(K, m) = H((K xor opad) || H((K xor ipad) || m))
     * ipad = 0x36 * 64, opad = 0x5c * 64
     */
    jbyte *dataPtr = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (dataPtr == NULL) {
        return NULL;
    }

    unsigned char inner_pad[SHA256_BLOCK_SIZE];
    unsigned char outer_pad[SHA256_BLOCK_SIZE];
    for (int i = 0; i < SHA256_BLOCK_SIZE; i++) {
        inner_pad[i] = k_pad[i] ^ 0x36;
        outer_pad[i] = k_pad[i] ^ 0x5c;
    }

    /* Inner: H(inner_pad || data) */
    SHA256_CTX inner;
    SHA256_Init(&inner);
    SHA256_Update(&inner, inner_pad, SHA256_BLOCK_SIZE);
    SHA256_Update(&inner, (const unsigned char *)(dataPtr + offset), (size_t)len);

    unsigned char inner_hash[SHA256_DIGEST_SIZE];
    SHA256_Final(inner_hash, &inner);

    /* Outer: H(outer_pad || inner_hash) */
    SHA256_CTX outer;
    SHA256_Init(&outer);
    SHA256_Update(&outer, outer_pad, SHA256_BLOCK_SIZE);
    SHA256_Update(&outer, inner_hash, SHA256_DIGEST_SIZE);

    unsigned char result[SHA256_DIGEST_SIZE];
    SHA256_Final(result, &outer);

    /* 立即 release，反向顺序 */
    (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr, JNI_ABORT);

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)SHA256_DIGEST_SIZE);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)SHA256_DIGEST_SIZE, (const jbyte *)result);
    }
    return ret;
}