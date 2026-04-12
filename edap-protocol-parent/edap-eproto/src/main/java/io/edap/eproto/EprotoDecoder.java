package io.edap.eproto;

import io.edap.protobuf.ProtoException;

/**
 * 定义eproto协议的解码器接口
 * @param <T> 解码器的对象类型
 */
public interface EprotoDecoder<T> {

    /**
     * 从ProtoBufReader中反序列化java的POJO对象
     * @param reader ProtoBufReader对象
     * @return 返回反序列化后的POJO对象
     * @throws ProtoException 如果给定的数据不是正确的eproto编码的数据则抛错
     */
    T decode(EprotoReader reader) throws ProtoException;
}
