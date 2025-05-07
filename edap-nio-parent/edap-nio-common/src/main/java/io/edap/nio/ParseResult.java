package io.edap.nio;

import java.util.List;

/**
 * 网络协议解析结果
 * @param <T> 协议消息体类型
 */
public class ParseResult<T> {
    /**
     * 是否解析到完整的消息体
     */
    private boolean finished;
    /**
     * 解析出的消息体列表
     */
    private T message;

    private int code;
    private String error;

    public boolean isFinished() {
        return finished;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }

    public T getMessage() {
        return message;
    }

    public void setMessage(T message) {
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
