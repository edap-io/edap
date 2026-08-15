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
import io.edap.http.PathInfo;
import io.edap.http.ValueHttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.server.PathInfoMatcher;
import io.edap.http.server.rangedecoder.PathDecoder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PathDecoderTest {

    @Test
    public void testDecode() throws UnsupportedEncodingException {
        PathDecoder pathDecoder = new PathDecoder(new PathInfoMatcher());
        FastBuf buf = new FastBuf(1024);
        HttpFastBufDataRange hbdr = new HttpFastBufDataRange();
        HttpRequest request = new ValueHttpRequest();

        PathInfo pathInfo = pathDecoder.decode(buf, hbdr, request);
        Assertions.assertNull(pathInfo);

        buf.reset();
        buf.write("/index".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        Assertions.assertNull(pathInfo);

        buf.reset();
        buf.write("+ ".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        Assertions.assertNotNull(pathInfo);
        Assertions.assertNotNull(pathInfo.getPath());
        assertEquals(hbdr.first(), (byte)' ');

        buf.reset();
        hbdr.reset();
        buf.write("++t ".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        Assertions.assertNotNull(pathInfo);
        Assertions.assertNotNull(pathInfo.getPath());
        assertEquals(hbdr.first(), (byte)' ');

        buf.reset();
        hbdr.reset();
        buf.write("++t?".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        Assertions.assertNotNull(pathInfo);
        Assertions.assertNotNull(pathInfo.getPath());
        assertEquals(hbdr.first(), (byte)' ');

        buf.reset();
        hbdr.reset();
        buf.write("++t#".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        Assertions.assertNotNull(pathInfo);
        Assertions.assertNotNull(pathInfo.getPath());
        assertEquals(hbdr.first(), (byte)' ');
        assertEquals(hbdr.getString(), "  t");

        buf.reset();
        hbdr.reset();
        buf.write("123++t#".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        Assertions.assertNotNull(pathInfo);
        Assertions.assertNotNull(pathInfo.getPath());
        assertEquals(hbdr.first(), (byte)'1');
        assertEquals(hbdr.getString(), "123  t");

        buf.reset();
        hbdr.reset();
        byte[] bs = URLEncoder.encode("中", "utf-8").getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[bs.length+1];
        System.arraycopy(bs, 0, data, 0, bs.length);
        data[data.length-1] = (byte)' ';
        buf.write(data);
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        Assertions.assertNotNull(pathInfo);
        Assertions.assertNotNull(pathInfo.getPath());
        assertEquals(hbdr.first(), "中".getBytes(StandardCharsets.UTF_8)[0]);
        assertEquals(hbdr.getString(), "中");

        buf.reset();
        hbdr.reset();
        bs = URLEncoder.encode("54321中", "utf-8").getBytes(StandardCharsets.UTF_8);
        data = new byte[bs.length+1];
        System.arraycopy(bs, 0, data, 0, bs.length);
        data[data.length-1] = (byte)' ';
        buf.write(data);
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        Assertions.assertNotNull(pathInfo);
        Assertions.assertNotNull(pathInfo.getPath());
        assertEquals(hbdr.first(), "5".getBytes(StandardCharsets.UTF_8)[0]);
        assertEquals(hbdr.getString(), "54321中");

        buf.reset();
        buf.write("%E".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        Assertions.assertNull(pathInfo);

        buf.reset();
        buf.write(new byte[]{'%', 1, 29});
        Throwable ex = Assertions.assertThrows(Throwable.class,
                () -> {
                    pathDecoder.decode(buf, hbdr, request);
                });
        Assertions.assertTrue(ex.getMessage().contains(": Illegal hex characters in escape (%) pattern - negative value"));
    }
}
