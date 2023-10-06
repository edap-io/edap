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

    protected PathInfo pathInfo;
    /**
     * Http请求的方法信息
     */
    protected MethodInfo methodInfo;
    protected QueryInfo queryInfo;

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

    /**
     * 整个header数据区的数据
     */
    public byte[] getHeaderData() {
        return headerData;
    }

    public void setHeaderData(byte[] headerData) {
        this.headerData = headerData;
    }

    class HeaderItem {
        String name;
        HeaderValue value;
        public HeaderItem(String name, HeaderValue value) {
            this.name = name;
            this.value = value;
        }
    }

    /**
     * HTTP请求的header列表
     */
    private HeaderItem[] headers = new HeaderItem[16];
    /**
     * 整个header数据区的数据
     */
    private byte[] headerData;
    private int headerCount = 0;
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

    public void addHeader(String name, HeaderValue value) {
        if (headerCount >= headers.length - 1) {
            HeaderItem[] tmp = new HeaderItem[headers.length*2];
            System.arraycopy(headers, 0, tmp, 0, headerCount);
            headers = tmp;
        }
        headers[headerCount++] = new HeaderItem(name, value);
    }

    @Override
    public PathInfo getPath() {
        return pathInfo;
    }

    @Override
    public HeaderValue getHeaderValue(String name) {
        if (headerCount <= 0) {
            return null;
        }
        for (int i=0;i<headerCount;i++) {
            HeaderItem h = headers[i];
            if (h.name != null && h.name.equals(name)) {
                return h.value;
            }
        }
        return null;
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
        for (int i=0;i<headerCount;i++) {
            headers[i] = null;
        }
        headerCount = 0;
        if (!CollectionUtils.isEmpty(parameter)) {
            parameter.clear();
        }
    }
}
