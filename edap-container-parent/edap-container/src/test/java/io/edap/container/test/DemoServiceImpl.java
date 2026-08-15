package io.edap.container.test;

public class DemoServiceImpl implements DemoService {
    @Override
    public HelloResp hello(HelloReq emp) {
        return new HelloResp();
    }
}
