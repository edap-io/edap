#include <jni.h>
#include <stdio.h>
#include <unistd.h>
#include "io_edap_nio_nativeimpl_FastNetIO.h"
#include "jlong_h.h"

#if defined(__linux__)
#include <linux/fs.h>
#endif

#define IOS_UNAVAILABLE -2L
#define IOS_INTERRUPTED -3L
#define IOS_THROWN      -5L

#include <sys/ioctl.h>
#include <errno.h>


static jfieldID fd_fdID;


jint
fdval(JNIEnv *env, jobject fdo)
{
    return (*env)->GetIntField(env, fdo, fd_fdID);
}

JNIEXPORT jint JNICALL Java_io_edap_nio_nativeimpl_FastNetIO_read0
            (JNIEnv *env, jclass clazz, jobject fdo, jlong address, jint len)
{
    jint fd = fdval(env, fdo);
    void *buf = (void *)jlong_to_ptr(address);

    return convertReturnVal(env, read(fd, buf, len), 1);
}

JNIEXPORT jint JNICALL
Java_io_edap_nio_nativeimpl_FastNetIO_write0(JNIEnv *env, jclass clazz,
                              jobject fdo, jlong address, jint len)
{
    jint fd = fdval(env, fdo);
    void *buf = (void *)jlong_to_ptr(address);

    return convertReturnVal(env, write(fd, buf, len), 0);
}

jint
convertReturnVal(JNIEnv *env, jint n, int reading)
{
    if (n > 0) /* Number of bytes written */
        return n;
    else if (n == 0) {
        if (reading == 1) {
            return -1; /* EOF is -1 in javaland */
        } else {
            return 0;
        }
    }
    else if (errno == EAGAIN || errno == EWOULDBLOCK)
        return IOS_UNAVAILABLE;
    else if (errno == EINTR)
        return IOS_INTERRUPTED;
    else {
        const char *msg = reading == 1? "Read failed" : "Write failed";
        // JNU_ThrowIOExceptionWithLastError(env, msg);
        return IOS_THROWN;
    }
}

JNIEXPORT void JNICALL
Java_io_edap_nio_nativeimpl_FastNetIO_initIDs(JNIEnv *env, jclass clazz)
{
    CHECK_NULL(clazz = (*env)->FindClass(env, "java/io/FileDescriptor"));
    CHECK_NULL(fd_fdID = (*env)->GetFieldID(env, clazz, "fd", "I"));
}

