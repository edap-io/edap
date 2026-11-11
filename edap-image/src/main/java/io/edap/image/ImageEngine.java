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

package io.edap.image;

/**
 * 缩略图生成引擎。
 *
 * <p>实现必须是<b>线程安全</b>的：缩略图请求来自 HTTP 工作线程池，同一时刻会有多个线程
 * 并发调用。libvips 本身是线程安全的（操作无共享状态），因此进程内实现不需要额外同步。</p>
 *
 * <p>实现可能依赖运行期才具备的外部程序（libvips 命令行是运行期依赖，不是构建期依赖），
 * 所以调用方必须先判断 {@link #available()}，再决定是否降级。</p>
 */
public interface ImageEngine {

    /**
     * 引擎标识 —— <b>参与派生资源（缩略图）的缓存 key</b>。
     *
     * <p>换引擎会改变输出字节（同样的输入，libvips 与 ImageMagick 编码结果不一样），
     * 所以缓存目录必须按引擎分开，否则换引擎后旧缓存会被当成新引擎的产物继续用。
     * 实现方给的标识一旦变化，其产物的历史缓存就整体失效 —— 这是刻意的。</p>
     *
     * <p>标识里<b>不要</b>带会变的量（时间戳、随机数、进程 id）。</p>
     *
     * @return 稳定的引擎标识
     */
    String id();

    /**
     * 引擎是否可用。
     *
     * @return true 表示可以正常处理图片
     */
    boolean available();

    /**
     * 生成缩略图。
     *
     * @param source 原图字节
     * @param spec   目标规格
     * @return 缩略图字节
     * @throws ImageProcessingException 输入非法或处理失败
     */
    byte[] thumbnail(byte[] source, ThumbSpec spec);
}
