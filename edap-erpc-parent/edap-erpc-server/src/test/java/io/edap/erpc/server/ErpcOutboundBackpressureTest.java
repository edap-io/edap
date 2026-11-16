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

package io.edap.erpc.server;

import io.edap.erpc.ErpcMethod;
import io.edap.erpc.codec.ErpcCodecIds;
import io.edap.erpc.frame.ErpcFrameType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * eRPC 服务端<b>出站背压</b>测试 —— 走「半写 → 残余交 IO 线程」那条路。
 *
 * <p><b>★ 要测的是什么</b>：出站写路径被拆成「短锁追加 + 写者令牌 + 双缓冲轮换」之后，
 * 有一条以前不存在的路径：<b>worker 写 socket 时写不全</b> ⇒ 残余字节挂进 NIO 层的
 * 写队列 ⇒ 等 {@code OP_WRITE} 事件由 IO 线程接着写。这条路必须满足：</p>
 *
 * <ul>
 *   <li><b>帧不能被劈开</b>：残余是「帧的后半截」，IO 线程接着写完它 ⇒ 客户端
 *       收到的仍然是一个完整帧（帧头里的 length 与实际载荷一致）；</li>
 *   <li><b>字节不能丢</b>：半写的那部分 + 残余那部分 = 完整载荷（逐字节相同）；</li>
 *   <li><b>不能乱序</b>：残余（老字节）必须先于新追加的字节进 socket。</li>
 * </ul>
 *
 * <p><b>★ 怎么逼出半写</b>：客户端把 {@code SO_RCVBUF} 调小（接收窗口一小），
 * 再<b>故意不读</b>一会儿 ⇒ 服务端的 socket 发送缓冲写满 ⇒ {@code write} 返回
 * 部分字节数 ⇒ 走残余交接那条路。</p>
 *
 * <p>这条路径在「客户端一直读」的基准里<b>永远走不到</b>。</p>
 */
public class ErpcOutboundBackpressureTest {

    private static final int METHOD_BIG = 2001;

    /**
     * 响应载荷大小 —— 必须<b>远大于</b>出站累积缓冲（{@code NioSession.WRITE_BUFFER_SIZE}，
     * 默认 256KB），否则累积缓冲一次就装下了，永远走不到「写不全」。
     */
    private static final int PAYLOAD_SIZE = 512 * 1024;

    /** 客户端接收缓冲 —— 调小它是为了让服务端的 socket 很快写满。 */
    private static final int RECV_BUFFER_SIZE = 16 * 1024;

    /** 「故意不读」的时长 —— 给服务端留出半写 + 交接残余的时间。 */
    private static final long SILENCE_MILLIS = 800;

    private ErpcTestServer server;

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    @BeforeEach
    public void setUp() throws IOException {
        server = ErpcTestServer.start(methods());
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private Map<Integer, ErpcMethod> methods() {
        Map<Integer, ErpcMethod> methods = new HashMap<>();
        methods.put(METHOD_BIG, new ErpcMethod(METHOD_BIG, "test.BigService", "big",
            (req, resp) -> resp.setPayload(payloadOf(req.getRequestId())),
            ErpcTestDto.class, ErpcTestDto.class));
        return methods;
    }

    /**
     * 造一个「自带身份」的载荷：<b>前 4 字节放 requestId</b>，其余字节是由
     * requestId 派生的模式。
     *
     * <p>★ 为什么要把 requestId 塞进载荷：服务端是<b>多路复用</b>的 ——
     * 一个连接上多个请求的响应会交错地写进同一条字节流。如果两个响应被劈开
     * 或交错，客户端收到的载荷里「嵌着的 requestId」就会对不上
     * ⇒ 用它来证明「帧没被劈开、字节没交错」。</p>
     */
    private static byte[] payloadOf(int requestId) {
        byte[] payload = new byte[PAYLOAD_SIZE];
        payload[0] = (byte) (requestId >>> 24);
        payload[1] = (byte) (requestId >>> 16);
        payload[2] = (byte) (requestId >>> 8);
        payload[3] = (byte) requestId;
        for (int i = 4; i < PAYLOAD_SIZE; i++) {
            payload[i] = (byte) (requestId * 31 + i);
        }
        return payload;
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /**
     * ★ 大响应 + 客户端不读 ⇒ 半写 ⇒ 残余由 IO 线程接着写完。
     *
     * <p>断言：客户端最终收到<b>完整且逐字节正确</b>的一帧。</p>
     */
    @Test
    public void bigResponseSurvivesPartialWrite() throws IOException {
        ErpcOutboundWriter.resetLockStats();
        try (ErpcRawClient raw = new ErpcRawClient(server.port(), RECV_BUFFER_SIZE)) {
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_BIG, 0, 7, 0L, new byte[0]);

            // ★ 故意不读：服务端的 socket 写不动 ⇒ 半写 ⇒ 残余交 IO 线程
            sleep(SILENCE_MILLIS);

            ErpcRawClient.RawFrame frame = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), frame.header.getType());
            assertEquals(7, frame.header.getRequestId());
            // 帧头里的长度与实际收到的字节数一致 ⇒ 帧没被劈开
            assertEquals(PAYLOAD_SIZE, frame.header.getLength());
            assertEquals(PAYLOAD_SIZE, frame.payload.length);
            assertPayloadIntact(7, frame.payload);
        }
        // ★ 这条用例的价值全在「真的走了半写那条路」—— 没走的话它就是个空断言
        assertTrue(ErpcOutboundWriter.handoffCount() > 0,
            "没有走到「残余交 IO 线程」那条路 —— 这条用例是空的");
    }

    /**
     * ★ 同一条连接上多个大响应：残余（老字节）必须<b>先于</b>新追加的字节进 socket。
     *
     * <p>断言：每个响应的载荷里嵌着的 requestId 与帧头里的 requestId 一致
     * ⇒ 帧没被劈开、两个响应没交错。</p>
     */
    @Test
    public void multipleBigResponsesOnOneConnectionStayIntact() throws IOException {
        int requests = 4;
        ErpcOutboundWriter.resetLockStats();
        try (ErpcRawClient raw = new ErpcRawClient(server.port(), RECV_BUFFER_SIZE)) {
            for (int i = 1; i <= requests; i++) {
                raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                    METHOD_BIG, 0, i, 0L, new byte[0]);
            }

            // ★ 故意不读：4 个 512KB 响应（2MB）远超 socket 缓冲 ⇒ 必然半写
            sleep(SILENCE_MILLIS);

            boolean[] seen = new boolean[requests + 1];
            for (int i = 0; i < requests; i++) {
                ErpcRawClient.RawFrame frame = raw.readFrame();
                assertEquals(ErpcFrameType.RESPONSE.getValue(), frame.header.getType());
                assertEquals(PAYLOAD_SIZE, frame.header.getLength());
                assertEquals(PAYLOAD_SIZE, frame.payload.length);
                int requestId = frame.header.getRequestId();
                assertTrue(requestId >= 1 && requestId <= requests,
                    "收到了不该有的 requestId: " + requestId);
                assertTrue(!seen[requestId], "同一个 requestId 回来了两次: " + requestId);
                seen[requestId] = true;
                // ★ 载荷里嵌的 requestId 必须与帧头一致 ⇒ 字节没交错、帧没被劈开
                assertPayloadIntact(requestId, frame.payload);
            }
            for (int i = 1; i <= requests; i++) {
                assertTrue(seen[i], "requestId=" + i + " 的响应没收到");
            }
        }
        assertTrue(ErpcOutboundWriter.handoffCount() > 0,
            "没有走到「残余交 IO 线程」那条路 —— 这条用例是空的");
    }

    /**
     * ★ 对端突然断开（写 socket 抛 IOException）之后，服务端不能垮。
     *
     * <p>这条路径对应写者循环里的异常分支：<b>令牌必须被释放</b>，
     * 否则这条连接就再也不会有人写出去（连接静默卡死）。</p>
     */
    @Test
    public void serverKeepsServingAfterPeerAborts() throws IOException {
        // 发一个请求，然后立刻 RST 掉连接 ⇒ 服务端回写时 socket 写失败
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", server.port()), 3_000);
            socket.setSoLinger(true, 0);
            byte[] payload = new byte[0];
            byte[] buf = new byte[32];
            io.edap.erpc.frame.ErpcFrameHeader header = new io.edap.erpc.frame.ErpcFrameHeader();
            header.setType(ErpcFrameType.REQUEST.getValue())
                .setCodec(ErpcCodecIds.PROTOBUF)
                .setLength(payload.length)
                .setMethodId(METHOD_BIG)
                .setServiceId(0)
                .setRequestId(1)
                .setTraceId(0L);
            header.writeTo(buf, 0);
            socket.getOutputStream().write(buf);
            socket.getOutputStream().flush();
            socket.close();
        }

        // 服务端必须还活着：换一条新连接，正常收一个响应
        sleep(200);
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_BIG, 0, 99, 0L, new byte[0]);
            ErpcRawClient.RawFrame frame = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), frame.header.getType());
            assertEquals(99, frame.header.getRequestId());
            assertEquals(PAYLOAD_SIZE, frame.payload.length);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 校验载荷：requestId 嵌对了，且模式字节一个不差。
     */
    private static void assertPayloadIntact(int requestId, byte[] payload) {
        assertEquals(requestId, readInt(payload, 0), "载荷里的 requestId 对不上 ⇒ 字节错位/交错");
        for (int i = 4; i < payload.length; i++) {
            byte expect = (byte) (requestId * 31 + i);
            if (payload[i] != expect) {
                throw new AssertionError("载荷第 " + i + " 字节不对: 期望 " + expect
                    + "，实际 " + payload[i] + " ⇒ 半写路径丢了/乱了字节");
            }
        }
    }

    private static int readInt(byte[] buf, int off) {
        return ((buf[off] & 0xff) << 24)
             | ((buf[off + 1] & 0xff) << 16)
             | ((buf[off + 2] & 0xff) << 8)
             | (buf[off + 3] & 0xff);
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}
