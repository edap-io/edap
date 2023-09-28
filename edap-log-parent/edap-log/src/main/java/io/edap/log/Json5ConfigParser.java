package io.edap.log;

import java.io.InputStream;

/**
 * json5格式配置文件解析SPI
 */
public interface Json5ConfigParser {

    /**
     * 将资源文件解析json5格式的内容解析成为edap-json的配置文件
     * @param inputStream
     * @return
     */
    LogConfig parseConfig(InputStream inputStream);
}
