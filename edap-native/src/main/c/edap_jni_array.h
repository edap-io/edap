/*
 * edap-native 的 JNI 数组取数辅助。
 *
 * ★ 为什么单独一个头文件（不并进 edap_jni_common.h）：
 *   edap_jni_common.h 明确「只放纯计算，不放 JNI 调用」——
 *   critical section 的「拿指针 / 释放指针」必须成对出现，抽进公共头会让边界看不清。
 *   本头文件把这一对操作**封在一个函数里**（临界区不出现在调用方），
 *   边界反而比原来更清楚：调用方只拿到一段「只读字节」。
 *
 * ★ 小载荷为什么要拷栈缓冲（而不是 GetPrimitiveArrayCritical）：
 *   实测（macOS / JDK 17，见 edap-crypto/doc/DESIGN.md §10.14.7）：
 *     GetPrimitiveArrayCritical + Release = 102 ns（**与载荷大小无关**）
 *     GetByteArrayRegion                 =  55 ns@64B / 67 ns@1KB / 202 ns@8KB
 *   ⇒ 交叉点约 1.5~2 KB ⇒ 阈值取 1 KB（保守，必赚）。
 *   ★ 额外好处：critical section 会让 JVM 触发 GCLocker Initiated GC
 *     （实测每轮基准固定 3 次），改用 region 后这条路径整个消失。
 *   ★ 附带好处：GetByteArrayRegion 越界会抛 ArrayIndexOutOfBoundsException
 *     （与 JCE 的 MessageDigest 行为一致）—— 比 critical section 越界读
 *     （静默读到邻接堆内存）更安全。
 */
#ifndef EDAP_JNI_ARRAY_H
#define EDAP_JNI_ARRAY_H

#include <jni.h>
#include <stddef.h>

/**
 * 小载荷阈值（字节）。
 *
 * <p>≤ 此值：拷到栈缓冲再喂给 OpenSSL（避开 critical section 的固定成本）。
 * &gt; 此值：critical section 直接指向 Java 堆（不拷贝、不落 Java 堆副本）。</p>
 */
#define EDAP_SMALL_PAYLOAD_MAX 1024

/**
 * 「消费一段字节」的回调：把 data[0..len) 喂进摘要 / MAC。
 *
 * <p>用回调而不是「返回指针 + 标志位」，是为了让 critical section
 * 的获取与释放**成对出现在同一个栈帧里** —— 调用方拿不到「半开」的临界区。</p>
 */
typedef void (*edap_bytes_fn)(void *ctx, const unsigned char *data, size_t len);

/**
 * 取一段字节并交给回调消费。
 *
 * <p>两条路径：</p>
 * <ul>
 *   <li><b>小载荷</b>（≤ {@link EDAP_SMALL_PAYLOAD_MAX}）：{@code GetByteArrayRegion}
 *       拷到栈缓冲再喂 —— 避开 critical section 的 ~102ns 固定成本。</li>
 *   <li><b>大载荷</b>：{@code GetPrimitiveArrayCritical} 直接指向 Java 堆 ——
 *       不拷贝。临界区在本函数内成对获取/释放。</li>
 * </ul>
 *
 * @param env    JNI 环境
 * @param data   源数组（不可为 NULL）
 * @param offset 起始偏移（≥ 0）
 * @param len    长度（≥ 0）
 * @param fn     消费回调
 * @param ctx    回调上下文
 * @return 成功返回 1；失败返回 0（参数非法 / 取数失败 / 越界抛异常）
 */
static inline int edap_jni_consume_bytes(JNIEnv *env, jbyteArray data, jint offset, jint len,
        edap_bytes_fn fn, void *ctx) {
    if (data == NULL || offset < 0 || len < 0) {
        return 0;
    }
    if (len <= EDAP_SMALL_PAYLOAD_MAX) {
        unsigned char stackBuf[EDAP_SMALL_PAYLOAD_MAX];
        (*env)->GetByteArrayRegion(env, data, offset, len, (jbyte *)stackBuf);
        if ((*env)->ExceptionCheck(env)) {
            /* 越界 ⇒ 越界异常留给 JVM 抛（不吞） */
            return 0;
        }
        fn(ctx, stackBuf, (size_t)len);
        return 1;
    }
    jbyte *ptr = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (ptr == NULL) {
        return 0;
    }
    fn(ctx, (const unsigned char *)(ptr + offset), (size_t)len);
    (*env)->ReleasePrimitiveArrayCritical(env, data, ptr, JNI_ABORT);
    return 1;
}

#endif /* EDAP_JNI_ARRAY_H */
