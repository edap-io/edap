package io.edap.http.server.client.test;

public class SslEngineTest {


    public static void main(String[] args) throws Exception {
        NioSslClient client = new NioSslClient();
        client.connect("www.edap.io", 443);

        client.write("GET / HTTP/1.1\r\nHost: www.eda.io\r\n\r\n");
        client.read();
        client.shutdown();
    }


}
