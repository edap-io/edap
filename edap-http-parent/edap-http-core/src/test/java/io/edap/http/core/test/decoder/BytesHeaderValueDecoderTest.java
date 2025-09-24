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
import io.edap.http.bytesdecoder.BytesHeaderValueDecoder;
import io.edap.util.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class BytesHeaderValueDecoderTest {

	@Test
	public void testDecode() {
		BytesHeaderValueDecoder decoder = new BytesHeaderValueDecoder();
		FastBuf buf = new FastBuf(1024);
		ByteArrayBuilder sb = new ByteArrayBuilder();
		HttpRequest request = new ValueHttpRequest();

		sb.setLength(0);
		HeaderValue val = decoder.decode(buf, sb, request);
		assertNull(val);

		buf.reset();
		buf.write(" ".getBytes(StandardCharsets.UTF_8));
		val = decoder.decode(buf, sb, request);
		assertNull(val);

		buf.reset();
		buf.write(" \r".getBytes(StandardCharsets.UTF_8));
		val = decoder.decode(buf, sb, request);
		assertNull(val);

		buf.reset();
		buf.write(" ab\r".getBytes(StandardCharsets.UTF_8));
		val = decoder.decode(buf, sb, request);
		assertNull(val);

		buf.reset();
		buf.write(" ab".getBytes(StandardCharsets.UTF_8));
		val = decoder.decode(buf, sb, request);
		assertNull(val);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> {
					buf.reset();
					buf.write(" \r\t".getBytes(StandardCharsets.UTF_8));
					decoder.decode(buf, sb, request);
				});
		assertTrue(ex.getMessage().contains("HeaderValue: Illegal name can't have \\r!"));

		buf.reset();
		buf.write("gzip, deflate, br\r\n".getBytes(StandardCharsets.UTF_8));
		val = decoder.decode(buf, sb, request);
		assertNotNull(val);
		assertEquals(val.getValue().charAt(0), 'g');
		assertEquals(val.getValue(), "gzip, deflate, br");
	}
}
