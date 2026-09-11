package io.edap.http.server;

import io.edap.http.WSHandler;

import java.util.Map;

public interface UrlMapping {

    Map<String, UrlMappingItem> urlMappings();

    Map<String, WSHandler> websocketMapping();
}
