package io.edap.log.test.config;

import io.edap.log.LogConfig;
import io.edap.log.config.*;
import io.edap.util.CollectionUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringBufferInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

        AppenderConfigSection appenderSection = config.getAppenderSection();
        assertNotNull(appenderSection);
        List<AppenderConfig> appenderConfigs = appenderSection.getAppenderConfigs();
        assertNotNull(appenderConfigs);
        assertEquals(appenderConfigs.size(), 1);
        AppenderConfig appenderConfig = appenderConfigs.get(0);

        assertEquals(appenderConfig.getName(), "console");
        assertEquals(appenderConfig.getClazzName(), "ch.qos.logback.core.ConsoleAppender");
        List<LogConfig.ArgNode> argNodes = appenderConfig.getArgs();
        assertEquals(argNodes.size(), 1);
        LogConfig.ArgNode argNode = argNodes.get(0);
        assertEquals(argNode.getName(), "encoder");
        assertEquals(CollectionUtils.isEmpty(argNode.getAttributes()), true);
        assertEquals(argNode.getChilds().size(), 1);
        LogConfig.ArgNode argNodeChild = argNode.getChilds().get(0);
        assertEquals(argNodeChild.getName(), "pattern");
        assertEquals(argNodeChild.getValue(), "${COMMON_LOG_PATTERN}");
    }
}
