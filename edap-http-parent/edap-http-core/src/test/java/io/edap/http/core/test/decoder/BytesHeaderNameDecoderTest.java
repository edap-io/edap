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
import io.edap.http.HeaderName;
import io.edap.http.HttpRequest;
import io.edap.http.ValueHttpRequest;
import io.edap.http.bytesdecoder.BytesHeaderNameDecoder;
import io.edap.util.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class BytesHeaderNameDecoderTest {

	@Test
	public void testDecode() {
		FastBuf buf = new FastBuf(1024);
		ByteArrayBuilder sb = new ByteArrayBuilder();
		HttpRequest request = new ValueHttpRequest();

		BytesHeaderNameDecoder decoder = new BytesHeaderNameDecoder();
		sb.setLength(0);
		HeaderName headerName = decoder.decode(buf, sb, request);
		assertNull(headerName);

		buf.reset();
		buf.write("\r".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, sb, request);
		assertNull(headerName);

		buf.reset();
		buf.write("\r\n".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, sb, request);
		assertNotNull(headerName);
		assertTrue(headerName.finish);

		buf.reset();
		buf.write("\r\t".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, sb, request);
		assertNull(headerName);

		buf.reset();
		buf.write("Accept-Language".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, sb, request);
		assertNull(headerName);

		buf.reset();
		buf.write("Accept-Language:".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, sb, request);
		assertNotNull(headerName);
		assertEquals(headerName.name, "Accept-Language");

		buf.reset();
		buf.write("Accept-Language :".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, sb, request);
		assertNotNull(headerName);
		assertEquals(headerName.name, "Accept-Language");

		buf.reset();
		buf.write("Accept-Language  :".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, sb, request);
		assertNotNull(headerName);
		assertEquals(headerName.name, "Accept-Language");

		buf.reset();
		buf.write("Accept-Language     :".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, sb, request);
		assertNotNull(headerName);
		assertEquals(headerName.name, "Accept-Language");

		buf.reset();
		buf.write("Accept-Language ".getBytes(StandardCharsets.UTF_8));
		headerName = decoder.decode(buf, sb, request);
		assertNull(headerName);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> {
					buf.reset();
					buf.write("Accept-Language T:".getBytes(StandardCharsets.UTF_8));
					decoder.decode(buf, sb, request);
				});
		assertTrue(ex.getMessage().contains("HeaderName: Illegal name can't have space!"));
	}
}
