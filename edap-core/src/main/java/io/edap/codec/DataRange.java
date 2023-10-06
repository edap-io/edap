/*
 * Copyright 2023 The edap Project
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

package io.edap.codec;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 定义解析协议时数据块的接口，使用数据块无需生成新的String等对象，降低内存申请和释放的性能消耗
 * @param <P> 数据块位置的数据类型ByteBuffer时为Integer，FastBuf时为Long
 * @param <B> 内存Buffer的类型，有ByteBuffer，FastBuf类型
 */
public interface DataRange<P, B> {

    boolean urlEncoded();

    DataRange<P, B> urlEncoded(boolean urlEncoded);

    /**
     * 数据块判断相同时是否采用严格的匹配模式
     * @return 返回是否采用严格匹配模式
     */
    boolean matchStrict();

    /**
     * 设置数据库判断相同时是否使用严格的匹配模式
     * @param strict 是否为严格的匹配模式
     * @return 返回数据块实例
     */
    DataRange<P, B> matchStrict(boolean strict);

    /**
     * 数据块第一个字节的数据
     * @return 第一个字节的数据
     */
    byte first();

    DataRange<P, B> first(byte first);

    /**
     * 数据块的最后一个字节的数据
     * @return 数据块最后一个字节的数据
     */
    byte last();

    DataRange<P, B> last(byte last);

    /**
     * 获取数据库块所属内存Buffer的实例
     * @return 返回内存缓存对象实例
     */
    B buffer();

    DataRange<P, B> buffer(B buffer);

    /**
     * 数据块在内存Buffer中的开始位置
     * @return 返回数据库在内存Buffer中的开始位置
     */
    P start();

    /**
     * 设置数据块在内存buffer中的开始位置
     * @param start 开始位置
     * @return 返回数据块对象实例
     */
    DataRange<P, B> start(P start);

    /**
     * 数据块所有数据的hash值,在HashMap中作为key时取hashCode的值，该值在协议解码时直接计算获得，减少一次遍历数据的过程
     * @return 数据块的hash值
     */
    int hashCode();

    /**
     * 设置数据库块对象的hash值
     * @param hash
     * @return
     */
    DataRange<P, B> hashCode(int hash);

    /**
     * 数据块的长度
     * @return 数据块的长度
     */
    int length();

    /**
     * 为数据块设置长度
     * @param length 数据块的长度
     * @return 返回数据块对象
     */
    DataRange<P, B> length(int length);

    /**
     * 两个数据块的内容是否相同，使用严格模式
     */
    boolean equals(Object dataRange);

    default String getString() {
        return getString(StandardCharsets.UTF_8);
    }

    String getString(Charset charset);

    /**
     * 重设数据块对象，方便进行复用
     */
    void reset();
}
