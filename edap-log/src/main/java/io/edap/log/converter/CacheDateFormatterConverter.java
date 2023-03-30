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

package io.edap.log.converter;

import io.edap.log.LogEvent;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class CacheDateFormatterConverter implements DateConverter {

    final DateTimeFormatter dateFormatter;

    final AtomicReference<CacheData> atomicReference;

    private final byte[] postfixData;

    public CacheDateFormatterConverter(String format) {
        this(format, null);
    }

    public CacheDateFormatterConverter(String format, String nextText) {
        TimeZoneInfo timeZoneInfo = DateConverter.parseTimeZoneInfo(format);
        ZoneId zoneId = timeZoneInfo.zoneId;
        Locale locale = timeZoneInfo.locale;
        dateFormatter = DateTimeFormatter.ofPattern(timeZoneInfo.format)
                .withZone(zoneId).withLocale(locale);
        if (StringUtil.isEmpty(nextText)) {
            postfixData = null;
        } else {
            postfixData = nextText.getBytes(StandardCharsets.UTF_8);
        }
        long now = System.currentTimeMillis();
        Instant instant = Instant.ofEpochMilli(now);
        byte[] result = dateFormatter.format(instant).getBytes(StandardCharsets.UTF_8);
        if (postfixData != null) {
            byte[] tmp = new byte[result.length + postfixData.length];
            System.arraycopy(result, 0, tmp, 0, result.length);
            System.arraycopy(postfixData, 0, tmp, result.length, postfixData.length);
            result = tmp;
        }
        CacheData cacheData = new CacheData(now, result);
        this.atomicReference = new AtomicReference<>(cacheData);

    }

    @Override
    public void convertTo(ByteArrayBuilder out, LogEvent logEvent) {
        long mills = logEvent.getLogTime();
        System.out.println("logtime:" + mills);
        CacheData localCacheData = atomicReference.get();
        CacheData oldCacheData = localCacheData;
        if (localCacheData.mills != mills) {
            Instant instant = Instant.ofEpochMilli(mills);
            byte[] result = dateFormatter.format(instant).getBytes(StandardCharsets.UTF_8);
            if (postfixData != null) {
                byte[] tmp = new byte[result.length + postfixData.length];
                System.arraycopy(result, 0, tmp, 0, result.length);
                System.arraycopy(postfixData, 0, tmp, result.length, postfixData.length);
                result = tmp;
            }
            out.append(result);
            localCacheData = new CacheData(mills, result);
            atomicReference.compareAndSet(oldCacheData, localCacheData);
        } else {
            out.append(localCacheData.timeBytes);
        }
    }

    static class CacheData {
        public long mills;
        public byte[] timeBytes;

        public CacheData(long mills, byte[] timeBytes) {
            this.mills = mills;
            this.timeBytes = timeBytes;
        }
    }

}
