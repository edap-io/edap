package io.edap.mqtt.broker;

import java.util.ArrayList;
import java.util.List;

public class MqttBrokerBuilder {

    List<String> addrs = new ArrayList<>();

    public MqttBrokerBuilder listen(int... ports) {
        if (ports == null) {
            throw new RuntimeException("listen must not null");
        }
        for (int i=0;i<ports.length;i++) {
            listen("", ports[i]);
        }
        return this;
    }

    public MqttBrokerBuilder listen(String address, int port) {
        String addr = address + ":" + port;
        if (!addrs.contains(addr) && !addrs.contains(":" + port)) {
            addrs.add(addr);
        }
        return this;
    }

    public MqttBroker build() {
        MqttBroker broker = new MqttBroker();
        int index;
        for (String addr : addrs) {
            index = addr.indexOf(":");
            int port = Integer.parseInt(addr.substring(index+1));
            if (index > 0) {
                broker.listen(addr.substring(0, index), port);
            } else {
                broker.listen(port);
            }
        }
        return broker;
    }
}
