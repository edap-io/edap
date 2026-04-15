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
import io.edap.Decoder;
import io.edap.buffer.FastBuf;
import io.edap.http.*;
import io.edap.http.header.HeaderConnection;
import io.edap.http.ws.AbstractFrame;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.ParseResult;
import io.edap.util.CryptUtil;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static io.edap.http.header.UpgradeHeader.UPGRADE_WEBSOCKET;
import static io.edap.http.server.HttpServer.NOT_FOUND_HANDLER;
import static io.edap.http.server.HttpServer.NOT_SUPPORT_METHO_HANDLER;

public class HttpServerNioSession extends HttpNioSession implements WSConnection {

	Logger log = LoggerManager.getLogger(HttpServerNioSession.class);

	static ThreadLocal<HttpResponse> THREAD_HTTP_RESPONSE;
	static BufPool BUF_POOL;
	static String WEBSOCKET_SEC_KEY = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
	static Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
	/**
	 * 是否已经升级到WebSocket
	 */
	private boolean upgraded;

	private WSHandler wsHandler;

	private WebsocketDecoder wsDecoder;

	private HttpDecoder.WSState wsState;

	private AbstractFrame tmpWSFrame;
	static {
		THREAD_HTTP_RESPONSE = ThreadLocal.withInitial(() -> {
			HttpResponse response = new HttpResponse();
			return response;
		});

	}

	public void setBufPool(BufPool bufPool) {
		BUF_POOL = bufPool;
	}

	public boolean decode(FastBuf fastBuf, boolean threadSwitch) {
		boolean hasMsg = false;
		if (upgraded) {
			wsDecoder.decode(fastBuf, this);
		} else {
			Decoder _decoder = decoder;
			while (fastBuf.remain() > 0) {
				ParseResult pr = _decoder.decode(fastBuf, this);
				if (!pr.isFinished()) {
					break;
				}
				hasMsg = true;
				handle((HttpRequest) pr.getMessage());
			}
		}
		return hasMsg;
	}

	private void handeshake(HttpRequest request, HttpResponse resp) {
		HeaderValue upgradeVal = request.getHeaderValue("Upgrade");
		HeaderValue connectionVal = request.getHeaderValue("Connection");
		HeaderValue secKeyVal = request.getHeaderValue("Sec-WebSocket-Key");
		HeaderValue versionVal = request.getHeaderValue("Sec-WebSocket-Version");
		HeaderValue secProtocolVal = request.getHeaderValue("Sec-WebSocket-Protocol");
		HeaderValue secExtVal = request.getHeaderValue("Sec-WebSocket-Extensions");
		HeaderValue originVal = request.getHeaderValue("Origin");
		if (upgradeVal != null && upgradeVal.getValue().equalsIgnoreCase("websocket")
				&& connectionVal != null && connectionVal.getValue().equalsIgnoreCase("Upgrade")
				&& secKeyVal != null) {
			if (versionVal == null || !versionVal.getValue().equalsIgnoreCase("13")) {
				resp.setSimpleResponse(400, null);
			} else {
				String secAccept = new String(BASE64_ENCODER.encode(CryptUtil.sha1(
						secKeyVal.getValue() + WEBSOCKET_SEC_KEY)));
				Map<String, String> headers = new HashMap<>();
				headers.put("Sec-WebSocket-Accept", secAccept);
				resp.setSimpleResponse(101, headers, HeaderConnection.UPGRADE, UPGRADE_WEBSOCKET);
				upgraded = true;
				wsHandler.onOpen(this);
			}
		} else {
			resp.setSimpleResponse(400, null);
		}
	}

	@Override
	public void handle(HttpRequest request) {
		PathInfo pathInfo = request.getPath();
		HttpHandler handler = null;
		HttpResponse resp = request.getResponse();
		resp.setNioSession(this);
		resp.setRequest(request);
		if (pathInfo.isFound()) {
			if (pathInfo.getWsHandler() != null) {
				wsHandler = pathInfo.getWsHandler();
				handeshake(request, resp);
				return;
			} else {
				try {
					handler = pathInfo.getHttpHandlers()[request.getMethodInfo().getMethodIndex()];
				} catch (Exception e) {
					log.warn("get HttpHandler error", e);
				}
				if (handler == null) {
					handler = NOT_SUPPORT_METHO_HANDLER;
				}
			}
		} else { // 请求的路径不存在
			handler = NOT_FOUND_HANDLER;
		}
		try {
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

	public WSHandler getWsHandler() {
		return wsHandler;
	}

	public void setWsHandler(WSHandler wsHandler) {
		this.wsHandler = wsHandler;
	}

	public WebsocketDecoder getWsDecoder() {
		return wsDecoder;
	}

	public void setWsDecoder(WebsocketDecoder wsDecoder) {
		this.wsDecoder = wsDecoder;
	}

	public HttpDecoder.WSState getWsState() {
		return wsState;
	}

	public void setWsState(HttpDecoder.WSState wsState) {
		this.wsState = wsState;
	}

	public AbstractFrame getTmpWSFrame() {
		return tmpWSFrame;
	}

	public void setTmpWSFrame(AbstractFrame tmpWSFrame) {
		this.tmpWSFrame = tmpWSFrame;
	}
}
