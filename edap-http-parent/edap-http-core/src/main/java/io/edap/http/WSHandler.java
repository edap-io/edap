package io.edap.http;

import io.edap.http.ws.Ping;
import io.edap.http.ws.Pong;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

/**
 * WebSocket处理器的接口定义
 */
public interface WSHandler {

    Logger log = LoggerManager.getLogger(WSHandler.class);

    Pong PONG = new Pong();

    /**
     * 有新的连接创建时触发的操作
     * @param webSocket
     */
    default void onOpen(WSConnection webSocket) {

    }

    /**
     * 收文本消息时触发的操作,默认为空实现，防止抛异常
     * @param webSocket WebSocket的连接实例
     * @param message 文本消息
     */
    default void onMessage(WSConnection webSocket, String message) {
        System.out.println("text message: " + message);
    }

    /**
     * 收到字节消息时触发的操作
     * @param webSocket WebSocket的连接实例
     * @param message 字节消息
     */
    default void onMessage(WSConnection webSocket, byte[] message) {

    }
    /**
     * 错误时触发的操作
     * @param webSocket
     * @param throwable
     */
    default void onError(WSConnection webSocket, Throwable throwable) {

    }

    default void onPing(WSConnection webSocket, Ping ping) {
        log.info("ping msg");
        webSocket.sendFrame(PONG);
    }
    /**
     * 连接关闭时触发的操作
     * @param webSocket
     */
    default void onClose(WSConnection webSocket) {

    }

    /**
     * websocket协议uprade时进行token校验
     * @param token 用户的accessToken
     * @return
     */
    default boolean tokenVerify(String token) {
        return true;
    }
}
