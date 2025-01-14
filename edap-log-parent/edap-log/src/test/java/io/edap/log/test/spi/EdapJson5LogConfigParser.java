package io.edap.log.test.spi;

import io.edap.log.Json5ConfigParser;
import io.edap.log.LogConfig;

import java.io.InputStream;

public class EdapJson5LogConfigParser implements Json5ConfigParser {
    @Override
    public LogConfig parseConfig(InputStream inputStream) {
        return null;
    }
}
