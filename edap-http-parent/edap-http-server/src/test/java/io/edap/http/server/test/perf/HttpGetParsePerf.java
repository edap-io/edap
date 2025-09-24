/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.http.server.test.perf;

import io.edap.buffer.FastBuf;
import io.edap.http.*;
import io.edap.http.cache.HeaderNameCache;
import io.edap.http.cache.HeaderValueCache;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.rangedecoder.ContentTypeValueDecoder;
import io.edap.http.rangedecoder.HeaderValueDecoder;
import io.edap.http.server.BytesHttpRequestDecoder;
import io.edap.http.server.RangeHttpRequestDecoder;
import io.edap.http.server.cache.PathCache;
import io.edap.nio.codec.FastBufDataRange;
import io.edap.util.ByteArrayBuilder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 5)
@Threads(1)
@Fork(1)
@State(value = Scope.Benchmark)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HttpGetParsePerf {

    static ValueHttpRequest request;
    static HttpFastBufDataRange hbr = new HttpFastBufDataRange();
    static FastBuf buf;
	static FastBuf fastBuf;
    static byte[] httpData;
	static byte[] fastHttpData;
    static RangeHttpRequestDecoder decoder = new RangeHttpRequestDecoder();
    static BytesHttpRequestDecoder bytesDecoder = new BytesHttpRequestDecoder();
    static ByteArrayBuilder byteArrayBuilder;
    static HeaderNameCache headerNameCache;
    static FastBufDataRange range = FastBufDataRange.from("Host");

    static {
        request = new ValueHttpRequest();
        buf = new FastBuf(4096);
		fastBuf = new FastBuf(4096);
		try {
			fastHttpData = ("GET " + URLEncoder.encode("/zh_cn/A1SRI-2758F", "utf-8") + " HTTP/1.1\r\n" +
					"Host: server\r\n" +
					"User-Agent: Mozilla/5.0 (X11; Linux x86_64) Gecko/20130501 Firefox/30.0 AppleWebKit/600.00 Chrome/30.0.0000.0 Trident/10.0 Safari/600.00\r\n" +
					"Cookie: uid=12345678901234567890;__utma=1.1234567890.1234567890.1234567890.1234567890.12; wd=2560x1600\r\n" +
					"Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n" +
					"Accept-Language: en-US,en;q=0.5\r\n" +
					"Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.UTF_8);
			httpData = ("GET " + URLEncoder.encode("/zh_cn/A1SRI-2758E", "utf-8") + " HTTP/1.1\r\n" +
					"Host: server\r\n" +
					"User-Agent: Mozilla/5.0 (X11; Linux x86_64) Gecko/20130501 Firefox/30.0 AppleWebKit/600.00 Chrome/30.0.0000.0 Trident/10.0 Safari/600.00\r\n" +
					"Cookie: uid=12345678901234567890;__utma=1.1234567890.1234567890.1234567890.1234567890.12; wd=2560x1600\r\n" +
					"Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n" +
					"Accept-Language: en-US,en;q=0.5\r\n" +
					"Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.UTF_8);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
		buf.write(httpData);
		fastBuf.write(fastHttpData);

        byteArrayBuilder = new ByteArrayBuilder();




        PathCache CACHE   = PathCache.instance();
        headerNameCache = HeaderNameCache.instance();
        headerNameCache.get(range);
		HeaderValueDecoder connectionDecoder = new ContentTypeValueDecoder();
		FastBufDataRange connectionDecoderDr = FastBufDataRange.from("Connection");
		HeaderName connectionName = headerNameCache.get(connectionDecoderDr);
		connectionName.valueDecoder = connectionDecoder;

		HeaderValueCache hvcache = HeaderValueCache.instance();
		hvcache.get(FastBufDataRange.from("server"));
		hvcache.get(FastBufDataRange.from("keep-alive"));

//		HeaderValueDecoder hostDecoder = new HeaderValueCacheDecoder();
//		FastBufDataRange hostDecoderDr = FastBufDataRange.from("Host");
//		HeaderName hostName = headerNameCache.get(hostDecoderDr);
//		hostName.valueDecoder = hostDecoder;


        CACHE.registerHandler("/zh_cn/A1SRI-2758F", new HttpHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse resp) {

            }
        });
		PathInfo pathInfo = CACHE.get("/zh_cn/A1SRI-2758F");
		HttpHandleOption option = new HttpHandleOption();
		option.setLazyParseHeader(true);
		pathInfo.setHandlerOption(option);
//        CACHE.registerHandler("/zh_cn/motherboard/A1SRI-2758F2", new HttpHandler() {
//            @Override
//            public void handle(HttpRequest req, HttpResponse resp) {
//
//            }
//        });
//        CACHE.registerHandler("/zh_cn/motherboard/A1SRI-2758F3", new HttpHandler() {
//            @Override
//            public void handle(HttpRequest req, HttpResponse resp) {
//
//            }
//        });
//        CACHE.registerHandler("/zh_cn/motherboard/A1SRI-2758F4", new HttpHandler() {
//            @Override
//            public void handle(HttpRequest req, HttpResponse resp) {
//
//            }
//        });
    }

	@Benchmark
	public void dataRangeFastParse() {

		HttpDecoder.State state = HttpDecoder.State.SKIP_CONTROL_CHARS;
		fastBuf.rewind();
		request.reset();
		hbr.reset();
		AbstractHttpDecoder.Result res = decoder.parseHttpRequest(fastBuf, state, hbr, request, null);
//        assertNotNull(res);
	}

    @Benchmark
    public void dataRangeParse() {

        HttpDecoder.State state = HttpDecoder.State.SKIP_CONTROL_CHARS;
        buf.rewind();
        request.reset();
        hbr.reset();
        AbstractHttpDecoder.Result res = decoder.parseHttpRequest(buf, state, hbr, request, null);
//        assertNotNull(res);
    }

    //@Benchmark
    public void dataRangePerf() {
        headerNameCache.get(range);
    }

    @Benchmark
    public void bytesParse() {
        HttpDecoder.State state = HttpDecoder.State.SKIP_CONTROL_CHARS;
        buf.rewind();
        request.reset();
        byteArrayBuilder.setLength(0);
        bytesDecoder.parseHttpRequest(buf, state, byteArrayBuilder, request, null);
    }

	@Benchmark
	public void bytesFastParse() {
		HttpDecoder.State state = HttpDecoder.State.SKIP_CONTROL_CHARS;
		fastBuf.rewind();
		request.reset();
		byteArrayBuilder.setLength(0);
		bytesDecoder.parseHttpRequest(fastBuf, state, byteArrayBuilder, request, null);
	}

    public static void main(String[] args) throws RunnerException {

        Options opt = new OptionsBuilder()
                .include(HttpGetParsePerf.class.getSimpleName())
                .result("result.json")
                .resultFormat(ResultFormatType.JSON).build();
        new Runner(opt).run();
    }
}
