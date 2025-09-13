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

package io.edap.http.server.test.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpRequest;
import io.edap.http.ValueHttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.model.QueryInfo;
import io.edap.http.server.rangedecoder.QueryStringDecoder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
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
        buf.write("?".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
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

        buf.reset();
        buf.write("?test= ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test=");


        hbdr.reset();
        buf.reset();
        buf.write("?test=123 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test=123");
        assertNotNull(query.getParamPairs());
        assertEquals(query.getParamPairs().size(), 1);
        assertEquals(query.getParamPairs().get(0).getKey(), "test");
        assertEquals(query.getParamPairs().get(0).getValue(), "123");

        hbdr.reset();
        buf.reset();
        buf.write("?test=123&test2=456 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test=123&test2=456");
        assertNotNull(query.getParamPairs());
        assertEquals(query.getParamPairs().size(), 2);
        assertEquals(query.getParamPairs().get(0).getKey(), "test");
        assertEquals(query.getParamPairs().get(0).getValue(), "123");
        assertEquals(query.getParamPairs().get(1).getKey(), "test2");
        assertEquals(query.getParamPairs().get(1).getValue(), "456");

        hbdr.reset();
        buf.reset();
        buf.write("?=123&test2=456 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "=123&test2=456");
        assertNotNull(query.getParamPairs());
        assertEquals(query.getParamPairs().size(), 1);
        assertEquals(query.getParamPairs().get(0).getKey(), "test2");
        assertEquals(query.getParamPairs().get(0).getValue(), "456");

        hbdr.reset();
        buf.reset();
        buf.write("?te+t=123&test2=456 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "te+t=123&test2=456");
        assertNotNull(query.getParamPairs());
        assertEquals(query.getParamPairs().size(), 2);
        assertEquals(query.getParamPairs().get(0).getKey(), "te t");
        assertEquals(query.getParamPairs().get(0).getValue(), "123");
        assertEquals(query.getParamPairs().get(1).getKey(), "test2");
        assertEquals(query.getParamPairs().get(1).getValue(), "456");

        hbdr.reset();
        buf.reset();
        buf.write("?test=1+3&test2=456 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test=1+3&test2=456");
        assertNotNull(query.getParamPairs());
        assertEquals(query.getParamPairs().size(), 2);
        assertEquals(query.getParamPairs().get(0).getKey(), "test");
        assertEquals(query.getParamPairs().get(0).getValue(), "1 3");
        assertEquals(query.getParamPairs().get(1).getKey(), "test2");
        assertEquals(query.getParamPairs().get(1).getValue(), "456");

        hbdr.reset();
        buf.reset();
        buf.write("?test=1++3&test2=456 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test=1++3&test2=456");
        assertNotNull(query.getParamPairs());
        assertEquals(query.getParamPairs().size(), 2);
        assertEquals(query.getParamPairs().get(0).getKey(), "test");
        assertEquals(query.getParamPairs().get(0).getValue(), "1  3");
        assertEquals(query.getParamPairs().get(1).getKey(), "test2");
        assertEquals(query.getParamPairs().get(1).getValue(), "456");

        hbdr.reset();
        buf.reset();
        buf.write(("?key" + urlEncode("中文") + "1=1++3&test2=456 ").getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "key" + urlEncode("中文") + "1=1++3&test2=456");
        assertNotNull(query.getParamPairs());
        assertEquals(query.getParamPairs().size(), 2);
        assertEquals(query.getParamPairs().get(0).getKey(), "key中文1");
        assertEquals(query.getParamPairs().get(0).getValue(), "1  3");
        assertEquals(query.getParamPairs().get(1).getKey(), "test2");
        assertEquals(query.getParamPairs().get(1).getValue(), "456");

        hbdr.reset();
        buf.reset();
        buf.write(("?key" + urlEncode("中文") + "=1++3&test2=456 ").getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "key" + urlEncode("中文") + "=1++3&test2=456");
        assertNotNull(query.getParamPairs());
        assertEquals(query.getParamPairs().size(), 2);
        assertEquals(query.getParamPairs().get(0).getKey(), "key中文");
        assertEquals(query.getParamPairs().get(0).getValue(), "1  3");
        assertEquals(query.getParamPairs().get(1).getKey(), "test2");
        assertEquals(query.getParamPairs().get(1).getValue(), "456");

        hbdr.reset();
        buf.reset();
        buf.write(("?" + urlEncode("中文") + "key=1++3&test2=456 ").getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "" + urlEncode("中文") + "key=1++3&test2=456");
        assertNotNull(query.getParamPairs());
        assertEquals(query.getParamPairs().size(), 2);
        assertEquals(query.getParamPairs().get(0).getKey(), "中文key");
        assertEquals(query.getParamPairs().get(0).getValue(), "1  3");
        assertEquals(query.getParamPairs().get(1).getKey(), "test2");
        assertEquals(query.getParamPairs().get(1).getValue(), "456");

        hbdr.reset();
        buf.reset();
        buf.write(("?key=1" + urlEncode("中文") + "3&test2=456 ").getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "key=1" + urlEncode("中文") + "3&test2=456");
        assertNotNull(query.getParamPairs());
        assertEquals(query.getParamPairs().size(), 2);
        assertEquals(query.getParamPairs().get(0).getKey(), "key");
        assertEquals(query.getParamPairs().get(0).getValue(), "1中文3");
        assertEquals(query.getParamPairs().get(1).getKey(), "test2");
        assertEquals(query.getParamPairs().get(1).getValue(), "456");

        buf.reset();
        hbdr.reset();
        buf.write(new byte[]{'?', '%', 1, 29});
        Throwable ex = Assertions.assertThrows(Throwable.class,
                () -> {
                    decoder.decode(buf, hbdr, request);
                });
        Assertions.assertTrue(ex.getMessage().contains(": Illegal hex characters in escape (%) pattern - negative value"));

        buf.reset();
        hbdr.reset();
        buf.write("?%E".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, hbdr, request);
        Assertions.assertNull(query);

    }

    private String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
