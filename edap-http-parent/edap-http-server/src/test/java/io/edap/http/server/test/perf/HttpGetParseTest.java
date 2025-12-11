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

package io.edap.http.server.test.perf;

import io.edap.buffer.FastBuf;
import io.edap.http.AbstractHttpDecoder;
import io.edap.http.HttpDecoder;
import io.edap.http.HttpRequest;
import io.edap.http.ValueHttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.server.RangeHttpRequestDecoder;
import io.edap.nio.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HttpGetParseTest {

    static ValueHttpRequest request;
    static HttpFastBufDataRange hbr = new HttpFastBufDataRange();
    static FastBuf buf;
    static byte[] httpData;

    static {
        request = new ValueHttpRequest();
        buf = new FastBuf(4096);
        httpData = ("GET /json HTTP/1.1\r\n" +
                "Host: server\r\n" +
                "User-Agent: Mozilla/5.0 (X11; Linux x86_64) Gecko/20130501 Firefox/30.0 AppleWebKit/600.00 Chrome/30.0.0000.0 Trident/10.0 Safari/600.00\r\n" +
                "Cookie: uid=12345678901234567890;__utma=1.1234567890.1234567890.1234567890.1234567890.12; wd=2560x1600\r\n" +
                "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n" +
                "Accept-Language: en-US,en;q=0.5\r\n" +
                "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void testParse() {

        for (int i=0;i<2;i++) {
        //while (true) {
            RangeHttpRequestDecoder decoder = new RangeHttpRequestDecoder();
            HttpDecoder.State state = HttpDecoder.State.SKIP_CONTROL_CHARS;
            buf.reset();
            buf.write(httpData);
            request.reset();
            hbr.reset();
            ParseResult<HttpRequest> result = new ParseResult<>();
            decoder.parseHttpRequest(buf, state, hbr, request, null, result);
            assertNotNull(result);

        }

    }
}
