package io.edap.container.manager;

import io.edap.container.manager.telnet.cmdhandlers.ClientTotalHandler;
import io.edap.protocol.telnet.TelnetServer;

import java.util.ArrayList;
import java.util.List;

public class TelnetManagerBuilder {

    List<String> addrs = new ArrayList<>();

    public TelnetManagerBuilder listen(int... ports) {
        if (ports.length > 0) {
            for (int i=0;i<ports.length;i++) {
                listen("127.0.0.1", ports[i]);
            }
        }
        return this;
    }

    public TelnetManagerBuilder listen(String address, int port) {
        String addr = address + ":" + port;
        if (!addrs.contains(addr) && !addrs.contains(":" + port)) {
            addrs.add(addr);
        }
        return this;
    }

    public TelnetServer build() {
        TelnetServer server = new TelnetServer();
        server.name("edapMgrTelnet");
        server.registerShellCmdHandler("ct", new ClientTotalHandler());
        int index;
        for (String addr : addrs) {
            index = addr.indexOf(":");
            int port = Integer.parseInt(addr.substring(index+1));
            if (index > 0) {
                server.listen(addr.substring(0, index), port);
            } else {
                server.listen(port);
            }
        }

        return server;
    }
}
