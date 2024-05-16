package io.edap.eproto;

import io.edap.protobuf.EncodeException;

/**
 * 定义eproto协议的编码器接口
 * @param <T> java的POJO的对象类型
 */
public interface EprotoEncoder<T> {

    /**
     * 将Java的POJO对象做eproto编码写到BufOut中
     * @param writer 写入byte[]的BufOut的对象
     * @param t java的POJO对象
     * @throws EncodeException 编码异常
     */
    void encode(EprotoWriter writer, T t) throws EncodeException;
}
