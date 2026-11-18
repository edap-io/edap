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
 * 展开逻辑已抽到 edap_jni_common.h，与 NativeMac.c 共用同一份实现。
 *
 * ★★ 本入口是 edap-auth-jwt 的 HS256 **生产路径**
 *   （io.edap.auth.jwt.algorithm.HmacSha256.HmacSha256Native），
 *   所以取数路径（小载荷 region / 大载荷 critical）的分流阈值与
 *   NativeStream、NativeDigest、NativeMac 共用同一份（edap_jni_array.h）。
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

#include "edap_jni_array.h"
#include "edap_jni_common.h"
#include "io_edap_jni_crypto_NativeHmacSha256.h"

/**
 * key → k_pad 的回调上下文。
 */
typedef struct {
    unsigned char k_pad[EDAP_SHA256_BLOCK_SIZE];
} edap_hmac_key_ctx;

/**
 * edap_jni_consume_bytes 的回调：拿到 key ⇒ RFC 2104 §2 的 key 预处理。
 */
static void edap_hmac_key_apply(void *vctx, const unsigned char *key, size_t keyLen) {
    edap_hmac_key_ctx *c = (edap_hmac_key_ctx *)vctx;
    edap_sha256_key_pad(key, keyLen, c->k_pad);
}

/**
 * data → MAC 结果的回调上下文。
 */
typedef struct {
    const unsigned char *k_pad;
    unsigned char result[EDAP_SHA256_DIGEST_SIZE];
} edap_hmac_data_ctx;

/**
 * edap_jni_consume_bytes 的回调：拿到消息 ⇒ 算 HMAC。
 */
static void edap_hmac_data_apply(void *vctx, const unsigned char *data, size_t len) {
    edap_hmac_data_ctx *c = (edap_hmac_data_ctx *)vctx;
    edap_hmac_sha256(c->k_pad, data, len, c->result);
}

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeHmacSha256_sign0
  (JNIEnv *env, jclass cls,
   jbyteArray key, jint keyLen,
   jbyteArray data, jint offset, jint len)
{
    if (key == NULL || data == NULL || keyLen < 0 || offset < 0 || len < 0) {
        return NULL;
    }

    /*
     * 取 key：≤ 1 KB 拷栈缓冲（避开 critical section 的 ~102ns 固定成本），
     * > 1 KB 走 critical section（不拷贝）。
     * 复制完后立刻释放，不在临界区里做 SHA256 计算。
     * key ≤ 64 bytes：直接 memcpy + zero-pad
     * key > 64 bytes：SHA256(K) → 32 bytes hash + zero-pad（RFC 2104 §2）
     */
    edap_hmac_key_ctx kctx;
    if (!edap_jni_consume_bytes(env, key, 0, keyLen, edap_hmac_key_apply, &kctx)) {
        return NULL;
    }

    /*
     * 取 data：同样的分流（key 通常 16~64 字节，载荷大小不限）。
     * HMAC(K, m) = H((K xor opad) || H((K xor ipad) || m))
     * ipad = 0x36 * 64, opad = 0x5c * 64
     */
    edap_hmac_data_ctx dctx;
    dctx.k_pad = kctx.k_pad;
    if (!edap_jni_consume_bytes(env, data, offset, len, edap_hmac_data_apply, &dctx)) {
        return NULL;
    }

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)EDAP_SHA256_DIGEST_SIZE);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)EDAP_SHA256_DIGEST_SIZE,
                (const jbyte *)dctx.result);
    }
    return ret;
}
