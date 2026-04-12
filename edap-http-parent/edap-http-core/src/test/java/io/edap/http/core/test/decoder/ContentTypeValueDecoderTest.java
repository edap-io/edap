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

package io.edap.http.core.test.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HeaderValue;
import io.edap.http.HttpRequest;
import io.edap.http.ValueHttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.headervalue.ContentTypeValue;
import io.edap.http.rangedecoder.ContentTypeValueDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ContentTypeValueDecoderTest {

    @Test
    public void testDecoder() {
        ContentTypeValueDecoder decoder = new ContentTypeValueDecoder();

        String contentType = "application/x-www-form-urlencoded; charset=UTF-8\r\n";
        FastBuf buf = new FastBuf(1024);
        HttpFastBufDataRange hbdr = new HttpFastBufDataRange();
        HttpRequest request = new ValueHttpRequest();

        hbdr.buffer(buf);
        buf.write(contentType.getBytes(StandardCharsets.UTF_8));
        HeaderValue hv = decoder.decode(buf, hbdr, request);
        assertEquals(hv instanceof ContentTypeValue, true);

        contentType = "application/x-www-form-urlencoded; charset=UTF-8\r";
        buf.reset();
        buf.write(contentType.getBytes(StandardCharsets.UTF_8));
        hv = decoder.decode(buf, hbdr, request);
        assertNull(hv);

        contentType = "application/x-www-form-urlencoded\r\n";
        buf.reset();
        buf.write(contentType.getBytes(StandardCharsets.UTF_8));
        hv = decoder.decode(buf, hbdr, request);
        assertNotNull(hv);
    }
}
