package io.edap.http.server.client;

import io.edap.http.server.client.method.Get;
import io.edap.http.server.client.method.Post;
import okhttp3.*;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class HttpClient {

    HashMap<AsyncGroupConfig, AsyncGroup> asyncGroups = new HashMap<>();

    OkHttpClient client = new OkHttpClient();

    static MediaType JSON = MediaType.parse("application/json;charset=utf-8");

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

    public HttpResp post(Post method) throws IOException {
        try {

            Request.Builder postBuilder = new Request.Builder().url(method.getUrl());

            for (Map.Entry<String, String> entry : method.getHeaders().entrySet()) {
                postBuilder.addHeader(entry.getKey(), entry.getValue());
            }
            if (method.getBody() != null) {
                postBuilder.post(RequestBody.create(method.getBody().bytes(), JSON));
            }
            Response resp = client.newCall(postBuilder.build()).execute();
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
