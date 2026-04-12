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
import io.edap.http.ValueHttpRequest;
import io.edap.http.model.QueryInfo;
import io.edap.http.server.bytesdecoder.BytesQueryStringDecoder;
import io.edap.util.ByteArrayBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class BytesQueryStringDecoderTest {

    @Test
    public void testDecode() throws UnsupportedEncodingException {
        BytesQueryStringDecoder decoder = new BytesQueryStringDecoder();

        FastBuf buf = new FastBuf(1024);
        ByteArrayBuilder sb = new ByteArrayBuilder();
        ValueHttpRequest request = new ValueHttpRequest();

        QueryInfo query = decoder.decode(buf, sb, request);
        assertNull(query);

        buf.reset();
        buf.write("?".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNull(query);

        buf.reset();
        buf.write(" ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertNull(query.getQueryBytes());

        buf.reset();
        buf.write("?test".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNull(query);

        buf.reset();
        buf.write("?test ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test");

        buf.reset();
        buf.write("?test= ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test=");


        sb.reset();
        request.reset();
        buf.reset();
        buf.write("?test=123 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test=123");
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 1);
        assertEquals(request.getParameters().containsKey("test"), true);
        assertEquals(request.getParameters().get("test").get(0).getValue(), "123");

        sb.reset();
        buf.reset();
        request.reset();
        buf.write("?test=123&test2=456 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test=123&test2=456");
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 2);
        assertEquals(request.getParameters().containsKey("test"), true);
        assertEquals(request.getParameters().get("test").get(0).getValue(), "123");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(0).getValue(), "456");

        sb.reset();
        buf.reset();
        request.reset();
        buf.write("?=123&test2=456 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "=123&test2=456");
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 1);
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(0).getValue(), "456");

        sb.reset();
        buf.reset();
        request.reset();
        buf.write("?te+t=123&test2=456 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "te+t=123&test2=456");
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 2);
        assertEquals(request.getParameters().containsKey("te t"), true);
        assertEquals(request.getParameters().get("te t").get(0).getValue(), "123");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(0).getValue(), "456");

        sb.reset();
        buf.reset();
        request.reset();
        buf.write("?test=1+3&test2=456 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test=1+3&test2=456");
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 2);
        assertEquals(request.getParameters().containsKey("test"), true);
        assertEquals(request.getParameters().get("test").get(0).getValue(), "1 3");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(0).getValue(), "456");

        sb.reset();
        buf.reset();
        request.reset();
        buf.write("?test=1++3&test2=456 ".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "test=1++3&test2=456");
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 2);
        assertEquals(request.getParameters().containsKey("test"), true);
        assertEquals(request.getParameters().get("test").get(0).getValue(), "1  3");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(0).getValue(), "456");

        sb.reset();
        buf.reset();
        request.reset();
        buf.write(("?key" + urlEncode("中文") + "1=1++3&test2=456 ").getBytes("utf-8"));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "key" + urlEncode("中文") + "1=1++3&test2=456");
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 2);
        assertEquals(request.getParameters().containsKey( "key中文1"), true);
        assertEquals(request.getParameters().get("key中文1").get(0).getValue(), "1  3");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(0).getValue(), "456");

        sb.reset();
        buf.reset();
        request.reset();
        buf.write(("?key" + urlEncode("中文") + "=1++3&test2=456 ").getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "key" + urlEncode("中文") + "=1++3&test2=456");
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 2);
        assertEquals(request.getParameters().containsKey("key中文"), true);
        assertEquals(request.getParameters().get("key中文").get(0).getValue(), "1  3");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(0).getValue(), "456");

        sb.reset();
        buf.reset();
        request.reset();
        buf.write(("?" + urlEncode("中文") + "key=1++3&test2=456 ").getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "" + urlEncode("中文") + "key=1++3&test2=456");
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 2);
        assertEquals(request.getParameters().containsKey("中文key"), true);
        assertEquals(request.getParameters().get("中文key").get(0).getValue(), "1  3");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(0).getValue(), "456");

        sb.reset();
        buf.reset();
        request.reset();
        buf.write(("?key=1" + urlEncode("中文") + "3&test2=456 ").getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "key=1" + urlEncode("中文") + "3&test2=456");
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 2);
        assertEquals(request.getParameters().containsKey("key"), true);
        assertEquals(request.getParameters().get("key").get(0).getValue(), "1中文3");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(0).getValue(), "456");

        sb.reset();
        buf.reset();
        request.reset();
        buf.write(("?key=1" + urlEncode("中文") + "3&test2=456&test2=" + urlEncode("中文") + " ").getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "key=1" + urlEncode("中文") + "3&test2=456&test2=" + urlEncode("中文"));
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 2);
        assertEquals(request.getParameters().containsKey("key"), true);
        assertEquals(request.getParameters().get("key").get(0).getValue(), "1中文3");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(0).getValue(), "456");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(1).getValue(), "中文");

        sb.reset();
        buf.reset();
        request.reset();
        buf.write(("?key=1" + urlEncode("中文") + "3&key=234&test2=456&test2=" + urlEncode("中文") + " ").getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        assertNotNull(query);
        assertEquals(query.getQuery(), "key=1" + urlEncode("中文") + "3&key=234&test2=456&test2=" + urlEncode("中文"));
        assertNotNull(request.getParameters());
        assertEquals(request.getParameters().size(), 2);
        assertEquals(request.getParameters().containsKey("key"), true);
        assertEquals(request.getParameters().get("key").get(0).getValue(), "1中文3");
        assertEquals(request.getParameters().get("key").get(1).getValue(), "234");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(0).getValue(), "456");
        assertEquals(request.getParameters().containsKey("test2"), true);
        assertEquals(request.getParameters().get("test2").get(1).getValue(), "中文");

        buf.reset();
        sb.reset();
        buf.write("?%E".getBytes(StandardCharsets.UTF_8));
        query = decoder.decode(buf, sb, request);
        Assertions.assertNull(query);

    }

    private String urlEncode(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s, "utf-8");
    }
}
