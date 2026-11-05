package io.edap.http;

import io.edap.http.header.ContentLength;
import io.edap.http.model.QueryInfo;
import io.edap.util.ByteData;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.io.InputStream;
import java.util.*;

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

    private HttpBody body;

    private int headerSize;

    /**
     * HTTP请求的header列表
     */
    private Map<String, HeaderValue> headers = new HashMap<>();

    /**
     * header 的大小写不敏感查找表。
     *
     * <p>HTTP/2 强制 header 名全小写（RFC 9113 §8.2.1），而 HTTP/1.1 的调用方
     * 用的是 {@code "Content-Type"}、{@code "X-Forwarded-For"} 这类混合大小写。
     * 没有这张表，h2 下的 {@code getHeaderValue("Content-Type")} 永远取不到值。</p>
     */
    private final Map<String, HeaderValue> headersCI = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /**
     * 整个header数据区的数据
     */
    private ByteData headerData;

    private HttpResponse response;

    private boolean headerKeyLowerCase = false;

    /**
     * HTTP请求的参数
     */
    private Map<String, List<ParameterValue>> parameters = new HashMap<>();

    protected InputStream inputStream;
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

    public void setResponse(HttpResponse response) {
        this.response = response;
    }

    @Override
    public HttpResponse getResponse() {
        return response;
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
        headersCI.put(name, value);
    }

    @Override
    public Map<String, String> getHeaderMap() {
        Map<String, String> hs = new HashMap<>();
        for (Map.Entry<String, HeaderValue> entry : headers.entrySet()) {
            hs.put(entry.getKey(), entry.getValue().getValue());
        }
        return hs;
    }

    @Override
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public String getClientAddr() {
        HeaderValue hv = getHeaderValue("X-Forwarded-For");
        String addr = "";
        if (hv != null) {
            addr = hv.getValue();
            if (!StringUtil.isEmpty(addr)) {
                int index = addr.lastIndexOf(",");
                if (index != -1) {
                    addr = addr.substring(index + 1);
                }
            }
        }
        if (StringUtil.isEmpty(addr)) {
            hv = getHeaderValue("X-Real-IP");
            if (hv != null) {
                addr = hv.getValue();
            }
        }
        if (StringUtil.isEmpty(addr)) {
            try {
                addr = httpNioSession.getSocketChannel().getRemoteAddress().toString();
            } catch (Throwable t) {
                addr = "";
            }
        }
        hv = getHeaderValue("X-Forwarded-Port");
        if (hv != null && !StringUtil.isEmpty(hv.getValue())) {
            addr += ":" + hv.getValue();
        }
        return addr;
    }

    @Override
    public PathInfo getPathInfo() {
        return pathInfo;
    }

    @Override
    public HeaderValue getHeaderValue(String name) {
        HeaderValue v = headersCI.get(name);
        return v != null ? v : headers.get(name);
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
            HeaderValue lengthVal = getHeaderValue(ContentLength.NAME_LOWER_CASE);
            if (lengthVal != null && lengthVal.getData().length > 0) {
                contentLength = lengthVal.getIntValue();
            } else {
                lengthVal = getHeaderValue(ContentLength.NAME);
                if (lengthVal != null && lengthVal.getData().length > 0) {
                    contentLength = lengthVal.getIntValue();
                } else {
                    contentLength = -1;
                }
            }
        }
        return contentLength;
    }

    public HttpBody getBody() {
        return body;
    }

    @Override
    public void setBody(HttpBody body) {
        this.body = body;
    }

    @Override
    public void reset() {
        contentLength = -2;
        headers.clear();
        headersCI.clear();
        parameters.clear();
        // HttpResponse 在 HTTP/1.1 路径下是线程级单例（见 RangeHttpRequestDecoder 的 ThreadLocal），
        // 不重置的话上一个请求设置的状态码/响应头会漏进下一个响应。
        if (response != null) {
            response.reset();
        }
    }

    /**
     * 主机名。HTTP/1.1 来自 {@code Host} 头，HTTP/2 来自 {@code :authority} 伪头。
     */
    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    /**
     * 所有请求头（只读视图）。
     *
     * <p>h2c 升级时要把 HTTP/1.1 请求的头搬到 HTTP/2 流上（RFC 7540 §3.2），
     * 所以需要一个能枚举全部头的入口。</p>
     */
    public Map<String, HeaderValue> getHeaders() {
        return headers;
    }

    /**
     * 整个header数据区的数据
     */
    public ByteData getHeaderData() {
		if (headerData == null) {
			headerData = new ByteData(4096);
		}
        return headerData;
    }

    public void setHeaderData(ByteData headerData) {
        this.headerData = headerData;
    }

    @Override
    public String getParameter(String name) {
        if (!CollectionUtils.isEmpty(parameters)) {
            List<ParameterValue> vs = parameters.get(name);
            if (!CollectionUtils.isEmpty(vs)) {
                return vs.get(0).getValue();
            }
        }
        return null;
    }

    /**
     * HTTP请求的参数
     */
    public Map<String, List<ParameterValue>> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, List<ParameterValue>> parameters) {
        this.parameters = parameters;
    }
}
