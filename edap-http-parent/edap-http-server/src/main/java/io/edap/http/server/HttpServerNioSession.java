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
import io.edap.http.ws.CloseFrame;
import io.edap.http.ws.Ping;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.ParseResult;
import io.edap.nio.util.BytesBuilder;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.CollectionUtils;
import io.edap.util.CryptUtil;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.edap.http.header.UpgradeHeader.UPGRADE_WEBSOCKET;
import static io.edap.http.server.HttpServer.NOT_FOUND_HANDLER;
import static io.edap.http.server.HttpServer.NOT_SUPPORT_METHO_HANDLER;
import static io.edap.http.server.WebsocketDecoder.*;
import static io.edap.http.ws.AbstractFrame.*;

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

	private List<AbstractFrame> tmpWSFrames;
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
			AbstractFrame frame = wsDecoder.decode(fastBuf, this);
			if (frame == null) {
				return false;
			}
			switch (frame.getOpcode()) {
				case TEXT_OPCODE:
					if (frame.isFin()) {
						if (!CollectionUtils.isEmpty(tmpWSFrames)) {
							StringBuilder sb = new StringBuilder();
							for (AbstractFrame f : tmpWSFrames) {
								sb.append(new String(f.getPayload(), StandardCharsets.UTF_8));
							}
							sb.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
							tmpWSFrames.clear();
							wsHandler.onMessage(this, sb.toString());
						} else {
							wsHandler.onMessage(this, new String(frame.getPayload(), StandardCharsets.UTF_8));
						}
					} else {
						if (tmpWSFrames == null) {
							tmpWSFrames = new ArrayList<>();
						}
						tmpWSFrames.add(frame);
					}
					break;
				case BINARY_OPCODE:
					if (frame.isFin()) {
						if (!CollectionUtils.isEmpty(tmpWSFrames)) {
							ByteArrayBuilder bb = new ByteArrayBuilder();
							for (AbstractFrame f : tmpWSFrames) {
								bb.append(f.getPayload());
							}
							bb.append(frame.getPayload());
							tmpWSFrames.clear();
							wsHandler.onMessage(this, bb.toByteArray());
						} else {
							wsHandler.onMessage(this, frame.getPayload());
						}
					} else {
						if (tmpWSFrames == null) {
							tmpWSFrames = new ArrayList<>();
						}
						tmpWSFrames.add(frame);
					}
					break;
				case PING_OPCODE:
					wsHandler.onPing(this, (Ping)frame);
					break;
				case CLOSE_OPCODE:
					wsHandler.onClose( this);
					break;
			}
			return true;
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

	@Override
	public void sendFrame(AbstractFrame frame) {
		FastBuf buf = THREAD_WRITE_BUF.get();
		int first = 1 << 7 | (frame.getRsv() & 0x7) << 4 | frame.getOpcode() & 0xf;
		int len = (int)frame.getPayloadLength();
		if (len <= 125) {
			if (buf.writeRemain() < len + 2) {
				FastBuf nbuf = new FastBuf(len + 2);
				THREAD_WRITE_BUF.set(nbuf);
				buf = nbuf;
			}
			buf.write((byte)first);
			buf.write((byte)frame.getPayloadLength());
			buf.write(frame.getPayload());
		} else if (len <= 65536) {
			if (buf.writeRemain() < len + 4) {
				FastBuf nbuf = new FastBuf(len + 4);
				THREAD_WRITE_BUF.set(nbuf);
				buf = nbuf;
			}
			buf.write((byte)first);
			buf.write((byte)126);
			buf.write((byte)(len >> 8));
			buf.write((byte)(len & 0xff));
			buf.write(frame.getPayload());
		} else {
			if (buf.writeRemain() < len + 10) {
				FastBuf nbuf = new FastBuf(len + 10);
				THREAD_WRITE_BUF.set(nbuf);
				buf = nbuf;
			}
			buf.write((byte)(len >> 56));
			buf.write((byte)(len >> 48));
			buf.write((byte)(len >> 40));
			buf.write((byte)(len >> 32));
			buf.write((byte)(len >> 24));
			buf.write((byte)(len >> 16));
			buf.write((byte)(len >>  8));
			buf.write((byte)(len & 0xff));
			buf.write(frame.getPayload());
		}
	}
}
