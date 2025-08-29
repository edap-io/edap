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

package io.edap.http.api.io.edap.http.api.test.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpRequest;
import io.edap.http.MethodInfo;
import io.edap.http.ValueHttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.decoder.MethodDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class MethodDecoderTest {

	@Test
	public void testDecode() {
		MethodDecoder decoder = new MethodDecoder();
		FastBuf buf = new FastBuf(1024);
		HttpFastBufDataRange hbdr = new HttpFastBufDataRange();
		HttpRequest request = new ValueHttpRequest();
		testOneChar(decoder, buf, hbdr, request);
		testTwoChar(decoder, buf, hbdr, request);
		testThreeChar(decoder, buf, hbdr, request);
		testFourChar(decoder, buf, hbdr, request);
		testFiveChar(decoder, buf, hbdr, request);
		testSixChar(decoder, buf, hbdr, request);
		testSevenChar(decoder, buf, hbdr, request);

		testNoFinished(decoder, buf, hbdr, request);
	}

	private void testNoFinished(MethodDecoder decoder, FastBuf buf,
							 HttpFastBufDataRange hbdr, HttpRequest request) {
		buf.reset();
		buf.write("C".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		MethodInfo methodInfo = decoder.decode(buf, hbdr, request);
		assertNull(methodInfo);

		buf.reset();
		buf.write("GET".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNull(methodInfo);
	}

	private void testOneChar(MethodDecoder decoder, FastBuf buf,
							 HttpFastBufDataRange hbdr, HttpRequest request) {
		buf.reset();
		buf.write("C ".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		MethodInfo methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "C");

		buf.reset();
		buf.write("C       ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "C");
	}

	private void testTwoChar(MethodDecoder decoder, FastBuf buf,
							   HttpFastBufDataRange hbdr, HttpRequest request) {
		buf.reset();
		buf.write("CO ".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		MethodInfo methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "CO");

		buf.reset();
		buf.write("CO      ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "CO");
	}

	private void testThreeChar(MethodDecoder decoder, FastBuf buf,
							   HttpFastBufDataRange hbdr, HttpRequest request) {
		testGet(decoder, buf, hbdr, request);
		testPut(decoder, buf, hbdr, request);
	}

	private void testFourChar(MethodDecoder decoder, FastBuf buf,
							   HttpFastBufDataRange hbdr, HttpRequest request) {
		testPost(decoder, buf, hbdr, request);
		testHead(decoder, buf, hbdr, request);
	}

	private void testFiveChar(MethodDecoder decoder, FastBuf buf,
							   HttpFastBufDataRange hbdr, HttpRequest request) {
		testTrace(decoder, buf, hbdr, request);
	}

	private void testSixChar(MethodDecoder decoder, FastBuf buf,
							  HttpFastBufDataRange hbdr, HttpRequest request) {
		testDelete(decoder, buf, hbdr, request);
	}

	private void testSevenChar(MethodDecoder decoder, FastBuf buf,
							 HttpFastBufDataRange hbdr, HttpRequest request) {
		testConnect(decoder, buf, hbdr, request);
		testOptions(decoder, buf, hbdr, request);
	}

	private void testConnect(MethodDecoder decoder, FastBuf buf,
							HttpFastBufDataRange hbdr, HttpRequest request) {
		buf.reset();
		buf.write("CONNECT ".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		MethodInfo methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "CONNECT");

		buf.reset();
		buf.write("CONNECT     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "CONNECT");

		buf.reset();
		buf.write("DONNECT     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "DONNECT");

		buf.reset();
		buf.write("CANNECT     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "CANNECT");

		buf.reset();
		buf.write("COONECT     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "COONECT");

		buf.reset();
		buf.write("CONOECT     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "CONOECT");

		buf.reset();
		buf.write("CONNFCT     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "CONNFCT");

		buf.reset();
		buf.write("CONNEET     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "CONNEET");

		buf.reset();
		buf.write("CONNECZ     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "CONNECZ");
	}

	private void testOptions(MethodDecoder decoder, FastBuf buf,
							 HttpFastBufDataRange hbdr, HttpRequest request) {
		buf.reset();
		buf.write("OPTIONS ".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		MethodInfo methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "OPTIONS");

		buf.reset();
		buf.write("OPTIONS     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "OPTIONS");

		buf.reset();
		buf.write("PPTIONS     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "PPTIONS");

		buf.reset();
		buf.write("OZTIONS     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "OZTIONS");

		buf.reset();
		buf.write("OPWIONS     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "OPWIONS");

		buf.reset();
		buf.write("OPTJONS     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "OPTJONS");

		buf.reset();
		buf.write("OPTIPNS     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "OPTIPNS");

		buf.reset();
		buf.write("OPTIOBS     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "OPTIOBS");

		buf.reset();
		buf.write("OPTIONT     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "OPTIONT");

		buf.reset();
		buf.write("OPTIONTA     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "OPTIONTA");
	}

	private void testDelete(MethodDecoder decoder, FastBuf buf,
						   HttpFastBufDataRange hbdr, HttpRequest request) {
		buf.reset();
		buf.write("DELETE ".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		MethodInfo methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "DELETE");

		buf.reset();
		buf.write("DELETE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "DELETE");

		buf.reset();
		buf.write("AELETE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "AELETE");

		buf.reset();
		buf.write("DFLETE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "DFLETE");

		buf.reset();
		buf.write("DEMETE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "DEMETE");

		buf.reset();
		buf.write("DELSTE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "DELSTE");

		buf.reset();
		buf.write("DELEYE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "DELEYE");

		buf.reset();
		buf.write("DELETB     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "DELETB");
	}

	private void testTrace(MethodDecoder decoder, FastBuf buf,
						  HttpFastBufDataRange hbdr, HttpRequest request) {
		buf.reset();
		buf.write("TRACE ".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		MethodInfo methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "TRACE");

		buf.reset();
		buf.write("TRACE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "TRACE");

		buf.reset();
		buf.write("TDACE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "TDACE");

		buf.reset();
		buf.write("TRECE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "TRECE");

		buf.reset();
		buf.write("TRAFE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "TRAFE");

		buf.reset();
		buf.write("TRACT     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "TRACT");

		buf.reset();
		buf.write("WRACE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "WRACE");
	}

	private void testPost(MethodDecoder decoder, FastBuf buf,
						 HttpFastBufDataRange hbdr, HttpRequest request) {
		buf.reset();
		buf.write("POST ".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		MethodInfo methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "POST");

		buf.reset();
		buf.write("POST     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "POST");

		buf.reset();
		buf.write("PAST     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "PAST");

		buf.reset();
		buf.write("POBT     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "POBT");

		buf.reset();
		buf.write("POSS     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "POSS");
	}

	private void testHead(MethodDecoder decoder, FastBuf buf,
						 HttpFastBufDataRange hbdr, HttpRequest request) {
		buf.reset();
		buf.write("HEAD ".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		MethodInfo methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "HEAD");

		buf.reset();
		buf.write("HEAD     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "HEAD");

		buf.reset();
		buf.write("HCAD     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "HCAD");

		buf.reset();
		buf.write("HEDD     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "HEDD");

		buf.reset();
		buf.write("HEAE     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "HEAE");
	}

	private void testGet(MethodDecoder decoder, FastBuf buf,
						 HttpFastBufDataRange hbdr, HttpRequest request) {
		buf.reset();
		buf.write("GET ".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		MethodInfo methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "GET");

		buf.reset();
		buf.write("GET     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "GET");

		buf.reset();
		buf.write("GST     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "GST");

		buf.reset();
		buf.write("GES     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "GES");
	}

	private void testPut(MethodDecoder decoder, FastBuf buf,
						 HttpFastBufDataRange hbdr, HttpRequest request) {
		buf.reset();
		buf.write("PUT ".getBytes(StandardCharsets.UTF_8));

		hbdr.buffer(buf);
		MethodInfo methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "PUT");

		buf.reset();
		buf.write("PUT     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "PUT");

		buf.reset();
		buf.write("PET     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "PET");

		buf.reset();
		buf.write("PUS     ".getBytes(StandardCharsets.UTF_8));
		methodInfo = decoder.decode(buf, hbdr, request);
		assertNotNull(methodInfo);
		assertEquals(methodInfo.getMethod(), "PUS");
	}
}
