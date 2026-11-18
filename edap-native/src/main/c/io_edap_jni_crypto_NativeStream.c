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
 *   init   → 取一个上下文（优先从本线程的空闲池），返回句柄
 *   update → 就地更新
 *   finish → 算出结果并**释放上下文**（句柄随即失效！）
 *   free   → 不算结果直接释放（放弃/异常路径）
 *   ⇒ 「finish 之后不能再 free」是调用方的约定。
 *   ⇒ 「释放」= 归还到本线程的空闲池（不是直接 free）——
 *     归还后的上下文可能被别的会话取走并改写，所以句柄一旦失效就不可再用。
 */
#include <jni.h>
#include <openssl/crypto.h>
#include <openssl/md5.h>
#include <openssl/sha.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "edap_jni_array.h"
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
 *   a          工作上下文：摘要时是摘要上下文；HMAC 时是 inner 上下文
 *   b          HMAC 的 outer 上下文（摘要时不用）
 *   alg        算法编号（决定用哪套 Init/Update/Final）
 *   state      生命周期标记（EDAP_CTX_LIVE / EDAP_CTX_POOLED）
 *   inner_pad  HMAC 的 ipad（K xor 0x36）
 *   outer_pad  HMAC 的 opad（K xor 0x5c）
 *
 * ★ 布局（2026-11-17 调整）：两个 union 挪到结构体**最前面**。
 *   实测 sizeof(SHA256_CTX)=112 / SHA_CTX=96 / MD5_CTX=92。
 *   旧布局（alg 在最前）下 union a 的偏移是 **132 —— 只有 4 字节对齐**；
 *   新布局下 a 在偏移 0、b 在偏移 112，而 malloc 返回的地址是 16 字节对齐
 *   ⇒ 两个工作上下文都天然 **8 字节对齐**（旧布局下两者都只是 4 字节对齐）。
 */
typedef struct {
    union {
        SHA256_CTX c256;
        SHA_CTX c1;
        MD5_CTX c5;
    } a;
    union {
        SHA256_CTX c256;
        SHA_CTX c1;
    } b;
    int alg;
    int state;
    unsigned char inner_pad[EDAP_SHA256_BLOCK_SIZE];
    unsigned char outer_pad[EDAP_SHA256_BLOCK_SIZE];
} edap_stream_ctx;

/**
 * ★ 对齐护栏：两个 union 都必须满足 SHA256_CTX 的对齐要求。
 *
 * <p>union a 在偏移 0、union b 在 sizeof(a) —— 而 sizeof(union) 必然是 union
 * 对齐要求的倍数 ⇒ 只要基址对齐（malloc 保证 16 字节），两个工作上下文都天然对齐。
 * 旧布局（alg / state 在最前）下 union a 的偏移是 132 ⇒ 只有 4 字节对齐。</p>
 *
 * <p><b>对齐要求随平台 / 头文件而变</b>：{@code SHA256_CTX} 的对齐要求由
 * {@code SHA_LONG} 的宽度决定。实测（macOS，OpenSSL 3.6.4）{@code SHA_LONG}
 * 是 {@code unsigned int}（4 字节）⇒ {@code sizeof(SHA256_CTX)}=112、
 * 对齐要求 4 字节 ⇒ 此时**旧布局（union a 在偏移 132）本来就满足**，
 * 这一项在当前 OpenSSL 3.x 上是 no-op。但部分 OpenSSL 1.x 的 {@code sha.h}
 * 按平台把 {@code SHA_LONG} 定成 8 字节 ⇒ 那时旧布局只剩 4 字节对齐，
 * 未对齐的 8 字节成员访问在 SPARC 上有 SIGBUS 风险。
 * ⇒ 这里用 {@code __alignof__(SHA256_CTX)} 让断言**按平台自动取值**，
 * 不硬编码任何对齐数字；换平台 / 换 OpenSSL 版本也不会悄悄退化。</p>
 *
 * <p>★ 用「负长度数组」而不是 {@code _Static_assert}：Solaris 上的 gcc 版本较老，
 * 默认方言未必是 C11 ⇒ {@code _Static_assert} 可能不可用，而负长度数组
 * 是 C89 就有的写法，任何编译器都会报错。</p>
 */
typedef char edap_ctx_align_check[
        (offsetof(edap_stream_ctx, a) == 0
                && offsetof(edap_stream_ctx, b) % __alignof__(SHA256_CTX) == 0) ? 1 : -1];

/* 小载荷阈值（EDAP_SMALL_PAYLOAD_MAX）与取数 helper 在 edap_jni_array.h ——
 * 摘要、MAC、一次性入口共用同一份阈值与同一套分流逻辑。 */

/* ------------------------------------------------------------------ */
/* 上下文池（按线程复用）                                             */
/* ------------------------------------------------------------------ */

/**
 * ★ 为什么需要池：一次摘要操作 = 一次 malloc(360) + 一次 free(360)。
 *   实测（macOS / JDK 17）malloc+free ≈ **25.9 ns/op**，而一次 64B 的
 *   SHA-256 全程才 ~60 ns ⇒ 分配器占了近 1/3 的固定成本。
 *   改成「按线程复用一个空闲上下文」，成本降到 ~3.5 ns（pthread_getspecific
 *   + 一次数组 push/pop）⇒ **净省 ~22 ns/op**。
 *
 * ★ 为什么池是**线程私有**的：
 *   上下文在任一时刻只被一个会话持有 ⇒ 线程私有池天然无竞争、无锁。
 *   （用全局池就得加锁，锁成本会吃掉大半收益。）
 *
 * ★ 池用 pthread_key（不是 __thread）：
 *   __thread 变量在线程退出时无法回收 ⇒ 线程池里的上下文会泄漏；
 *   pthread_key_create 的析构器会在线程退出时自动 free 掉。
 *
 * ★ 池有上限（EDAP_CTX_POOL_MAX）：一个线程同时最多持有这么多上下文，
 *   超出的一律直接 free ⇒ 内存占用有界，不会因为「池」而无限增长。
 */
#define EDAP_CTX_POOL_MAX 8

/* 上下文生命周期标记 —— 用来挡住「重复释放」 */
#define EDAP_CTX_LIVE   0 /* 正在被某个会话持有 */
#define EDAP_CTX_POOLED 1 /* 已归还到池里（句柄对调用方已失效） */

typedef struct {
    edap_stream_ctx *slots[EDAP_CTX_POOL_MAX];
    int count;
} edap_ctx_pool;

static pthread_key_t g_ctx_pool_key;
static pthread_once_t g_ctx_pool_once = PTHREAD_ONCE_INIT;
static int g_ctx_pool_key_ok = 0;

/**
 * 线程退出时的析构器：把池里剩下的上下文全部 free。
 */
static void edap_ctx_pool_destroy(void *arg) {
    edap_ctx_pool *pool = (edap_ctx_pool *)arg;
    if (pool == NULL) {
        return;
    }
    for (int i = 0; i < pool->count; i++) {
        free(pool->slots[i]);
    }
    free(pool);
}

static void edap_ctx_pool_key_init(void) {
    g_ctx_pool_key_ok = (pthread_key_create(&g_ctx_pool_key, edap_ctx_pool_destroy) == 0);
}

/**
 * 取当前线程的池；没有就返回 NULL（调用方回落到 malloc/free）。
 */
static edap_ctx_pool *edap_ctx_pool_current(void) {
    pthread_once(&g_ctx_pool_once, edap_ctx_pool_key_init);
    if (!g_ctx_pool_key_ok) {
        return NULL;
    }
    return (edap_ctx_pool *)pthread_getspecific(g_ctx_pool_key);
}

/**
 * 归还一个上下文：优先放回本线程的池，池满 / 池不可用 ⇒ 直接 free。
 *
 * <p>★ 重复释放是**静默忽略**（而不是二次 free）：
 * 上下文被归还后句柄对调用方已失效，但一旦进入池就可能被别的会话拿走并**改写**，
 * 此时再来一次 free 就是「free 一块正在被别人用的内存」⇒ 比原来的二次 free 更危险。
 * state 标记让这种情况退化成 no-op。</p>
 */
static void edap_ctx_release(edap_stream_ctx *c) {
    if (c == NULL) {
        return;
    }
    if (c->state != EDAP_CTX_LIVE) {
        return; /* 已经归还过了 ⇒ 忽略 */
    }
    c->state = EDAP_CTX_POOLED;

    /* ★ 清掉 HMAC 的 ipad / opad —— 它们是 key 的简单 XOR，
     *   留在池里等于把 key 留在进程内存里（可被 dump 出来）。
     *   ★ 为什么必须在这里清：上下文是**按线程复用**的，
     *     「取用时 memset 一次」挡不住「归还后、下次取用前」这段窗口
     *     （key 材料就在池里躺着）。归还时清零才是无窗口的做法。
     *   ※ 纯摘要不用 pad ⇒ 不清（省掉 128 字节的写）。 */
    if (c->alg == EDAP_ALG_HMAC_SHA256 || c->alg == EDAP_ALG_HMAC_SHA1) {
        OPENSSL_cleanse(c->inner_pad, EDAP_SHA256_BLOCK_SIZE);
        OPENSSL_cleanse(c->outer_pad, EDAP_SHA256_BLOCK_SIZE);
    }

    edap_ctx_pool *pool = edap_ctx_pool_current();
    if (pool == NULL) {
        pool = (edap_ctx_pool *)calloc(1, sizeof(*pool));
        if (pool == NULL) {
            free(c);
            return;
        }
        if (pthread_setspecific(g_ctx_pool_key, pool) != 0) {
            free(pool);
            free(c);
            return;
        }
    }
    if (pool->count < EDAP_CTX_POOL_MAX) {
        pool->slots[pool->count++] = c;
        return;
    }
    free(c);
}

/**
 * 取一个上下文：优先从本线程的池里拿，没有就 malloc。
 *
 * <p>取出来的上下文**内容是上一轮留下的残值**（不是零）——
 * 但这没关系：每次使用前都会重新 Init，而 OpenSSL 的
 * {@code SHA256_Init / SHA1_Init / MD5_Init} 都把上下文**全量复位**
 * （实测：填 0xAA 后 Init，残留 0xAA 字节数为 0）；
 * HMAC 的两个 pad 由 {@code edap_mac_pads} 全写；outer 上下文 b 在
 * {@code edap_mac_final} 里 Init 之后才用 ⇒ <b>不存在读未初始化内存的路径</b>。</p>
 */
static edap_stream_ctx *edap_ctx_acquire(void) {
    edap_ctx_pool *pool = edap_ctx_pool_current();
    if (pool != NULL && pool->count > 0) {
        edap_stream_ctx *c = pool->slots[--pool->count];
        c->state = EDAP_CTX_LIVE;
        return c;
    }
    edap_stream_ctx *c = (edap_stream_ctx *)malloc(sizeof(edap_stream_ctx));
    if (c != NULL) {
        c->state = EDAP_CTX_LIVE;
    }
    return c;
}

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
 * 「把 key 展开成 ipad/opad」的回调上下文。
 */
typedef struct {
    edap_stream_ctx *c;
} edap_mac_pad_ctx;

/**
 * edap_jni_consume_bytes 的回调：拿到 key ⇒ 展开 ipad/opad。
 */
static void edap_mac_pad_apply(void *vctx, const unsigned char *key, size_t keyLen) {
    edap_mac_pad_ctx *p = (edap_mac_pad_ctx *)vctx;
    edap_mac_pads(p->c, key, keyLen);
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
 * 「把一段数据喂进上下文」的回调上下文。
 */
typedef struct {
    edap_stream_ctx *c;
    int isMac;
} edap_update_ctx;

/**
 * edap_jni_consume_bytes 的回调：拿到一段数据 ⇒ 喂给摘要 / MAC 上下文。
 */
static void edap_update_apply(void *vctx, const unsigned char *data, size_t len) {
    edap_update_ctx *u = (edap_update_ctx *)vctx;
    if (u->isMac) {
        edap_mac_update(u->c, data, len);
    } else {
        edap_digest_update(u->c, data, len);
    }
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
 * <p>★ 上下文来自**线程私有的空闲池**（见 edap_ctx_acquire）——
 * 池空时才 malloc ⇒ 省掉每次操作的 malloc/free（~22 ns/op）。</p>
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

    edap_stream_ctx *c = edap_ctx_acquire();
    if (c == NULL) {
        return 0;
    }
    /* ★ 不 memset 整个上下文：池里取出来的上下文带着上一轮的内容，
     *   但每次使用前都会重新 Init —— OpenSSL 的 Init 把上下文**全量复位**
     *   （实测：填 0xAA 后 Init，残留 0xAA 字节数为 0），
     *   HMAC 的两个 pad 由 edap_mac_pads 全写，outer 上下文 b 在收尾时 Init 之后才用
     *   ⇒ <b>不存在读未初始化内存的路径</b>。
     *   省掉的是每次操作 360（macOS）/ 568（Solaris）字节的 memset。
     *   ※ 代价：pad 不再在「取用时」被清零 ⇒ 改到「归还时」清（见 edap_ctx_release）。 */
    c->alg = (int)alg;
    c->state = EDAP_CTX_LIVE;

    int ok;
    if (isMac) {
        /* key 通常 16~64 字节 ⇒ 小载荷路径（GetByteArrayRegion，比 critical section 省 ~47 ns） */
        edap_mac_pad_ctx padCtx;
        padCtx.c = c;
        if (!edap_jni_consume_bytes(env, key, 0, keyLen, edap_mac_pad_apply, &padCtx)) {
            edap_ctx_release(c);
            return 0;
        }
        ok = edap_mac_inner_init(c);
    } else {
        ok = edap_digest_init(c);
    }
    if (!ok) {
        edap_ctx_release(c);
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
 * <p>★ 小载荷（≤ 1 KB）走 {@code GetByteArrayRegion} 拷到栈缓冲，
 * 大载荷走 critical section 直接喂给 OpenSSL（不拷贝、不落 Java 堆）。
 * 两条路的取舍见 {@link EDAP_SMALL_PAYLOAD_MAX} 的注释。</p>
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
    int created = 0;
    if (ctx == 0) {
        ctx = edap_stream_create(env, alg, key, keyLen);
        if (ctx == 0) {
            return 0;
        }
        created = 1;
    }
    if (data == NULL || offset < 0 || len <= 0) {
        return ctx;
    }
    edap_stream_ctx *c = (edap_stream_ctx *)(intptr_t)ctx;

    /* 小载荷走 GetByteArrayRegion（拷栈缓冲），大载荷走 critical section（不拷贝）。
     * 分流逻辑与阈值在 edap_jni_array.h —— 摘要 / MAC / 一次性入口共用同一份。
     * ★ 越界时 GetByteArrayRegion 会抛 ArrayIndexOutOfBoundsException
     *   （与 JCE 的 MessageDigest 行为一致）—— 比旧的 critical section
     *   越界读（静默读到邻接堆内存）更安全。 */
    edap_update_ctx uctx;
    uctx.c = c;
    uctx.isMac = (c->alg == EDAP_ALG_HMAC_SHA256 || c->alg == EDAP_ALG_HMAC_SHA1);
    if (!edap_jni_consume_bytes(env, data, offset, len, edap_update_apply, &uctx)) {
        if (created) {
            edap_ctx_release(c);
        }
        return 0;
    }
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
    /* 算完结果再归还（归还后句柄对调用方失效 —— 与旧的 free 语义一致） */
    edap_ctx_release(c);
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
 *
 * <p>★ 释放 = 归还到本线程的空闲池（不是直接 free）——
 * 下一次建上下文就能复用，省掉 malloc/free。</p>
 */
JNIEXPORT void JNICALL Java_io_edap_jni_crypto_NativeStream_free
  (JNIEnv *env, jclass cls, jlong ctx)
{
    if (ctx == 0) {
        return;
    }
    edap_ctx_release((edap_stream_ctx *)(intptr_t)ctx);
}
