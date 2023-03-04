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

package io.edap.log.test.perf;

import io.edap.log.converter.DateConverter;
import io.edap.log.helps.ByteArrayBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class LogbackCacheDateConverter implements DateConverter {

    final DateTimeFormatter dtf;
    final ZoneId zoneId;
    final AtomicReference<CacheTuple> atomicReference;

    static class CacheTuple {
        final long lastTimestamp;
        final byte[] cachedBytes;

        public CacheTuple(long lastTimestamp, byte[] cachedBytes) {
            super();
            this.lastTimestamp = lastTimestamp;
            this.cachedBytes = cachedBytes;
        }
    }

    public LogbackCacheDateConverter(String pattern) {
        this(pattern, null);
    }

    public LogbackCacheDateConverter(String pattern, ZoneId aZoneId) {
        this(pattern, aZoneId, null);
    }

    public LogbackCacheDateConverter(String pattern, ZoneId aZoneId, Locale aLocale) {
        if (aZoneId == null) {
            this.zoneId = ZoneId.systemDefault();
        } else {
            this.zoneId = aZoneId;
        }
        Locale locale = aLocale != null ? aLocale : Locale.getDefault();

        dtf = DateTimeFormatter.ofPattern(pattern).withZone(this.zoneId).withLocale(locale);
        CacheTuple cacheTuple = new CacheTuple(-1, null);
        this.atomicReference = new AtomicReference<>(cacheTuple);
    }

    @Override
    public void convertTo(ByteArrayBuilder out, Long now) {
        CacheTuple localCacheTuple = atomicReference.get();
        CacheTuple oldCacheTuple = localCacheTuple;

        if (now != localCacheTuple.lastTimestamp) {
            Instant instant = Instant.ofEpochMilli(now);
            byte[] result = dtf.format(instant).getBytes(StandardCharsets.UTF_8);
            localCacheTuple = new CacheTuple(now, result);
            out.append(result);
            // allow a single thread to update the cache reference
            atomicReference.compareAndSet(oldCacheTuple, localCacheTuple);
        } else {
            out.append(localCacheTuple.cachedBytes);
        }
    }
}
