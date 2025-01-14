package io.edap.http.client;

import java.util.HashMap;

public class HttpClient {

    HashMap<AsyncGroupConfig, AsyncGroup> asyncGroups = new HashMap<>();

    /**
     * 根据异步httpclient组配置获取异步httpclient的组，用来处理异步的http的请求
     * @param config
     * @return
     */
    public synchronized AsyncGroup buildAsyncGroup(AsyncGroupConfig config) {
        AsyncGroup asyncGroup = new AsyncGroup();

        asyncGroups.put(config, asyncGroup);
        return asyncGroup;
    }
}
