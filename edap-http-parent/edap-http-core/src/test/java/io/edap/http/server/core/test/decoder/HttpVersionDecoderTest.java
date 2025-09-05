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

package io.edap.http.server.core.test.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.server.HttpRequest;
import io.edap.http.server.HttpVersion;
import io.edap.http.server.ValueHttpRequest;
import io.edap.http.server.codec.HttpFastBufDataRange;
import io.edap.http.server.rangedecoder.HttpVersionDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class HttpVersionDecoderTest {

	@Test
	public void testDecode() {
		HttpVersionDecoder decoder = new HttpVersionDecoder();
		FastBuf buf = new FastBuf(1024);
		HttpFastBufDataRange hbdr = new HttpFastBufDataRange();
		HttpRequest request = new ValueHttpRequest();
		buf.write("http".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		HttpVersion version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("http/0.9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertEquals(version, HttpVersion.HTTP_0_9);

		buf.reset();
		buf.write("attp/0.9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("HbTP/0.9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("HTcP/0.9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("HTTo/0.9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("HTTP|0.9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("HTTO/0.9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("HTTO/0.95\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("HTTO/0.9\r6".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("HTTO/0t9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("attp/0.9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("hbtp/0.9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("htcp/0.9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("httd/0.9\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertNull(version);

		buf.reset();
		buf.write("http/1.0\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertEquals(version, HttpVersion.HTTP_1_0);

		buf.reset();
		buf.write("HTTP/1.0\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertEquals(version, HttpVersion.HTTP_1_0);

		buf.reset();
		buf.write("http/1.1\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertEquals(version, HttpVersion.HTTP_1_1);

		buf.reset();
		buf.write("http/1.2\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertEquals(version, HttpVersion.NOT_SUPPORT_VERSION);

		buf.reset();
		buf.write("HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertEquals(version, HttpVersion.HTTP_1_1);

		buf.reset();
		buf.write("http/2.0\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertEquals(version, HttpVersion.HTTP_2_0);

		buf.reset();
		buf.write("HTTP/2.0\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertEquals(version, HttpVersion.HTTP_2_0);

		buf.reset();
		buf.write("HTTP/2.1\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertEquals(version, HttpVersion.NOT_SUPPORT_VERSION);

		buf.reset();
		buf.write("HTTP/3.0\r\n".getBytes(StandardCharsets.UTF_8));
		version = decoder.decode(buf, hbdr, request);
		assertEquals(version, HttpVersion.NOT_SUPPORT_VERSION);


	}
}
