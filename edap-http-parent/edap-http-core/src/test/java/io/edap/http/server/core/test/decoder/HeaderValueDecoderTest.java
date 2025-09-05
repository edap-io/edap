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
import io.edap.http.server.HeaderValue;
import io.edap.http.server.HttpRequest;
import io.edap.http.server.ValueHttpRequest;
import io.edap.http.server.codec.HttpFastBufDataRange;
import io.edap.http.server.rangedecoder.HeaderValueDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class HeaderValueDecoderTest {

	@Test
	public void testDecode() {
		HeaderValueDecoder decoder = new HeaderValueDecoder();
		FastBuf buf = new FastBuf(1024);
		HttpFastBufDataRange hbdr = new HttpFastBufDataRange();
		HttpRequest request = new ValueHttpRequest();

		hbdr.buffer(buf);
		HeaderValue val = decoder.decode(buf, hbdr, request);
		assertNull(val);

		buf.reset();
		buf.write(" ".getBytes(StandardCharsets.UTF_8));
		val = decoder.decode(buf, hbdr, request);
		assertNull(val);

		buf.reset();
		buf.write(" \r".getBytes(StandardCharsets.UTF_8));
		val = decoder.decode(buf, hbdr, request);
		assertNull(val);

		buf.reset();
		buf.write(" ab\r".getBytes(StandardCharsets.UTF_8));
		val = decoder.decode(buf, hbdr, request);
		assertNull(val);

		buf.reset();
		buf.write(" ab".getBytes(StandardCharsets.UTF_8));
		val = decoder.decode(buf, hbdr, request);
		assertNull(val);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> {
					buf.reset();
					buf.write(" \r\t".getBytes(StandardCharsets.UTF_8));
					decoder.decode(buf, hbdr, request);
				});
		assertTrue(ex.getMessage().contains("HeaderValue: Illegal name can't have \\r!"));

		buf.reset();
		buf.write("gzip, deflate, br\r\n".getBytes(StandardCharsets.UTF_8));
		val = decoder.decode(buf, hbdr, request);
		assertNotNull(val);
		assertEquals(val.getValue(), "gzip, deflate, br");
	}
}
