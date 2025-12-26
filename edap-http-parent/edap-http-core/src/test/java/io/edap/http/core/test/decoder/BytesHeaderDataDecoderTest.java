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
import io.edap.http.HttpRequest;
import io.edap.http.ValueHttpRequest;
import io.edap.http.bytesdecoder.BytesHeaderDataDecoder;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.rangedecoder.HeaderDataDecoder;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.ByteData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class BytesHeaderDataDecoderTest {

	@Test
	public void testDecoder() {
		BytesHeaderDataDecoder decoder = new BytesHeaderDataDecoder();
		FastBuf buf = new FastBuf(1024);
		ByteArrayBuilder sb = new ByteArrayBuilder();
		HttpRequest request = new ValueHttpRequest();


		sb.setLength(0);
		ByteData data = decoder.decode(buf, sb, request);
		assertNull(data);

		String headerStr = "Accept: application/json, text/javascript, */*; q=0.01\r\n" +
				"Accept-Encoding: gzip, deflate, br\r\n" +
				"Accept-Language: zh-CN,zh-Hans;q=0.9\r\n" +
				"Content-Length: 176\r\n" +
				"Content-Type: application/x-www-form-urlencoded; charset=UTF-8\r\n" +
				"Cookie: JSESSIONID=9917CB4A2AC8D106F013284E58368622; atlassian.xsrf.token=BEM7-G1BN-G1X9-U6BO_d30dc69f0d45692657c5b39ae5a530ecd3f584fa_lin\r\n" +
				"Origin: https://gfjira.yyrd.com\r\n" +
				"Priority: u=3, i\r\n" +
				"Referer: https://gfjira.yyrd.com/browse/SJRW-11186?filter=-1\r\n" +
				"Sec-Fetch-Dest: empty\r\n" +
				"Sec-Fetch-Mode: cors\r\n" +
				"Sec-Fetch-Site: same-origin\r\n" +
				"User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15\r\n" +
				"X-Atlassian-Token: no-check\r\n" +
				"X-Requested-With: XMLHttpRequest\r\n\r\n";

		buf.reset();
		buf.write(headerStr.getBytes(StandardCharsets.UTF_8));
		data = decoder.decode(buf, sb, request);
		byte[] bs = new byte[data.getLength()];
		System.arraycopy(data.getBytes(), 0, bs, 0, data.getLength());
		assertArrayEquals(bs, headerStr.substring(0, headerStr.length()-4).getBytes(StandardCharsets.UTF_8));

		buf.reset();
		buf.write(headerStr.substring(0, headerStr.length()-1).getBytes(StandardCharsets.UTF_8));
		data = decoder.decode(buf, sb, request);
		assertNull(data);

		headerStr = "Accept: application/json, text/javascript, */*; q=0.01\r\n" +
				"Accept-Encoding: gzip, deflate, br\r\n" +
				"Accept-Language: zh-CN,zh-Hans;q=0.9\r\n" +
				"Content-Length: 176\r\n" +
				"Content-Type: application/x-www-form-urlencoded; charset=UTF-8\r\n" +
				"Cookie: JSESSIONID=9917CB4A2AC8D106F013284E58368622; atlassian.xsrf.token=BEM7-G1BN-G1X9-U6BO_d30dc69f0d45692657c5b39ae5a530ecd3f584fa_lin\r\n" +
				"Origin: https://gfjira.yyrd.com\r\n" +
				"Priority: u=3, i\r\n" +
				"Referer: https://gfjira.yyrd.com/browse/SJRW-11186?filter=-1\r\n" +
				"Sec-Fetch-Dest: empty\r\n" +
				"Sec-Fetch-Mode: cors\r\n" +
				"Sec-Fetch-Site: same-origin\r\n" +
				"User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15\r\n" +
				"X-Atlassian-Token: no-check\r\n" +
				"X-Requested-With: XMLHttpRequest\rt\r\n";

		buf.reset();
		buf.write(headerStr.getBytes(StandardCharsets.UTF_8));
		data = decoder.decode(buf, sb, request);
		assertNull(data);

		headerStr = "Accept: application/json, text/javascript, */*; q=0.01\r\n" +
				"Accept-Encoding: gzip, deflate, br\r\n" +
				"Accept-Language: zh-CN,zh-Hans;q=0.9\r\n" +
				"Content-Length: 176\r\n" +
				"Content-Type: application/x-www-form-urlencoded; charset=UTF-8\r\n" +
				"Cookie: JSESSIONID=9917CB4A2AC8D106F013284E58368622; atlassian.xsrf.token=BEM7-G1BN-G1X9-U6BO_d30dc69f0d45692657c5b39ae5a530ecd3f584fa_lin\r\n" +
				"Origin: https://gfjira.yyrd.com\r\n" +
				"Priority: u=3, i\r\n" +
				"Referer: https://gfjira.yyrd.com/browse/SJRW-11186?filter=-1\r\n" +
				"Sec-Fetch-Dest: empty\r\n" +
				"Sec-Fetch-Mode: cors\r\n" +
				"Sec-Fetch-Site: same-origin\r\n" +
				"User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15\r\n" +
				"X-Atlassian-Token: no-check\r\n" +
				"X-Requested-With: XMLHttpRequest\r\nv\n";

		buf.reset();
		buf.write(headerStr.getBytes(StandardCharsets.UTF_8));
		data = decoder.decode(buf, sb, request);
		assertNull(data);

		headerStr = "Accept: application/json, text/javascript, */*; q=0.01\r\n" +
				"Accept-Encoding: gzip, deflate, br\r\n" +
				"Accept-Language: zh-CN,zh-Hans;q=0.9\r\n" +
				"Content-Length: 176\r\n" +
				"Content-Type: application/x-www-form-urlencoded; charset=UTF-8\r\n" +
				"Cookie: JSESSIONID=9917CB4A2AC8D106F013284E58368622; atlassian.xsrf.token=BEM7-G1BN-G1X9-U6BO_d30dc69f0d45692657c5b39ae5a530ecd3f584fa_lin\r\n" +
				"Origin: https://gfjira.yyrd.com\r\n" +
				"Priority: u=3, i\r\n" +
				"Referer: https://gfjira.yyrd.com/browse/SJRW-11186?filter=-1\r\n" +
				"Sec-Fetch-Dest: empty\r\n" +
				"Sec-Fetch-Mode: cors\r\n" +
				"Sec-Fetch-Site: same-origin\r\n" +
				"User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15\r\n" +
				"X-Atlassian-Token: no-check\r\n" +
				"X-Requested-With: XMLHttpRequest\r\n\ra";

		buf.reset();
		buf.write(headerStr.getBytes(StandardCharsets.UTF_8));
		data = decoder.decode(buf, sb, request);
		assertNull(data);
	}
}
