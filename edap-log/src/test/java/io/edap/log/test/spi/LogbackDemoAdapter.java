package io.edap.log.test.spi;

import io.edap.log.LogAdapter;
import io.edap.log.LogConfig;

import java.io.OutputStream;

public class LogbackDemoAdapter implements LogAdapter {
    @Override
    public LogConfig loadConfig() {
        return null;
    }

    @Override
    public LogConfig reloadConfig() {
        return null;
    }

    @Override
    public OutputStream getOutputStream(String appenderName) {
        return null;
    }

}