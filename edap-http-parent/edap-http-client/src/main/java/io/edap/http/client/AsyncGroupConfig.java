package io.edap.http.client;

import io.edap.util.CollectionUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 异步线程组的配置
 */
public class AsyncGroupConfig {

    private Map<String, Integer> hostQpsLimits = new HashMap<>();
    private Map<String, Integer> hostConCountLimits = new HashMap<>();

    /**
     * 超时时间默认30秒
     */
    private long timeout = 30000;

    /**
     * 设置HTTP主机的限流策略，当处理情况的速度达到限速后则停止向该主机发送http情趣
     * @param host http的主机，可以设置带有http，https，ws，wss的协议头的字符串也可以设置不带协议头的字符串
     * @param count 每秒发送的最大次数，当请求时间短时会根据滑动窗口的方式来计算请求量
     */
    public void setHostQpsLimit(String host, int count) {
        hostQpsLimits.put(parseHost(host), count);
    }

    /**
     * 设置HTTP主机的限流策略，当处理情况的速度达到限速后则停止向该主机发送http情趣
     * @param limits 多个主机的QPS限制列表，key为主机，value是限制QPS的值
     */
    public void setHostQpsLimits(Map<String, Integer> limits) {
        if (CollectionUtils.isEmpty(limits)) {
            return;
        }
        for (Map.Entry<String, Integer> entry : limits.entrySet()) {
            hostQpsLimits.put(parseHost(entry.getKey()), entry.getValue());
        }
    }

    /**
     * 获取主机和QPS的对应关系
     * @return 键为host，值为QPS的最大值每秒
     */
    public Map<String, Integer> getHostQpsLimits() {
        return hostQpsLimits;
    }

    private String parseHost(String host) {
        int schemaIndex = host.indexOf("://");
        if (schemaIndex != -1) {
            schemaIndex += 3;
        } else {
            schemaIndex = 0;
        }
        int pathIndex = host.indexOf("/", schemaIndex);
        if (pathIndex != -1) {
            host = host.substring(schemaIndex, pathIndex);
        } else {
            if (schemaIndex > 0) {
                host = host.substring(schemaIndex);
            }
        }
        return host;
    }

    /**
     * 设置主机最大连接数
     * @param host http的主机，可以设置带有http，https，ws，wss的协议头的字符串也可以设置不带协议头的字符串
     * @param count 允许最大连接数
     */
    public void setHostConCountLimit(String host, int count) {
        hostConCountLimits.put(parseHost(host), count);
    }

    /**
     * 获取异步组Host和最大连接数的对应关系
     * @return
     */
    public Map<String, Integer> getHostConCountLimits() {
        return hostConCountLimits;
    }

    /**
     * 设置多个主机最大连接数
     * @param limits
     */
    public void setHostConCountLimits(Map<String, Integer> limits) {
        if (CollectionUtils.isEmpty(limits)) {
            return;
        }
        for (Map.Entry<String, Integer> entry : limits.entrySet()) {
            hostConCountLimits.put(parseHost(entry.getKey()), entry.getValue());
        }
    }

    /**
     * 设置改请求的最大超时时间
     * @param maxTimeout
     */
    public void setTimeout(long maxTimeout) {
        this.timeout = maxTimeout;
    }

    /**
     * 获取当前异步组的最大超时时间，单位为毫秒
     * @return
     */
    public long getTimeout() {
        return timeout;
    }
}
