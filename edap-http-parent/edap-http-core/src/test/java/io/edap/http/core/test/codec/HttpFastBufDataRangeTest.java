/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.http.core.test.codec;

import io.edap.http.codec.HttpFastBufDataRange;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;
import static org.junit.jupiter.api.Assertions.*;

public class HttpFastBufDataRangeTest {

    @Test
    public void testFrom() {
        HttpFastBufDataRange hbr = HttpFastBufDataRange.from("Host");
        assertNotNull(hbr);
        assertEquals(hbr.first(), (byte)'H');
        assertEquals(hbr.last(), (byte)'t');
        assertEquals(hbr.length(), 4);
        assertEquals(hbr.getString(), "Host");
        assertEquals(hbr.urlEncoded(), false);
        long hash = FNV_1a_INIT_VAL;
        for (byte b : "Host".getBytes(StandardCharsets.UTF_8)) {
            hash ^= b;
            hash *= FNV_1a_FACTOR_VAL;
        }
        assertEquals(hbr.hash(), hash);

        hbr = HttpFastBufDataRange.from("");
        assertNull(hbr);
        hbr = HttpFastBufDataRange.from(null);
        assertNull(hbr);
    }

    @Test
    public void testGetBytesBuilder() {
        HttpFastBufDataRange hbr = new HttpFastBufDataRange();
        assertNotNull(hbr.getBytesBuilder());
        assertEquals(hbr.getBytesBuilder().length(), 0);
    }

    @Test
    public void testAppend() {
        HttpFastBufDataRange hbr = new HttpFastBufDataRange();
        hbr.append("中文".getBytes(StandardCharsets.UTF_8));
        hbr.urlEncoded(true);
        assertEquals(hbr.getString(StandardCharsets.UTF_8), "中文");
    }
}
