package io.edap.container.test.handler;

import io.edap.container.AppContext;
import io.edap.container.BeanWrap;
import io.edap.container.app.asm.AbstractHandler;
import io.edap.container.test.DemoService;
import io.edap.container.test.HelloReq;
import io.edap.http.HttpHandler;
import io.edap.http.HttpRequest;
import io.edap.http.HttpResponse;
import io.edap.http.header.ContentTypeHeader;
import io.edap.json.Eson;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class HttpEmptyParamHandler extends AbstractHandler implements HttpHandler {

    private static DemoService bean;
    private static String serviceName = DemoService.class.getName();
    private static String NOT_IMPL = serviceName + NOT_IMPL_MSG;
    private static String BIZ_EXC = serviceName + BIZ_EXCEPTION_MSG;

    public HttpEmptyParamHandler(AppContext appContext) {
        super(appContext);
        Object obj = getBean(DemoService.class);
        if (obj == null) {
            bean = null;
        } else {
            bean = (DemoService) obj;
        }
    }

    @Override
    public void handle(HttpRequest req, HttpResponse resp) throws IOException {
        resp.contentType(ContentTypeHeader.JSON);
        if (bean == null) {
            Map<String, Object> respData = new HashMap<>();
            respData.put("code", 100);
            respData.put("msg", NOT_IMPL);
            resp.write(Eson.toJsonString(respData));
        } else {
            try {
                HelloReq helloReq = new HelloReq();
                resp.write(Eson.toJsonString(bean.hello(helloReq)));
            } catch (Throwable e) {
                log.warn("", e);
                Map<String, Object> respData = new HashMap<>();
                respData.put("code", 101);
                respData.put("msg", BIZ_EXC);
                resp.write(Eson.toJsonString(respData));
            }
        }
    }
}
