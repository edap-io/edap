/*
 * 流式摘要 / MAC native impl（SHA-256 / SHA-1 / MD5 / HMAC-SHA256 / HMAC-SHA1）。
 *
 * 为什么要有这一层：
 *   早先的 native 摘要只有「一次性」入口（digestSha2560(data, off, len)），
 *   而 edap-crypto 的 DigestSession 是流式的 ⇒ 累加在 Java 侧做
 *   （ByteAccumulator：整份拷贝一遍）⇒ digest() 时再一次性交给 native。
 *   ★ 实测（Solaris/SPARC，Oracle JDK 17）：64KB 载荷光「Java 侧累积」就要
 *     ~17.7µs（一次 64KB 分配 + 一次 64KB arraycopy），与一次 SHA-256 本体同量级。
 *   ⇒ 改成 native 侧持有摘要上下文，数据喂一次算一次 ⇒ 这 17.7µs 直接消失。
 *
 * 为什么用 legacy 的 SHA256_Init/Update/Final 而不是 EVP：
 *   见 edap-native/doc/NATIVE_DESIGN.md §6.6 —— OpenSSL 3.x 的 provider dispatch
 *   （evp_generic_fetch）在多线程下退化（16t profile 17.5% CPU）。
 *
 * ★ 为什么 HMAC 也放这里（而不是各拆一个类）：
 *   摘要与 HMAC 的会话形状完全一样（init / update / finish / free），
 *   差别只在「init 时要不要 key」⇒ 合成一个上下文结构 + 一套入口，
 *   Java 侧也只需绑定一次（少一份 MethodHandle 缓存、少一套探测逻辑）。
 *
 * ★ 上下文句柄的生命周期（Java 侧必须照这个来）：
 *   init   → malloc 一个上下文，返回句柄
 *   update → 就地更新
 *   finish → 算出结果并**释放上下文**（句柄随即失效！）
 *   free   → 不算结果直接释放（放弃/异常路径）
 *   ⇒ 「finish 之后不能再 free」是调用方的约定。
 */
#include <jni.h>
#include <openssl/md5.h>
#include <openssl/sha.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "edap_jni_common.h"
#include "io_edap_jni_crypto_NativeStream.h"

/*
 * 算法编号 —— 与 Java 侧 io.edap.jni.crypto.NativeStream 的常量一一对应。
 * ★ 不用字符串：每次调用 GetStringUTFChars + strcmp 在 Solaris/SPARC 上约 9.5µs，
 *   是 native 摘要固定成本的大头（见 io_edap_jni_crypto_NativeDigest.c 的说明）。
 */
#define EDAP_ALG_SHA256      0
#define EDAP_ALG_SHA1        1
#define EDAP_ALG_MD5         2
#define EDAP_ALG_HMAC_SHA256 3
#define EDAP_ALG_HMAC_SHA1   4

#define EDAP_ALG_MAX         4

/**
 * 流式上下文 —— 摘要与 HMAC 共用一个结构。
 *
 * 字段说明：
 *   alg        算法编号（决定用哪套 Init/Update/Final）
 *   inner_pad  HMAC 的 ipad（K xor 0x36）
 *   outer_pad  HMAC 的 opad（K xor 0x5c）
 *   a          工作上下文：摘要时是摘要上下文；HMAC 时是 inner 上下文
 *   b          HMAC 的 outer 上下文（摘要时不用）
 */
typedef struct {
    int alg;
    unsigned char inner_pad[EDAP_SHA256_BLOCK_SIZE];
    unsigned char outer_pad[EDAP_SHA256_BLOCK_SIZE];
    union {
        SHA256_CTX c256;
        SHA_CTX c1;
        MD5_CTX c5;
    } a;
    union {
        SHA256_CTX c256;
        SHA_CTX c1;
    } b;
} edap_stream_ctx;

/* ------------------------------------------------------------------ */
/* 摘要                                                                */
/* ------------------------------------------------------------------ */

static int edap_digest_init(edap_stream_ctx *c) {
    switch (c->alg) {
        case EDAP_ALG_SHA256: return SHA256_Init(&c->a.c256);
        case EDAP_ALG_SHA1:   return SHA1_Init(&c->a.c1);
        case EDAP_ALG_MD5:    return MD5_Init(&c->a.c5);
        default: return 0;
    }
}

static int edap_digest_update(edap_stream_ctx *c, const unsigned char *data, size_t len) {
    switch (c->alg) {
        case EDAP_ALG_SHA256: return SHA256_Update(&c->a.c256, data, len);
        case EDAP_ALG_SHA1:   return SHA1_Update(&c->a.c1, data, len);
        case EDAP_ALG_MD5:    return MD5_Update(&c->a.c5, data, len);
        default: return 0;
    }
}

static int edap_digest_final(edap_stream_ctx *c, unsigned char *out) {
    switch (c->alg) {
        case EDAP_ALG_SHA256: return SHA256_Final(out, &c->a.c256);
        case EDAP_ALG_SHA1:   return SHA1_Final(out, &c->a.c1);
        case EDAP_ALG_MD5:    return MD5_Final(out, &c->a.c5);
        default: return 0;
    }
}

/* ------------------------------------------------------------------ */
/* HMAC                                                                */
/* ------------------------------------------------------------------ */

/**
 * RFC 2104 §2 的 key 预处理 + ipad/opad 展开。
 */
static void edap_mac_pads(edap_stream_ctx *c, const unsigned char *key, size_t keyLen) {
    unsigned char k_pad[EDAP_SHA256_BLOCK_SIZE];
    if (c->alg == EDAP_ALG_HMAC_SHA1) {
        edap_sha1_key_pad(key, keyLen, k_pad);
    } else {
        edap_sha256_key_pad(key, keyLen, k_pad);
    }
    for (int i = 0; i < EDAP_SHA256_BLOCK_SIZE; i++) {
        c->inner_pad[i] = (unsigned char)(k_pad[i] ^ 0x36);
        c->outer_pad[i] = (unsigned char)(k_pad[i] ^ 0x5c);
    }
}

/**
 * inner 上下文初始化 = H_init(K xor ipad || …)。
 */
static int edap_mac_inner_init(edap_stream_ctx *c) {
    if (c->alg == EDAP_ALG_HMAC_SHA1) {
        if (!SHA1_Init(&c->a.c1)) {
            return 0;
        }
        return SHA1_Update(&c->a.c1, c->inner_pad, EDAP_SHA256_BLOCK_SIZE);
    }
    if (!SHA256_Init(&c->a.c256)) {
        return 0;
    }
    return SHA256_Update(&c->a.c256, c->inner_pad, EDAP_SHA256_BLOCK_SIZE);
}

static int edap_mac_update(edap_stream_ctx *c, const unsigned char *data, size_t len) {
    if (c->alg == EDAP_ALG_HMAC_SHA1) {
        return SHA1_Update(&c->a.c1, data, len);
    }
    return SHA256_Update(&c->a.c256, data, len);
}

/**
 * HMAC 收尾：inner 摘要 → outer 上下文 → 结果。
 */
static int edap_mac_final(edap_stream_ctx *c, unsigned char *out) {
    unsigned char inner_hash[EDAP_SHA256_DIGEST_SIZE];
    if (c->alg == EDAP_ALG_HMAC_SHA1) {
        if (!SHA1_Final(inner_hash, &c->a.c1)) {
            return 0;
        }
        if (!SHA1_Init(&c->b.c1)) {
            return 0;
        }
        if (!SHA1_Update(&c->b.c1, c->outer_pad, EDAP_SHA256_BLOCK_SIZE)) {
            return 0;
        }
        if (!SHA1_Update(&c->b.c1, inner_hash, EDAP_SHA1_DIGEST_SIZE)) {
            return 0;
        }
        return SHA1_Final(out, &c->b.c1);
    }
    if (!SHA256_Final(inner_hash, &c->a.c256)) {
        return 0;
    }
    if (!SHA256_Init(&c->b.c256)) {
        return 0;
    }
    if (!SHA256_Update(&c->b.c256, c->outer_pad, EDAP_SHA256_BLOCK_SIZE)) {
        return 0;
    }
    if (!SHA256_Update(&c->b.c256, inner_hash, EDAP_SHA256_DIGEST_SIZE)) {
        return 0;
    }
    return SHA256_Final(out, &c->b.c256);
}

/* ------------------------------------------------------------------ */
/* JNI 入口                                                            */
/* ------------------------------------------------------------------ */

/**
 * 建一个流式上下文。
 *
 * @param alg    算法编号（EDAP_ALG_*）
 * @param key    HMAC 的 key；纯摘要传 NULL
 * @param keyLen key 长度（字节）
 * @return 上下文句柄；失败返回 0
 */
static jlong edap_stream_create(JNIEnv *env, jint alg, jbyteArray key, jint keyLen) {
    if (alg < 0 || alg > EDAP_ALG_MAX) {
        return 0;
    }
    int isMac = (alg == EDAP_ALG_HMAC_SHA256 || alg == EDAP_ALG_HMAC_SHA1);
    if (isMac && (key == NULL || keyLen <= 0)) {
        return 0;
    }

    edap_stream_ctx *c = (edap_stream_ctx *)malloc(sizeof(edap_stream_ctx));
    if (c == NULL) {
        return 0;
    }
    memset(c, 0, sizeof(*c));
    c->alg = (int)alg;

    int ok;
    if (isMac) {
        jbyte *keyPtr = (*env)->GetPrimitiveArrayCritical(env, key, NULL);
        if (keyPtr == NULL) {
            free(c);
            return 0;
        }
        edap_mac_pads(c, (const unsigned char *)keyPtr, (size_t)keyLen);
        (*env)->ReleasePrimitiveArrayCritical(env, key, keyPtr, JNI_ABORT);
        ok = edap_mac_inner_init(c);
    } else {
        ok = edap_digest_init(c);
    }
    if (!ok) {
        free(c);
        return 0;
    }
    return (jlong)(intptr_t)c;
}

/**
 * 追加一段数据；<b>ctx == 0 时顺便把上下文建出来</b>。
 *
 * <p>★ 为什么要把「建上下文」并进「追加数据」：<b>JNI 调用本身在 Solaris/SPARC 上不便宜</b>
 * （一次 native 调用 ≈ 微秒级，而一次 64KB 的 SHA-256 才 ~90μs）。
 * 分成 {@code init} + {@code update} 两个入口 ⇒ 每次操作多一次 JNI 往返。
 * 并进一次调用后，「喂一次数据、收一次尾」只要 <b>2 次</b> native 调用
 * （旧的一次性 API 是 1 次，流式化只多 1 次）。</p>
 *
 * <p>★ 数据在 critical section 里直接喂给 OpenSSL —— 不拷贝、不落 Java 堆。
 * 这正是流式化要省掉的那一次整份拷贝。</p>
 *
 * @param ctx  上下文句柄；0 表示还没有，本次调用会建一个
 * @param alg  算法编号（只在 ctx == 0 时用）
 * @param key  HMAC 的 key（只在 ctx == 0 时用）；纯摘要传 NULL
 * @param keyLen key 长度（字节）
 * @return 上下文句柄（建过之后原样返回）；失败返回 0
 */
JNIEXPORT jlong JNICALL Java_io_edap_jni_crypto_NativeStream_update
  (JNIEnv *env, jclass cls, jlong ctx, jint alg, jbyteArray key, jint keyLen,
   jbyteArray data, jint offset, jint len)
{
    if (ctx == 0) {
        ctx = edap_stream_create(env, alg, key, keyLen);
        if (ctx == 0) {
            return 0;
        }
    }
    if (data == NULL || offset < 0 || len <= 0) {
        return ctx;
    }
    edap_stream_ctx *c = (edap_stream_ctx *)(intptr_t)ctx;
    jbyte *dataPtr = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (dataPtr == NULL) {
        return ctx;
    }
    const unsigned char *p = (const unsigned char *)(dataPtr + offset);
    if (c->alg == EDAP_ALG_HMAC_SHA256 || c->alg == EDAP_ALG_HMAC_SHA1) {
        edap_mac_update(c, p, (size_t)len);
    } else {
        edap_digest_update(c, p, (size_t)len);
    }
    (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr, JNI_ABORT);
    return ctx;
}

/**
 * 收尾：算出结果并**释放上下文**（句柄随即失效）。
 */
JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeStream_finish
  (JNIEnv *env, jclass cls, jlong ctx)
{
    if (ctx == 0) {
        return NULL;
    }
    edap_stream_ctx *c = (edap_stream_ctx *)(intptr_t)ctx;

    unsigned char result[EDAP_SHA256_DIGEST_SIZE];
    int size = EDAP_SHA256_DIGEST_SIZE;
    int ok;
    switch (c->alg) {
        case EDAP_ALG_SHA1:
            size = EDAP_SHA1_DIGEST_SIZE;
            ok = edap_digest_final(c, result);
            break;
        case EDAP_ALG_MD5:
            size = EDAP_MD5_DIGEST_SIZE;
            ok = edap_digest_final(c, result);
            break;
        case EDAP_ALG_HMAC_SHA1:
            size = EDAP_SHA1_DIGEST_SIZE;
            ok = edap_mac_final(c, result);
            break;
        case EDAP_ALG_HMAC_SHA256:
            size = EDAP_SHA256_DIGEST_SIZE;
            ok = edap_mac_final(c, result);
            break;
        case EDAP_ALG_SHA256:
            size = EDAP_SHA256_DIGEST_SIZE;
            ok = edap_digest_final(c, result);
            break;
        default:
            ok = 0;
            break;
    }
    free(c);
    if (!ok) {
        return NULL;
    }

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)size);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)size, (const jbyte *)result);
    }
    return ret;
}

/**
 * 放弃当前上下文（不算结果直接释放）。
 */
JNIEXPORT void JNICALL Java_io_edap_jni_crypto_NativeStream_free
  (JNIEnv *env, jclass cls, jlong ctx)
{
    if (ctx == 0) {
        return;
    }
    free((void *)(intptr_t)ctx);
}
