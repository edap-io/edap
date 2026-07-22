package io.edap.container.test.httpadapter.handler;

import io.edap.container.mw.BaseResult;
import io.edap.container.mw.DeployManager;
import io.edap.container.mw.MicroServiceInfo;
import io.edap.http.HttpHandler;
import io.edap.http.HttpRequest;
import io.edap.http.HttpResponse;
import io.edap.http.header.ContentTypeHeader;
import io.edap.json.Eson;

import java.io.IOException;
import java.util.List;

import static io.edap.container.utils.ValueTypeConvertor.convertToInt;

public class ParameterDemoHandler implements HttpHandler {

    private DeployManager bean;

    public ParameterDemoHandler(DeployManager bean) {
        this.bean = bean;
    }

    @Override
    public void handle(HttpRequest req, HttpResponse resp) throws IOException {
        resp.contentType(ContentTypeHeader.JSON);
        String name = req.getParameter("name");
        String version = req.getParameter("version");
        BaseResult<String> list = bean.deployMicroService(name, version);
        resp.write(Eson.toJsonString(list));
    }
}
