package io.edap.container.test.handler;

import io.edap.container.AppContext;
import io.edap.container.app.asm.AbstractHandler;
import io.edap.container.ws.WSServiceMsgHandler;

public class WsDemoHandler<String> extends AbstractHandler implements WSServiceMsgHandler<String> {

    public WsDemoHandler(AppContext appContext) {
        super(appContext);
    }

    @Override
    public String handle(String msg) {
        return null;
    }
}
