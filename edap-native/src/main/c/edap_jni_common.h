/*
 * edap-native 公共 C 辅助。
 *
 * 只放「纯计算」的部分（HMAC 展开、key 预处理），不放 JNI 调用 ——
 * JNI 的 GetPrimitiveArrayCritical 必须在调用方的临界区里成对出现，
 * 抽进 helper 会让「拿指针 / 释放指针」的边界难以保证。
 *
 * 依赖 legacy 的 SHA256_* / SHA1_*（不走 OpenSSL 3.x 的 provider dispatch）——
 * 理由见 edap-native/doc/NATIVE_DESIGN.md §6.6：
 * HMAC_Init_ex 内部的 evp_generic_fetch 在多线程下退化（16t profile 17.5% CPU）。
 */
#ifndef EDAP_JNI_COMMON_H
#define EDAP_JNI_COMMON_H

#include <openssl/sha.h>
#include <string.h>

#define EDAP_SHA256_BLOCK_SIZE 64  /* SHA-256 block size (bytes) */
#define EDAP_SHA256_DIGEST_SIZE 32 /* SHA-256 output size (bytes) */
#define EDAP_SHA1_BLOCK_SIZE 64    /* SHA-1 block size (bytes) */
#define EDAP_SHA1_DIGEST_SIZE 20   /* SHA-1 output size (bytes) */
#define EDAP_MD5_DIGEST_SIZE 16    /* MD5 output size (bytes) */

/**
 * RFC 2104 §2 的 key 预处理：key ≤ blockSize 时补零，否则先哈希再补零。
 *
 * @param key     原始 key
 * @param keyLen  key 长度（字节）
 * @param k_pad   输出：blockSize 字节的预处理 key
 */
static inline void edap_sha256_key_pad(const unsigned char *key, size_t keyLen,
        unsigned char k_pad[EDAP_SHA256_BLOCK_SIZE]) {
    if (keyLen > EDAP_SHA256_BLOCK_SIZE) {
        SHA256(key, keyLen, k_pad);
        memset(k_pad + EDAP_SHA256_DIGEST_SIZE, 0,
                EDAP_SHA256_BLOCK_SIZE - EDAP_SHA256_DIGEST_SIZE);
    } else {
        memcpy(k_pad, key, keyLen);
        memset(k_pad + keyLen, 0, EDAP_SHA256_BLOCK_SIZE - keyLen);
    }
}

/**
 * SHA-1 版的 key 预处理。
 */
static inline void edap_sha1_key_pad(const unsigned char *key, size_t keyLen,
        unsigned char k_pad[EDAP_SHA1_BLOCK_SIZE]) {
    if (keyLen > EDAP_SHA1_BLOCK_SIZE) {
        SHA1(key, keyLen, k_pad);
        memset(k_pad + EDAP_SHA1_DIGEST_SIZE, 0,
                EDAP_SHA1_BLOCK_SIZE - EDAP_SHA1_DIGEST_SIZE);
    } else {
        memcpy(k_pad, key, keyLen);
        memset(k_pad + keyLen, 0, EDAP_SHA1_BLOCK_SIZE - keyLen);
    }
}

/**
 * HMAC-SHA256：H((K xor opad) || H((K xor ipad) || m))。
 *
 * @param k_pad 预处理后的 key（64 字节）
 * @param data  消息
 * @param len   消息长度
 * @param out   输出（32 字节）
 */
static inline void edap_hmac_sha256(const unsigned char k_pad[EDAP_SHA256_BLOCK_SIZE],
        const unsigned char *data, size_t len,
        unsigned char out[EDAP_SHA256_DIGEST_SIZE]) {
    unsigned char inner_pad[EDAP_SHA256_BLOCK_SIZE];
    unsigned char outer_pad[EDAP_SHA256_BLOCK_SIZE];
    for (int i = 0; i < EDAP_SHA256_BLOCK_SIZE; i++) {
        inner_pad[i] = (unsigned char)(k_pad[i] ^ 0x36);
        outer_pad[i] = (unsigned char)(k_pad[i] ^ 0x5c);
    }

    SHA256_CTX inner;
    SHA256_Init(&inner);
    SHA256_Update(&inner, inner_pad, EDAP_SHA256_BLOCK_SIZE);
    SHA256_Update(&inner, data, len);

    unsigned char inner_hash[EDAP_SHA256_DIGEST_SIZE];
    SHA256_Final(inner_hash, &inner);

    SHA256_CTX outer;
    SHA256_Init(&outer);
    SHA256_Update(&outer, outer_pad, EDAP_SHA256_BLOCK_SIZE);
    SHA256_Update(&outer, inner_hash, EDAP_SHA256_DIGEST_SIZE);

    SHA256_Final(out, &outer);
}

/**
 * HMAC-SHA1：H((K xor opad) || H((K xor ipad) || m))。
 *
 * @param k_pad 预处理后的 key（64 字节）
 * @param data  消息
 * @param len   消息长度
 * @param out   输出（20 字节）
 */
static inline void edap_hmac_sha1(const unsigned char k_pad[EDAP_SHA1_BLOCK_SIZE],
        const unsigned char *data, size_t len,
        unsigned char out[EDAP_SHA1_DIGEST_SIZE]) {
    unsigned char inner_pad[EDAP_SHA1_BLOCK_SIZE];
    unsigned char outer_pad[EDAP_SHA1_BLOCK_SIZE];
    for (int i = 0; i < EDAP_SHA1_BLOCK_SIZE; i++) {
        inner_pad[i] = (unsigned char)(k_pad[i] ^ 0x36);
        outer_pad[i] = (unsigned char)(k_pad[i] ^ 0x5c);
    }

    SHA_CTX inner;
    SHA1_Init(&inner);
    SHA1_Update(&inner, inner_pad, EDAP_SHA1_BLOCK_SIZE);
    SHA1_Update(&inner, data, len);

    unsigned char inner_hash[EDAP_SHA1_DIGEST_SIZE];
    SHA1_Final(inner_hash, &inner);

    SHA_CTX outer;
    SHA1_Init(&outer);
    SHA1_Update(&outer, outer_pad, EDAP_SHA1_BLOCK_SIZE);
    SHA1_Update(&outer, inner_hash, EDAP_SHA1_DIGEST_SIZE);

    SHA1_Final(out, &outer);
}

#endif /* EDAP_JNI_COMMON_H */
