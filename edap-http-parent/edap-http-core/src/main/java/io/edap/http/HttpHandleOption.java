package io.edap.http;

public class HttpHandleOption {

	static HttpHandleOption HTTP_HANDLE_OPTION = new HttpHandleOption();

	/**
	 * 延迟解析header，加快http协议解码的效率
	 */
	private boolean lazyParseHeader;

	/**
	 * 延迟解析header，加快http协议解码的效率
	 */
	public boolean isLazyParseHeader() {
		return lazyParseHeader;
	}

	public void setLazyParseHeader(boolean lazyParseHeader) {
		this.lazyParseHeader = lazyParseHeader;
	}

	public static HttpHandleOption defaultHttpHandleOption() {
		return HTTP_HANDLE_OPTION;
	}
}
