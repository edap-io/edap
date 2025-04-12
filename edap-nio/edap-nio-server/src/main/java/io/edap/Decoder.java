package io.edap;

import io.edap.buffer.FastBuf;

/**
 * Socket数据解码器接口
 * @param <T>
 */
public interface Decoder<T, S extends NioSession> {
    /**
     * 消息体解码器，根据给定的FastBuf对象，解码成制定的对象，如果不是完整的消息体则返回null
     * @param bufIn
     * @return
     */
    ParseResult<T> decode(FastBuf bufIn, S nioSession);

    /**
     * 重置解码器，方便放回池中复用或者重新进行解码
     */
    void reset();
}
