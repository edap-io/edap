package io.edap.log.test.config;

import io.edap.log.LogConfig;
import io.edap.log.config.ConfigManager;
import io.edap.log.config.LoggerConfig;
import io.edap.log.config.LoggerConfigSection;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringBufferInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class TestConfig {

    @Test
    public void testParseXmlConfig() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = ConfigManager.class.getDeclaredMethod("parseXmlConfig", new Class[]{InputStream.class});
        method.setAccessible(true);
        InputStream inputStream = TestConfig.class.getResourceAsStream("/edap-log.xml");
        ConfigManager manager = new ConfigManager();
        method.invoke(manager, inputStream);

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<configuration></configuration>";
        inputStream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        LogConfig config = (LogConfig) method.invoke(manager, inputStream);

        assertNull(config);

        xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<configuration>" +
                "   <appender name=\"console\"\n" +
                "              class=\"ch.qos.logback.core.ConsoleAppender\">\n" +
                "        <encoder>\n" +
                "            <pattern>${COMMON_LOG_PATTERN}</pattern>\n" +
                "        </encoder>\n" +
                "    </appender>\n" +
                "</configuration>";
        inputStream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        config = (LogConfig) method.invoke(manager, inputStream);
        assertNotNull(config);
        LoggerConfigSection loggerSection = config.getLoggerSection();
        assertNotNull(loggerSection);
        LoggerConfig rootLoggerConfig = loggerSection.getRootLoggerConfig();
        assertNotNull(rootLoggerConfig);
        assertEquals(rootLoggerConfig.getName(), "ROOT");
        assertEquals(rootLoggerConfig.getLevel(), "INFO");
        assertEquals(rootLoggerConfig.getAppenderRefs().size(), 1);
        assertEquals(rootLoggerConfig.getAppenderRefs().get(0), "console");
    }
}
