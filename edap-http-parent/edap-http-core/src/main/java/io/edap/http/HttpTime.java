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

package io.edap.http;

import io.edap.util.EdapTime;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * HTTP 的 Date 头字节缓存。
 *
 * <p><b>拉取式</b>：每次取字节时看一眼当前是第几秒，跨了秒边界才重新格式化（每秒最多一次）。
 * 不再依赖 {@link EdapTime} 的推送回调 —— DIRECT 模式下没有推送源，
 * 而 Date 头是每个响应都要写的东西，不能不刷新。</p>
 *
 * <p>快照不可变、整体替换：老实现把新值原地 {@code arraycopy} 进已发布的数组，
 * 正在编码响应的线程可能读到半截数据。</p>
 */
public class HttpTime {

    /**
     * 一个"秒"内的格式化结果。不可变，整体替换。
     */
    private static final class Snapshot {

        private final long second;
        private final byte[] gmtBytes;
        private final byte[] dateBytes;

        private Snapshot(long second, byte[] gmtBytes, byte[] dateBytes) {
            this.second = second;
            this.gmtBytes = gmtBytes;
            this.dateBytes = dateBytes;
        }
    }

    private volatile Snapshot snapshot;

    /**
     * {@link SimpleDateFormat} 不是线程安全的，而拉取式下格式化发生在调用方线程上。
     */
    private final SimpleDateFormat gmtFormat;

    private HttpTime() {
        this.gmtFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z\r\n", Locale.ENGLISH);
        this.gmtFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        this.snapshot = format(System.currentTimeMillis() / 1000);
    }

    /**
     * 取当前秒的快照。秒边界变了才重新格式化。
     */
    private Snapshot current() {
        long second = EdapTime.instance().currentTimeMillis() / 1000;
        Snapshot s = snapshot;
        if (s.second != second) {
            s = format(second);
            snapshot = s;
        }
        return s;
    }

    private Snapshot format(long second) {
        byte[] gmt;
        synchronized (gmtFormat) {
            gmt = gmtFormat.format(new Date(second * 1000)).getBytes();
        }
        byte[] date = new byte[gmt.length + 6];
        System.arraycopy(gmt, 0, date, 6, gmt.length);
        date[0] = 'D';
        date[1] = 'a';
        date[2] = 't';
        date[3] = 'e';
        date[4] = ':';
        date[5] = ' ';
        return new Snapshot(second, gmt, date);
    }

    /**
     * 强制按给定毫秒重算缓存。
     *
     * @param currentMillis 毫秒时间戳
     */
    public void setCurrent(long currentMillis) {
        snapshot = format(currentMillis / 1000);
    }

    public byte[] getGMTBytes() {
        return current().gmtBytes;
    }

    public byte[] getDateBytes() {
        return current().dateBytes;
    }

    public static final HttpTime instance() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final HttpTime INSTANCE = new HttpTime();
    }
}
