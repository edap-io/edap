package io.edap.launcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/**
 * nested: 协议的 URLConnection。
 *
 * URL 格式:nested:<outerJarPath>!/<entry1>!/<entry2>!/.../<entryN>
 * 例子:nested:/path/to/outer.jar!/BOOT-INF/lib/foo.jar!/META-INF/services/xxx
 */
public class NestedUrlConnection extends URLConnection {

    private final String outerJarPath;
    private final String[] entrySegments;
    private byte[] bytes;

    public NestedUrlConnection(URL url) {
        super(url);
        String s = url.toString();
        if (s.startsWith("nested:")) {
            s = s.substring("nested:".length());
        }
        int firstBang = s.indexOf("!/");
        if (firstBang < 0) {
            this.outerJarPath = s;
            this.entrySegments = new String[0];
        } else {
            this.outerJarPath = s.substring(0, firstBang);
            this.entrySegments = s.substring(firstBang + 2).split("!/");
        }
    }

    @Override
    public void connect() throws IOException {
        if (bytes != null) return;
        if (entrySegments.length == 0) {
            throw new IOException("Empty entry path: " + url);
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(outerJarPath, "r")) {
            NestedJarFile cur = new NestedJarFile(raf, 0L, raf.length(), outerJarPath);
            for (int i = 0; i < entrySegments.length - 1; i++) {
                NestedJarFile next = cur.getNestedJarFile(entrySegments[i]);
                if (next == null) throw new IOException("Entry not found: " + entrySegments[i]);
                cur = next;
            }
            bytes = cur.readEntryBytes(entrySegments[entrySegments.length - 1]);
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        connect();
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public int getContentLength() { return bytes != null ? bytes.length : -1; }
    @Override
    public long getContentLengthLong() { return bytes != null ? bytes.length : -1L; }
}