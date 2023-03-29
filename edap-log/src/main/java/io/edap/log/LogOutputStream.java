package io.edap.log;

import java.io.IOException;

/**
 * 日志数据的写入接口，定义写入操作来方便与其他日志框架集成时统一写入逻辑
 */
public interface LogOutputStream {
    /**
     * 线程安全的方式写入日志数据
     * @param data
     */
    void writeLog(byte[] data, int offset, int length) throws IOException;
}
