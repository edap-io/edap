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

package io.edap.http.api.io.edap.http.api.test.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpRequest;
import io.edap.http.PathInfo;
import io.edap.http.ValueHttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.decoder.QueryStringDecoder;
import io.edap.http.model.QueryInfo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class QueryStringDecoderTest {

    @Test
    public void testDecode() {
        QueryStringDecoder decoder = new QueryStringDecoder();

        FastBuf buf = new FastBuf(1024);
        HttpFastBufDataRange hbdr = new HttpFastBufDataRange();
        HttpRequest request = new ValueHttpRequest();

        QueryInfo query = decoder.decode(buf, hbdr, request);
        assertNull(query);

        buf.reset();
        buf.write(" ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertNull(query.getQueryBytes());

        buf.reset();
        buf.write("?test ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test");
    }
}
