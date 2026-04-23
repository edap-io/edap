package io.edap.http.server.handler;

import io.edap.http.HttpHandler;
import io.edap.http.HttpRequest;
import io.edap.http.HttpResponse;
import io.edap.http.header.ContentTypeHeader;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

public class FaviconHandler implements HttpHandler {

    Logger log = LoggerManager.getLogger(FaviconHandler.class);

    private static byte[] FAVICON_ICO_DATA;
    private static byte[] FAVICON_SVG_DATA;
    private static byte[] FAVICON_PNG_DATA;
    @Override
    public void handle(HttpRequest req, HttpResponse resp) {
        String path = req.getPath();
        if ("/favicon.ico".equals(path)) {
            if (FAVICON_ICO_DATA == null) {
                FAVICON_ICO_DATA = loadData("/favicon.ico",
                        new byte[]{
                                0, 0, 1, 0, 1, 0, 32, 32, 0, 0, 1, 0, 32, 0, 83, 0, 0, 0, 22, 0,
                                0, 0, -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0,
                                0, 32, 0, 0, 0, 32, 8, 6, 0, 0, 0, 115, 122, 122, -12, 0, 0, 0, 26, 73,
                                68, 65, 84, 120, -100, -19, -63, 1, 1, 0, 0, 0, -126, 32, -1, -81, 110, 72, 64, 1,
                                0, 0, 0, -17, 6, 16, 32, 0, 1, 25, 67, 52, -18, 0, 0, 0, 0, 73, 69, 78,
                                68, -82, 66, 96, -126
                        });
            }
            resp.contentType(ContentTypeHeader.from("image/x-icon"));
            resp.write(FAVICON_ICO_DATA);
        } else if ("/icon.svg".equals(path)) {
            if (FAVICON_SVG_DATA == null) {
                FAVICON_SVG_DATA = loadData("/icon.svg",new byte[] {
                        60 ,63 ,120,109,108,32 ,118,101,114,115,105,111,110,61 ,34 ,49 ,46 ,48 ,34 ,32 ,
                        101,110,99 ,111,100,105,110,103,61 ,34 ,85 ,84 ,70 ,45 ,56 ,34 ,63 ,62 ,10 ,60 ,
                        115,118,103,32 ,120,109,108,110,115,61 ,34 ,104,116,116,112,58 ,47 ,47 ,119,119,
                        119,46 ,119,51 ,46 ,111,114,103,47 ,50 ,48 ,48 ,48 ,47 ,115,118,103,34 ,32 ,119,
                        105,100,116,104,61 ,34 ,49 ,34 ,32 ,104,101,105,103,104,116,61 ,34 ,49 ,34 ,62 ,
                        60 ,47 ,115,118,103,62 ,10
                });
            }
            resp.contentType(ContentTypeHeader.from("image/svg+xml"));
            resp.write(FAVICON_SVG_DATA);
        } else if ("/apple-touch-icon.png".equals(path)) {
            if (FAVICON_PNG_DATA == null) {
                FAVICON_PNG_DATA = loadData("/apple-touch-icon.png",
                        new byte[]{
                                -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1,
                                0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, -60, -119, 0, 0, 0, 13, 73, 68, 65,
                                84, 120, -100, 99, 96, 96, 96, 96, 0, 0, 0, 5, 0, 1, -91, -10, 69, 64, 0, 0,
                                0, 0, 73, 69, 78, 68, -82, 66, 96, -126
                        });
            }
            resp.contentType(ContentTypeHeader.from("image/png"));
            resp.write(FAVICON_PNG_DATA);
        }
    }

    private byte[] loadData(String name, byte[] defaultData) {
        byte[] data;
        try {
            data = FaviconHandler.class
                    .getResourceAsStream(name).readAllBytes();
            return data;
        } catch (Throwable e) {
            log.warn("未找到favicon.ico文件");
            return defaultData;
        }
    }
}
