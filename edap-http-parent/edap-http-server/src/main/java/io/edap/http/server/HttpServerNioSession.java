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
import io.edap.http.header.HeaderConnection;
import io.edap.http.ws.AbstractFrame;
import io.edap.http.ws.AuthResult;
import io.edap.http.ws.Ping;
import io.edap.http.ws.WSAuthenticator;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.pool.Pool;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.CollectionUtils;
import io.edap.util.CryptUtil;
import io.edap.util.Md5Util;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.edap.http.header.UpgradeHeader.UPGRADE_WEBSOCKET;
import static io.edap.http.server.HttpServer.NOT_FOUND_HANDLER;
import static io.edap.http.server.HttpServer.NOT_SUPPORT_METHO_HANDLER;
import static io.edap.http.ws.AbstractFrame.*;

public class HttpServerNioSession extends HttpNioSession implements WSConnection {

	Logger log = LoggerManager.getLogger(HttpServerNioSession.class);

	static ThreadLocal<HttpResponse> THREAD_HTTP_RESPONSE;
	static BufPool BUF_POOL;
	static String WEBSOCKET_SEC_KEY = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
	static Base64.Encoder BASE64_ENCODER = Base64.getEncoder();

	private Map<String, Object> sessionContext;
	/**
	 * 是否已经升级到WebSocket
	 */
	private boolean upgraded;

	/**
	 * 协议连接策略对象（HTTP/1.1 或 HTTP/2）。
	 *
	 * <p>首次 decode 时由 {@link HttpConnectionFactory} 嗅探协议后创建；
	 * 之后本 session 的解码与 flush 全部委派给它。</p>
	 */
	private HttpConnection connection;

	/**
	 * 协议嗅探 + 分派（跨 read 保存暂存字节），per-session。
	 */
	private HttpConnectionFactory connectionFactory;

	private WSHandler wsHandler;

	private WebsocketDecoder wsDecoder;

	private HttpDecoder.WSState wsState;

	private AbstractFrame tmpWSFrame;

	private List<AbstractFrame> tmpWSFrames;

	private HttpRequest httpRequest;

	static {
		THREAD_HTTP_RESPONSE = ThreadLocal.withInitial(() -> {
			HttpResponse response = new HttpResponse();
			return response;
		});

	}

	public void setBufPool(BufPool bufPool) {
		BUF_POOL = bufPool;
	}

	public boolean decode(FastBuf fastBuf, boolean threadSwitch) throws IOException {
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
			// ★ 协议嗅探 + 委派：HTTP/1.1 与 HTTP/2 的差异全部收敛在 HttpConnection 里。
			//   嗅探需要至少 3 个字节（"PRI" vs 其它 method），数据不足时返回 false 等下一次 read。
			if (connection == null) {
				connection = createConnection(fastBuf);
				if (connection == null) {
					return false;
				}
			}
			return connection.decode(fastBuf);
		}
	}

	/**
	 * 创建协议连接对象（首次 decode 时调用一次）。
	 *
	 * <p>默认实现是「嗅探 + 分派」：看前 3 个字节是不是 {@code "PRI"}
	 * （HTTP/2 连接前言的开头），是则建 {@code Http2Connection}，否则建 {@code Http1Connection}。</p>
	 *
	 * <p>★ 抽成 protected 钩子是为了让 TLS 会话能<b>跳过嗅探</b>：
	 * TLS 握手的 ALPN 已经明确协商出协议（{@code h2} / {@code http/1.1}），
	 * 没有必要再靠字节猜。</p>
	 *
	 * @param buf 本次 read 的缓冲
	 * @return 连接对象；{@code null} 表示数据不足，等下一次 read
	 */
	protected HttpConnection createConnection(FastBuf buf) throws IOException {
		if (connectionFactory == null) {
			connectionFactory = new HttpConnectionFactory(this);
		}
		return connectionFactory.detect(buf);
	}

	/**
	 * 把待写缓冲刷到 socket。
	 *
	 * <p>这是「协议无关」的入口：委派给当前的协议连接对象。
	 * HTTP/1.1 路径最终会走到 {@link #flushSessionBuffer(Pool)}，
	 * HTTP/2 路径自己管帧的原子写出。</p>
	 */
	@Override
	public void flush(Pool<FastBuf> bbPool) throws IOException {
		if (connection == null) {
			super.flush(bbPool);
			return;
		}
		connection.flush(bbPool);
	}

	/**
	 * 真正把待写缓冲写到 socket（HTTP/1.1 路径用）。
	 *
	 * <p>与 {@link #flush(Pool)} 分开是为了避免死循环：{@code flush} 委派给协议连接，
	 * 而 HTTP/1.1 的连接对象又需要把缓冲刷出去 —— 两者必须指向不同的方法。</p>
	 */
	public void flushSessionBuffer(Pool<FastBuf> bbPool) throws IOException {
		super.flush(bbPool);
	}

	/**
	 * NIO 层把写队列头写完之后回调 —— 协议连接据此继续发剩下的数据。
	 *
	 * <p><b>★ 这是 HTTP/2 大响应能发完的关键</b>：内核的发送缓冲不保证一次收得下一整条
	 * 响应（{@code AcceptEventHandler} 现在<b>不</b>钉死 SO_SNDBUF，交给内核自动调优），
	 * 而 HTTP/2 的发送窗口是 1MB。大响应第一次写有可能写不完，残余字节挂在 session 的
	 * 写队列上，等 {@code OP_WRITE} 事件；队列头写完时 NIO 层回调
	 * {@code removeWriteBuf}，协议连接必须在这里把剩下的帧继续发出去，
	 * 否则那些帧永远卡在队列里（对端表现为「响应只发了一半就停住」）。</p>
	 */
	@Override
	public void removeWriteBuf(FastBuf wbuf) {
		super.removeWriteBuf(wbuf);
		// ★ 顺序要紧：先把「协议层自己挂着的残余数据」排空，再让 HTTP/2 发新帧。
		//   TLS 的密文 backlog 比新帧旧 —— 顺序反了会把 TLS 记录写乱（对端直接报解密失败）。
		onWriteBufRemoved(wbuf);
		HttpConnection c = this.connection;
		if (c != null) {
			c.onWriteBufDrained();
		}
	}

	/**
	 * NIO 写队列头被摘掉之后的钩子。
	 *
	 * <p>默认什么都不做。TLS 会话覆写它：把还没写出去的密文排进 socket，
	 * 保证「旧字节先于新字节」发出去。</p>
	 *
	 * @param wbuf 刚被摘掉的写队列头
	 */
	protected void onWriteBufRemoved(FastBuf wbuf) {
	}

	/**
	 * 拿到本 session 的 {@link SelectionKey}。
	 *
	 * <p>NIO 层只在「首次部分写」时才缓存 selectionKey（见 {@code NioServerSession.flush}），
	 * 而 HTTP/2 需要在第一次写之前就拿到它来注册 {@code OP_WRITE}，
	 * 所以这里从 channel 反查一次并缓存下来。</p>
	 */
	public SelectionKey obtainSelectionKey() {
		SelectionKey key = getSelectionKey();
		if (key != null) {
			return key;
		}
		SocketChannel ch = getSocketChannel();
		Selector sel = getSelector();
		if (ch == null || sel == null) {
			return null;
		}
		key = ch.keyFor(sel);
		if (key != null) {
			setSelectionKey(key);
		}
		return key;
	}

	/**
	 * 切换协议连接对象（h2c 升级时从 HTTP/1.1 切到 HTTP/2）。
	 */
	void setConnection(HttpConnection connection) {
		this.connection = connection;
	}

	/**
	 * 注册 {@code OP_WRITE}（<b>保留</b> {@code OP_READ}）。
	 *
	 * <p>★ 不能用 {@code channel.register(selector, OP_WRITE, this)} ——
	 * 那会把 interestOps 整个替换掉，{@code OP_READ} 丢失后服务端再也读不到
	 * 对端的 WINDOW_UPDATE，双方死锁。</p>
	 */
	public void enableWriteInterest() {
		if (io.edap.http.server.http2.Http2WriteStats.ENABLED) {
			io.edap.http.server.http2.Http2WriteStats.opWriteEnables.increment();
		}
		SelectionKey key = obtainSelectionKey();
		if (key == null || !key.isValid()) {
			return;
		}
		int ops = key.interestOps();
		if ((ops & SelectionKey.OP_WRITE) == 0) {
			key.interestOps(ops | SelectionKey.OP_WRITE);
		}
	}

	/**
	 * 撤销 {@code OP_WRITE}。
	 *
	 * <p>不撤销的话 selector 会一直被「socket 可写」唤醒，空转烧 CPU。</p>
	 */
	public void disableWriteInterest() {
		SelectionKey key = obtainSelectionKey();
		if (key == null || !key.isValid()) {
			return;
		}
		int ops = key.interestOps();
		if ((ops & SelectionKey.OP_WRITE) != 0) {
			key.interestOps(ops & ~SelectionKey.OP_WRITE);
		}
	}

	/**
	 * 把线程写缓冲里的字节直接刷到 socket。
	 *
	 * <p><b>为什么不能直接用 {@code NioServerSession.flush}</b>：它在部分写时会
	 * {@code channel.register(selector, OP_WRITE, this)}，把 interestOps 整个替换掉，
	 * {@code OP_READ} 被丢弃 —— socket 一旦写满，服务端再也读不到对端的
	 * WINDOW_UPDATE，HTTP/2 双方死锁。</p>
	 *
	 * <p>h2c 升级用它把 101 响应先发出去：101 必须<b>先于</b>任何 HTTP/2 帧到达客户端，
	 * 而 HTTP/2 的帧写出器是直接写 socket 的（不经过线程写缓冲），
	 * 所以 101 必须在这里同步刷出去。</p>
	 */
	public void flushThreadWriteBuf() throws IOException {
		FastBuf writeBuf = THREAD_WRITE_BUF.get();
		if (writeBuf.remain() <= 0) {
			return;
		}
		if (writeToChannel(writeBuf)) {
			// writeToChannel 内部已把缓冲 clear 掉
			return;
		}
		// socket 写不动 —— 残余字节挂到 NIO 写队列，等 OP_WRITE 事件继续写
		putToWriteQueue(writeBuf);
		THREAD_WRITE_BUF.set(new FastBuf(32768));
		enableWriteInterest();
	}

	private void handeshake(HttpRequest request, HttpResponse resp, WSAuthenticator wsAuthenticator) throws IOException {
		HeaderValue upgradeVal = request.getHeaderValue("Upgrade");
		HeaderValue connectionVal = request.getHeaderValue("Connection");
		HeaderValue secKeyVal = request.getHeaderValue("Sec-WebSocket-Key");
		HeaderValue versionVal = request.getHeaderValue("Sec-WebSocket-Version");
		if (upgradeVal != null && upgradeVal.getValue().equalsIgnoreCase("websocket")
				&& connectionVal != null && connectionVal.getValue().equalsIgnoreCase("Upgrade")
				&& secKeyVal != null) {
			if (versionVal == null || !versionVal.getValue().equalsIgnoreCase("13")) {
				resp.setSimpleResponse(400, null);
				return;
			}
			// per-path 鉴权（Container.deployAppRoutes 已按 byType 注入；理论上非 null）
			if (wsAuthenticator == null) {
				log.warn("WS path {} 未配置 WSAuthenticator，拒绝握手", l -> l.arg(request.getPath()));
				resp.setSimpleResponse(401, null);
				return;
			}
			AuthResult result;
			try {
				result = wsAuthenticator.verify(request);
			} catch (Throwable t) {
				log.warn("WSAuthenticator.verify 抛异常", l -> l.threw(t));
				resp.setSimpleResponse(500, null);
				return;
			}
			if (result == null || !result.ok()) {
				int status = result == null ? 401 : result.status();
				byte[] body = (result == null || result.reason() == null)
						? new byte[0]
						: result.reason().getBytes(StandardCharsets.UTF_8);
				resp.write(status, body);
				return;
			}
			// 鉴权成功 → 计算 Sec-WebSocket-Accept + 协议升级 101
			String secAccept = new String(BASE64_ENCODER.encode(CryptUtil.sha1(
					secKeyVal.getValue() + WEBSOCKET_SEC_KEY)));
			Map<String, String> headers = new HashMap<>();
			headers.put("Sec-WebSocket-Accept", secAccept);
			resp.setSimpleResponse(101, headers, HeaderConnection.UPGRADE, UPGRADE_WEBSOCKET);
			upgraded = true;
			this.httpRequest = request;
			// principal → sessionContext（wsHandler.onOpen / 业务 handler 可取）
			if (result.principal() != null) {
				setSessionContext("principal", result.principal());
			}
			wsHandler.onOpen(this);
		} else {
			resp.setSimpleResponse(400, null);
		}
	}

	@Override
	public void handle(HttpRequest request) throws IOException {
		PathInfo pathInfo = request.getPathInfo();
		HttpHandler handler = null;
		HttpResponse resp = request.getResponse();
		resp.setNioSession(this);
		resp.setRequest(request);
		if (pathInfo.isFound()) {
			if (pathInfo.getWsHandler() != null) {
				// WebSocket over HTTP/2 需要 RFC 8441（extended CONNECT），本次不实现：
				// 服务端不广告 SETTINGS_ENABLE_CONNECT_PROTOCOL，收到 CONNECT 一律 RST_STREAM。
				// 所以 h2 下命中 WS 路径直接回 501，不走握手。
				HttpVersion version = request.getVersion();
				if (version != null && version.isHttp2()) {
					resp.setSimpleResponse(501, null);
					return;
				}
				wsHandler = pathInfo.getWsHandler();
				handeshake(request, resp, pathInfo.getWsAuthenticator());
				return;
			} else {
				// dispatch：旧实现按 HTTP method index 索引 httpHandlers[]（一个 path 多个 method 各占一位），
				// 新模型每个 path 对应一个（proto service 方法 → asm handler），路径已把方法编进去了（{@code /Service/method}），
				// 所以 [0] 就是该 path 的唯一 handler。
				// HTTP method (GET/POST/...) 的校验交由 asm 生成 handler 在字节码里完成（基于 @ProtoHttp.method 元数据）。
				HttpHandler[] handlers = pathInfo.getHttpHandlers();
				try {
					if (handlers != null && handlers.length > 0) {
						handler = handlers[request.getMethodInfo().getMethodIndex()];
					}
				} catch (Exception e) {
					log.warn("{} get HttpHandler error {}", (l -> l.arg(request.getPath()).arg(e)));
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
	public HttpRequest getHttpRequest() {
		return httpRequest;
	}

	@Override
	public void setSessionContext(String key, Object value) {
		synchronized (this) {
			if (sessionContext == null) {
				sessionContext = new HashMap<>();
			}
			sessionContext.put(key, value);
		}
	}

	@Override
	public Object getSessionContext(String key) {
		return sessionContext == null ? null : sessionContext.get(key);
	}

	@Override
	public void clearSessionContext() {
		synchronized (this) {
			if (sessionContext != null) {
				sessionContext.clear();
			}
		}
	}

	@Override
	public Set<String> getSessionContextKeys() {
		if (sessionContext == null || sessionContext.isEmpty()) {
			return Collections.EMPTY_SET;
		}
		return sessionContext.keySet();
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
			buf.write((byte)len);
			if (len > 0) {
				buf.write(frame.getPayload());
			}
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
