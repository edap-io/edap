package io.edap.http.server.client;

import io.edap.http.server.client.method.Get;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;

public class HttpClient {

    HashMap<AsyncGroupConfig, AsyncGroup> asyncGroups = new HashMap<>();

    OkHttpClient client = new OkHttpClient();

    public HttpClient() {

    }

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

    public HttpResp get(Get method) throws IOException {
        Request get = new Request.Builder().url(method.getUrl()).build();
        try {
            Response resp = client.newCall(get).execute();
            return new HttpResp() {
                @Override
                public int code() {
                    return resp.code();
                }

                @Override
                public HttpBody body() {
                    return new HttpBody() {
                        @Override
                        public void writeTo(OutputStream out) {

                        }

                        @Override
                        public byte[] bytes() throws IOException {
                            return resp.body().bytes();
                        }
                    };
                }
            };
        } catch (IOException e) {
            throw e;
        }
    }

}
