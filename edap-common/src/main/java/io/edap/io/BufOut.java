/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.io;

import io.edap.buffer.FastBuf;

/**
 * 定义一个BufOut的输出接口
 * @author louis
 */
public interface BufOut {
    /**
     * 将byte数组写到缓冲区
     * @param bs
     */
    void write(byte [] bs);
    /**
     * 写制定位置和长度的byte数组到缓存区
     * @param bs
     * @param offset
     * @param len
     */
    void write(byte [] bs, int offset, int len);
    /**
     * 写入一个字节到缓存区
     * @param b
     */
    void write(byte b);
    /**
     * 缓存剩余的字节数
     * @return
     */
    int remain();
    /**
     * 扩充一个新的缓存区，返回扩充的缓冲区的容量
     * @return
     */
    int expand();
    /**
     * 是否包含FastBuf(DirectByteBuffer)对象，如果该BufOut的实现无需直接和网络进行交互
     * 则不必包含FastBuf的对象，只操作堆内的临时byte[]的对象
     * @return
     */
    boolean hasBuf();
    /**
     * 重置该BufOut的对象
     */
    void reset();

    FastBuf getBuf();

    /**
     * 获取临时保存数据的堆内写的Buf
     * @return
     */
    WriteBuf getWriteBuf();
    /**
     * 设置新的byte[]数组到ThreadLocal中
     * @param bs
     */
    void setLocalBytes(byte [] bs);
    /**
     * 为了便于减少堆堆的操作调用堆外的内存，设置BufOut对象堆内的临时存储数据的结构
     */
    public static class WriteBuf {
        /**
         * 用于临时写数据的byte数组
         */
        public byte [] bs;
        /**
         * Buf的out对象
         */
        public BufOut out;
        /**
         * 当前的数组下标
         */
        public int start;
        /**
         * 当前byte缓存的长度
         */
        public int len;
        /**
         * 总计写入byte的长度
         */
        public int writeLen;
        /**
         * 编码对象深度
         */
        public int depth;
        /**
         * 编码对象深度的Field是否为第一个
         */
        public boolean depthIndex[];
        /**
         * 设置新的byte[]数组到ThreadLocal中
         * @param bs
         */
        public void setLocalBytes(byte [] bs) {
            out.setLocalBytes(bs);
        }
    }
}