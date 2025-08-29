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
import io.edap.http.decoder.PathDecoder;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;

import static org.junit.jupiter.api.Assertions.*;

public class PathDecoderTest {

    @Test
    public void testDecode() {
        PathDecoder pathDecoder = new PathDecoder();
        FastBuf buf = new FastBuf(1024);
        HttpFastBufDataRange hbdr = new HttpFastBufDataRange();
        HttpRequest request = new ValueHttpRequest();

        PathInfo pathInfo = pathDecoder.decode(buf, hbdr, request);
        assertNull(pathInfo);

        buf.reset();
        buf.write("/index".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        assertNull(pathInfo);

        buf.reset();
        buf.write("+ ".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        assertNotNull(pathInfo);
        assertNull(pathInfo.getPath());
        assertEquals(hbdr.first(), (byte)' ');

        buf.reset();
        buf.write("++t ".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        assertNotNull(pathInfo);
        assertNull(pathInfo.getPath());
        assertEquals(hbdr.first(), (byte)' ');

        buf.reset();
        buf.write("++t?".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        assertNotNull(pathInfo);
        assertNull(pathInfo.getPath());
        assertEquals(hbdr.first(), (byte)' ');

        buf.reset();
        buf.write("++t#".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        assertNotNull(pathInfo);
        assertNull(pathInfo.getPath());
        assertEquals(hbdr.first(), (byte)' ');

        buf.reset();
        byte[] bs = URLEncoder.encode("中", StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[bs.length+1];
        System.arraycopy(bs, 0, data, 0, bs.length);
        data[data.length-1] = (byte)' ';
        buf.write(data);
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        assertNotNull(pathInfo);
        assertNull(pathInfo.getPath());
        assertEquals(hbdr.first(), "中".getBytes(StandardCharsets.UTF_8)[0]);

        buf.reset();
        buf.write("%E".getBytes(StandardCharsets.UTF_8));
        pathInfo = pathDecoder.decode(buf, hbdr, request);
        assertNull(pathInfo);

        buf.reset();
        buf.write(new byte[]{'%', 1, 29});
        Throwable ex = assertThrows(Throwable.class,
                () -> {
                    pathDecoder.decode(buf, hbdr, request);
                });
        assertTrue(ex.getMessage().contains(": Illegal hex characters in escape (%) pattern - negative value"));
    }
}
