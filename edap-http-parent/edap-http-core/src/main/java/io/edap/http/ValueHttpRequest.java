package io.edap.http;

import io.edap.http.header.ContentLength;
import io.edap.http.model.QueryInfo;
import io.edap.util.ByteData;
import io.edap.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 */
public class ValueHttpRequest implements HttpRequest {
    /**
     * 该请求对应的HttpNioSession的对象
     */
    private HttpNioSession httpNioSession;

    public PathInfo pathInfo;
    /**
     * Http请求的方法信息
     */
	public MethodInfo methodInfo;
    public QueryInfo queryInfo;

    protected HttpVersion version;

    private int contentLength = -2;

    /**
     * HTTP请求的路径信息
     */
    private String path;
    /**
     * HTTP请求的主机名称
     */
    private String host;

    private ByteData body;

    private int headerSize;

    /**
     * HTTP请求的header列表
     */
    private Map<String, HeaderValue> headers = new HashMap<>();
    /**
     * 整个header数据区的数据
     */
    private byte[] headerData;

    /**
     * HTTP请求的参数
     */
    private Map<String, List<String>> parameter = new HashMap<>();

    @Override
    public String getMethod() {
        return methodInfo.getMethod();
    }

    @Override
    public MethodInfo getMethodInfo() {
        return methodInfo;
    }

    /**
     * 该请求对应的HttpNioSession的对象
     */
    public HttpNioSession getHttpNioSession() {
        return httpNioSession;
    }

    public void setHttpNioSession(HttpNioSession httpNioSession) {
        this.httpNioSession = httpNioSession;
    }

    @Override
    public HttpVersion getVersion() {
        return version;
    }

    public void putHeader(String name, HeaderValue value) {
        headers.put(name, value);
    }

    @Override
    public PathInfo getPath() {
        return pathInfo;
    }

    @Override
    public HeaderValue getHeaderValue(String name) {
        return headers.get(name);
    }

    public String getHeader(String name) {
        HeaderValue hv =getHeaderValue(name);
        if (hv == null) {
            return null;
        }
        return hv.getValue();
    }

    public void setVersion(HttpVersion version) {
        this.version = version;
    }

    @Override
    public int getContentLength() {
        if (contentLength == -2) {
            HeaderValue lengthVal = getHeaderValue(ContentLength.NAME);
            if (lengthVal != null && lengthVal.getData().length > 0) {
                contentLength = lengthVal.getIntValue();
            } else {
                contentLength = -1;
            }
        }
        return contentLength;
    }

    public ByteData getBody() {
        return body;
    }

    @Override
    public void setBody(ByteData body) {
        this.body = body;
    }

    @Override
    public void reset() {
        headers.clear();
    }

    /**
     * 整个header数据区的数据
     */
    public byte[] getHeaderData() {
        return headerData;
    }

    public void setHeaderData(byte[] headerData) {
        this.headerData = headerData;
    }
}
