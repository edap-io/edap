package io.edap.nio.bench;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

/**
 * 半开连接验证的客户端：连上之后就一直活着（不发数据），等外层脚本 SIGKILL 它。
 */
public class HalfOpenClient {

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int n = Integer.parseInt(args[2]);

        SocketChannel[] chans = new SocketChannel[n];
        for (int i = 0; i < n; i++) {
            SocketChannel c = SocketChannel.open();
            c.connect(new InetSocketAddress(host, port));
            chans[i] = c;
        }
        System.out.printf("CONNECTED %d%n", n);
        System.out.flush();
        Thread.sleep(Long.parseLong(System.getProperty("bench.holdMs", "600000")));
    }
}
