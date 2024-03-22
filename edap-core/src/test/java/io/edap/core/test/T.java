/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.core.test;

import okhttp3.*;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class T {

    public static void main(String[] args) throws Throwable {

        String url = "https://yts-demo.yyuap.com/iuap-ymsc-yts/bill/save?cmdname=cmdSave&terminalType=1&locale=zh_CN&businessActName=mddsagas%E8%AE%A2%E5%8D%95%E5%88%97%E8%A1%A8-%E4%BF%9D%E5%AD%98";
        String json = "{\n" +
                "  \"billnum\": \"sagas_card\",\n" +
                "  \"data\": \"{\\\"inumber\\\":\\\"1\\\",\\\"fprice\\\":\\\"1\\\",\\\"sorderid\\\":\\\"7\\\",\\\"sproductid\\\":\\\"12\\\",\\\"sname\\\":\\\"面包\\\",\\\"sbuyer\\\":\\\"12\\\",\\\"_status\\\":\\\"Insert\\\"}\"\n" +
                "}";
        String cookie = "redirect_url=https%3A%2F%2Fbip-test.yyuap.com%2Flogin%3Fyhtdesturl%3D%2Fyhtssoislogin%26yhtrealservice%3Dhttps%3A%2F%2Fbip-test.yyuap.com%26tenantId%3D0000L6YTYEY5FUZPXE0000%26; Path=/; Max-Age=10; Expires=Mon, 09 Oct 2023 02:13:35 GMT; Secure; HttpOnly; SameSite=None. Invalid 'expires' attribute: Mon, 09 Oct 2023 02:13:35 GMT JSESSIONID=D420046F0B01E916C675D5069D9C3CDB; Path=/; secure; SameSite=None; HttpOnly;yht_username_diwork=ST-10372-5wDeq5ffYkqgRlAg5qCB-testC2__99ea7655-00a2-4bda-b23c-19ade37ea574; Path=/; secure; SameSite=None; HttpOnly; Secure; SameSite=none;yht_usertoken_diwork=APnHyC3LNRAZu34OfbokwyXoTQwyQw1zboGNpHd5YiW3BT6rONpXbW4U6p5mcHD9aRXr7LAD68EbCfZcPJqyBQ%3D%3D; Path=/; secure; SameSite=None; HttpOnly; Secure; SameSite=none;yht_access_token=bttbFNoSWNYNGRHR0VaY1dDYVFHYTVpcE9zSS96Z0VOME1yUkRHT3YxWVovclZKb0xzeXBEdUtvRGVmbENDVWljaF9fYmlwLXRlc3QueW9ueW91Y2xvdWQuY29t__efe01ac3165cf69d3c729add33bd2680_1696817606720dccore0iuap-apcom-workbench1257a4c4YT; Path=/; secure; SameSite=None; HttpOnly;multilingualFlag=true; Path=/; secure; SameSite=None; HttpOnly;timezone=UTC+08:00; Path=/; secure; SameSite=None; HttpOnly;language=001; Path=/; secure; SameSite=None; HttpOnly;locale=zh_CN; Path=/; secure; SameSite=None; HttpOnly;orgId=; Path=/; secure; SameSite=None; HttpOnly;defaultOrg=1530661876902920193; Path=/; secure; SameSite=None; HttpOnly;tenantid=0000L6YTYEY5FUZPXE0000; Path=/; secure; SameSite=None; HttpOnly;theme=; Path=/; secure; SameSite=None; HttpOnly;languages=1_3-2_1-3_1-98_1; Path=/; secure; SameSite=None; HttpOnly;newArch=true; Path=/; secure; SameSite=None; HttpOnly;sysid=diwork; Path=/; secure; SameSite=None; HttpOnly;a00=iJnLcDhGMTa-mNsGZh9xEuwXNhrAn2941yInF9xOxwswMDAwTDZZVFlFWTVGVVpQWEUwMDAwYDI5MTAwMzMxNDY3NjE4MDhgMDAwMEw2WVRZRVk1RlVaUFhFMDAwMGA5OWVhNzY1NS0wMGEyLTRiZGEtYjIzYy0xOWFkZTM3ZWE1NzRgMzBgYGU2YjU4YmU4YWY5NWU3YWVhMWU3OTA4NmU1OTE5ODMxMzEzMWBgYDE2NjQ0Njg0MzkxMTUyMzUzMzRgZmFsc2VgYDE2OTY4MTc2MDY3MzFgeW1zc2VzOjM4MWE4NDg5NDJkNzk2Yzc3Njk5N2Y4NDY2OTA1ZDg4YGRpd29ya2A; Path=/; secure; SameSite=None; HttpOnly;a10=MDEyNzQ2NTExMjY5OTM2MDY3MzE; Path=/; secure; SameSite=None; HttpOnly;n_f_f=true; Path=/; secure; SameSite=None; HttpOnly;c800=dccore0; Max-Age=2592000; Expires=Wed, 08 Nov 2023 02:13:26 GMT; Path=/; secure; SameSite=None; HttpOnly;jwt_token=cookie_expire; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:10 GMT; Path=/; secure; SameSite=None; HttpOnly";
        try {
            TrustManager[] trustManagers = buildTrustManagers();
            OkHttpClient okHttpClient = new OkHttpClient().newBuilder()
                    .protocols(Arrays.asList(Protocol.HTTP_1_1))
                    .connectionPool(new ConnectionPool(3, 5, TimeUnit.MINUTES))
                    .followRedirects(false).followSslRedirects(false).build();
            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json"), json);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Cookie", cookie)
                    //.addHeader("cookie", cookie)
                    .addHeader("Domain-Key", "iuap-ymsc-yts")
                    .addHeader("domain-key", "iuap-ymsc-yts")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("content-type", "application/json")
                    .addHeader("Host", "yts-demo.yyuap.com")
                    .addHeader("X-traceId", "479ca75ceb874c9t")
                    .addHeader("X-spanId", "479ca75ceb874c9t")
                    .post(body)
                    .build();

            Call call = okHttpClient.newCall(request);
            Response response = call.execute();
            System.out.println(response.code());
            System.out.println(response.headers().get("X-Ymc-Access-Data"));
            System.out.println(new String(response.body().bytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new Exception(e);
        }

    }

    private static TrustManager[] buildTrustManagers() {
        return new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[]{};
                    }
                }
        };
    }
}
