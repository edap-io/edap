package io.edap.launcher.nested;

import io.edap.launcher.NestedUrlConnection;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * nested: 协议的 URLStreamHandler。
 *
 * 通过 java.protocol.handler.pkgs 系统属性被 JDK 自动发现:
 *   - 系统属性值 = "io.edap.launcher"
 *   - JDK 找 "io.edap.launcher" + "." + "nested" + ".Handler" = io.edap.launcher.nested.Handler
 *   - 找到后用反射 newInstance() 创建
 *
 * 不需要 URL.setURLStreamHandlerFactory,即使别的 jar 抢先注册了也不冲突。
 */
public class Handler extends URLStreamHandler {

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        // ★ 剥掉 #runtime 片段(URLClassPath 在 MR-JAR 探测时会加)
        String s = url.toString();
        int hash = s.indexOf('#');
        URL clean = (hash >= 0) ? new URL(s.substring(0, hash)) : url;
        return new NestedUrlConnection(clean);
    }
}
