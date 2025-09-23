package io.edap.http.server.test.perf;

import io.edap.buffer.FastBuf;
import io.edap.http.AbstractHttpDecoder;
import io.edap.http.HttpDecoder;
import io.edap.http.ValueHttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.server.BytesHttpRequestDecoder;
import io.edap.http.server.RangeHttpRequestDecoder;
import io.edap.util.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HttpGetBytesParseTest {

	static ValueHttpRequest request;
	static ByteArrayBuilder sb = new ByteArrayBuilder();
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

		//for (int i=0;i<1000000;i++) {
			BytesHttpRequestDecoder decoder = new BytesHttpRequestDecoder();
			HttpDecoder.State state = HttpDecoder.State.SKIP_CONTROL_CHARS;
			buf.reset();
			buf.write(httpData);
			request.reset();
			sb.setLength(0);
			AbstractHttpDecoder.Result res = decoder.parseHttpRequest(buf, state, sb, request, null);
			assertNotNull(res);

		//}

	}
}
