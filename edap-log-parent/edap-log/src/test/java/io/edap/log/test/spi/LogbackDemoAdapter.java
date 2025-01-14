package io.edap.log.test.spi;

import io.edap.log.ConfigAlterationListener;
import io.edap.log.LogAdapter;
import io.edap.log.LogConfig;
import io.edap.log.LogWriter;

public class LogbackDemoAdapter implements LogAdapter {
    @Override
    public LogConfig loadConfig() {
        return null;
    }

    @Override
    public boolean registerListener(ConfigAlterationListener listener) {
        return false;
    }


    @Override
    public LogWriter getLogDataWriter(String appenderName) {
        return null;
    }

}
