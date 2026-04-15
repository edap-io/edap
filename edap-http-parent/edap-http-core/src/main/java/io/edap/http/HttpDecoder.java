package io.edap.http;

public interface HttpDecoder {

	/**
	 * 解析http请求的状态
	 */
	enum State {

		SKIP_CONTROL_CHARS,        // http请求开始的控制字符
		READ_METHOD,               // http请求的Method
		READ_PATH,                 // http请求的URI地址
		READ_QUERY_STRING,         // http请求的query字符串
		READ_HTTP_VERSION,         // http请求的http版本
		READ_HEADER,               // http请求的header
		READ_BODY,                 // http请求的BODY
		READ_FIXED_LENGTH_CONTENT, // 读取到固定长度的内容部分
		READ_CHUNK,                // 如果http为chunked编码
		BAD_MESSAGE,               // 不符合标准的消息
		UPGRADED                   // 用于升级为websocket请求的升级标示
	}

	enum WSState {
		OPCODE,
		PAYLOAD_LENGTH,
		PAYLOAD_LENGTH_EXTEND,
		MASK_KEY,
		PAYLOAD
	}
}
