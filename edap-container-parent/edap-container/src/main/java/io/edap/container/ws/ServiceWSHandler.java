package io.edap.container.ws;

import io.edap.http.WSConnection;
import io.edap.http.WSHandler;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.StringUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ServiceWSHandler implements WSHandler {

    static Logger log = LoggerManager.getLogger(ServiceWSHandler.class);

    private volatile Map<String, WSServiceMsgHandler<String>> httpMapping = new HashMap<>();


    public ServiceWSHandler() {

    }

    @Override
    public void onOpen(WSConnection webSocket) {
        webSocket.clearSessionContext();
        String remoteAddr;
        try {
            remoteAddr = webSocket.getHttpRequest().getClientAddr();
        } catch (Exception e) {
            remoteAddr = "unknown";
        }
        String finalRemoteAddr = remoteAddr;
        log.info("client {} connected.", l -> l.arg(finalRemoteAddr));
        String token = null;
        try {
            token = webSocket.getHttpRequest().getHeaderValue("Authorization").getValue();
        } catch (Exception e) {
            log.warn("Get token from header error", l -> l.threw(e));
        }
        if (StringUtil.isEmpty(token)) {
            token = webSocket.getHttpRequest().getParameter("token");
        }

    }

    @Override
    public void onMessage(WSConnection webSocket, String message) {

    }

    public void setMapping(Map<String, WSServiceMsgHandler<String>> newMap) {
        Map<String, WSServiceMsgHandler<String>> resolved = newMap == null ? Collections.emptyMap() : newMap;
        this.httpMapping = resolved;
    }

}
