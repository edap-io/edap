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

package io.edap.http.server;

import io.edap.BufPool;
import io.edap.buffer.FastBuf;
import io.edap.http.*;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.io.IOException;
import java.nio.channels.SocketChannel;

import static io.edap.http.server.HttpServer.NOT_FOUND_HANDLER;
import static io.edap.http.server.HttpServer.NOT_SUPPORT_METHO_HANDLER;

public class HttpServerNioSession extends HttpNioSession {

	Logger log = LoggerManager.getLogger(HttpServerNioSession.class);

	static ThreadLocal<HttpResponse> THREAD_HTTP_RESPONSE;
	static BufPool BUF_POOL;

	static {
		THREAD_HTTP_RESPONSE = ThreadLocal.withInitial(() -> {
			HttpResponse response = new HttpResponse();
			return response;
		});

	}

	public void setBufPool(BufPool bufPool) {
		BUF_POOL = bufPool;
	}

	@Override
	public void handle(HttpRequest request) {
		PathInfo pathInfo = request.getPath();
		HttpHandler handler = null;
		if (pathInfo.isFound()) {
			try {
				handler = pathInfo.getHttpHandlers()[request.getMethodInfo().getMethodIndex()];
			} catch (Exception e) {
				log.warn("get HttpHandler error", e);
			}
			if (handler == null) {
				handler = NOT_SUPPORT_METHO_HANDLER;
			}
		} else { // 请求的路径不存在
			handler = NOT_FOUND_HANDLER;
		}
		FastBuf buf = THREAD_WRITE_BUF.get();
		try {
			HttpResponse resp = request.getResponse();
			resp.setNioSession(this);
			resp.setRequest(request);
			resp.setBuf(buf);

			handler.handle(request, resp);

//			SocketChannel sc = getSocketChannel();
//			if (sc.isOpen()) {
//				writeToChannel(buf);
//			} else {
//				sc.close();
//			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
