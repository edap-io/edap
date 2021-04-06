package io.edap.io;

import io.edap.buffer.FastBuf;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 输出到FastBuf的Writer接口
 * @author : luysh@yonyou.com
 * @date : 2020/11/10
 */
public interface BufWriter {
    /**
     * 数据写入到FastBuf中，返回成功写入FastBuf的字节数量
     * @param buf FastBuf的对象
     * @return
     */
    int toFastBuf(FastBuf buf);

    /**
     * 该Writer数据的大小
     * @return
     */
    int size();

    /**
     * 设置写入FastBuf的下标
     * @param wPos
     */
    void setWPos(int wPos);

    void toStream(OutputStream stream) throws IOException;

    byte[] toByteArray();
}
