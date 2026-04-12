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
import java.util.concurrent.TimeUnit;

public class HttpTime {

    private volatile byte[] gmtBytes;
    private volatile byte[] dateBytes;

    private SimpleDateFormat gmtFormat;

    private HttpTime() {
        long cur = EdapTime.instance().currentTimeMillis();
        gmtFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z\r\n", Locale.ENGLISH);
        gmtFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        gmtBytes = gmtFormat.format(new Date(cur)).getBytes();
        dateBytes = new byte[gmtBytes.length+6];
        dateBytes[0] = 'D';
        dateBytes[1] = 'a';
        dateBytes[2] = 't';
        dateBytes[3] = 'e';
        dateBytes[4] = ':';
        dateBytes[5] = ' ';
        setCurrent(cur);

        EdapTime.instance().addCallback(this::setCurrent, 0, 1, TimeUnit.SECONDS);
    }

    public void setCurrent(long currentMillis) {
        gmtBytes = gmtFormat.format(new Date(currentMillis)).getBytes();
        System.arraycopy(gmtBytes, 0, dateBytes, 6, gmtBytes.length);
    }

    public byte[] getGMTBytes() {
        return gmtBytes;
    }

    public byte[] getDateBytes() {
        return dateBytes;
    }

    public static final HttpTime instance() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final HttpTime INSTANCE = new HttpTime();
    }
}
