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

package io.edap.http.api.test.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HeaderName;
import io.edap.http.HttpRequest;
import io.edap.http.ValueHttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.rangedecoder.HeaderNameDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class HeaderNameDecoderTest {

	@Test
	public void testDecode() {
		FastBuf buf = new FastBuf(1024);
		HttpFastBufDataRange hbdr = new HttpFastBufDataRange();
		HttpRequest request = new ValueHttpRequest();

		HeaderNameDecoder decoder = new HeaderNameDecoder();
		hbdr.buffer(buf);
		HeaderName headerName = decoder.decode(buf, hbdr, request);
		assertNull(headerName);

		buf.reset();
		buf.write("\r".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, hbdr, request);
		assertNull(headerName);

		buf.reset();
		buf.write("\r\n".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, hbdr, request);
		assertNotNull(headerName);
		assertTrue(headerName.finish);

		buf.reset();
		buf.write("\r\t".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, hbdr, request);
		assertNull(headerName);

		buf.reset();
		buf.write("Accept-Language".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, hbdr, request);
		assertNull(headerName);

		buf.reset();
		buf.write("Accept-Language:".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, hbdr, request);
		assertNotNull(headerName);
		assertEquals(headerName.name, "Accept-Language");
		assertEquals(hbdr.first(), (byte)'A');
		assertEquals(hbdr.last(), (byte)'e');
		assertEquals(hbdr.length(), 15);

		buf.reset();
		buf.write("Accept-Language :".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, hbdr, request);
		assertNotNull(headerName);
		assertEquals(headerName.name, "Accept-Language");

		buf.reset();
		buf.write("Accept-Language  :".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, hbdr, request);
		assertNotNull(headerName);
		assertEquals(headerName.name, "Accept-Language");

		buf.reset();
		buf.write("Accept-Language     :".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, hbdr, request);
		assertNotNull(headerName);
		assertEquals(headerName.name, "Accept-Language");

		buf.reset();
		buf.write("Accept-Language ".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, hbdr, request);
		assertNull(headerName);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> {
					buf.reset();
					buf.write("Accept-Language T:".getBytes(StandardCharsets.UTF_8));
					decoder.decode(buf, hbdr, request);
				});
		assertTrue(ex.getMessage().contains("HeaderName: Illegal name can't have space!"));
	}
}
