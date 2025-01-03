package io.edap.log.test.config;

import io.edap.log.LogAdapter;
import io.edap.log.LogConfig;
import io.edap.log.config.*;
import io.edap.log.test.spi.LogbackDemoAdapter;
import io.edap.util.CollectionUtils;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static io.edap.log.consts.LogConsts.DEFAULT_CONSOLE_APPENDER_NAME;
import static io.edap.log.consts.LogConsts.DEFAULT_FILE_APPENDER_NAME;
import static org.junit.jupiter.api.Assertions.*;

public class TestConfig {

    @Test
    public void testParseXmlConfig() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = ConfigManager.class.getDeclaredMethod("parseXmlConfig", new Class[]{InputStream.class, long.class});
        method.setAccessible(true);
        InputStream inputStream = TestConfig.class.getResourceAsStream("/edap-log-demo.xml");
        ConfigManager manager = new ConfigManager();
        long now = System.currentTimeMillis();
        method.invoke(manager, inputStream, now);

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<configuration></configuration>";
        inputStream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        LogConfig config = (LogConfig) method.invoke(manager, inputStream, now);

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
        config = (LogConfig) method.invoke(manager, inputStream, now);
        assertNotNull(config);
        LoggerConfigSection loggerSection = config.getLoggerSection();
        assertNotNull(loggerSection);
        LoggerConfig rootLoggerConfig = loggerSection.getRootLoggerConfig();
        assertNotNull(rootLoggerConfig);
        assertEquals(rootLoggerConfig.getName(), "ROOT");
        assertEquals(rootLoggerConfig.getLevel(), "INFO");
        assertEquals(rootLoggerConfig.getAppenderRefs().size(), 2);
        assertEquals(rootLoggerConfig.getAppenderRefs().get(0), DEFAULT_CONSOLE_APPENDER_NAME);
        assertEquals(rootLoggerConfig.getAppenderRefs().get(1), DEFAULT_FILE_APPENDER_NAME);

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


        xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<configuration>" +
                "   <appender name=\"console\"\n" +
                "              class=\"ch.qos.logback.core.ConsoleAppender\">\n" +
                "        <encoder>\n" +
                "            <pattern>${COMMON_LOG_PATTERN}</pattern>\n" +
                "        </encoder>\n" +
                "    </appender>\n" +
                "   <appender\n" +
                "              class=\"ch.qos.logback.core.ConsoleAppender\">\n" +
                "        <encoder>\n" +
                "            <pattern>${COMMON_LOG_PATTERN}</pattern>\n" +
                "        </encoder>\n" +
                "    </appender>\n" +
                "   <appender name=\"console\"\n" +
                "              >\n" +
                "        <encoder>\n" +
                "            <pattern>${COMMON_LOG_PATTERN}</pattern>\n" +
                "        </encoder>\n" +
                "    </appender>\n" +
                "   <appender name=\"rollFile\"\n" +
                "              class=\"ch.qos.logback.core.ConsoleAppender\" />\n" +
                "</configuration>";
        inputStream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        config = (LogConfig) method.invoke(manager, inputStream, now);
        assertNotNull(config);
        appenderSection = config.getAppenderSection();
        assertNotNull(appenderSection);
        appenderConfigs = appenderSection.getAppenderConfigs();
        assertNotNull(appenderConfigs);
        assertEquals(appenderConfigs.size(), 2);

        xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<configuration>" +
                "   <appender name=\"console\"\n" +
                "              class=\"ch.qos.logback.core.ConsoleAppender\">\n" +
                "        <encoder>\n" +
                "            <pattern>${COMMON_LOG_PATTERN}</pattern>\n" +
                "        </encoder>\n" +
                "    </appender>\n" +
                "    <logger name=\"com.yonyou\" level=\"INFO\" additivity=\"false\">\n" +
                "        <appender-ref ref=\"console\" />\n" +
                "        <appender-ref ref=\"rollingFile\" />\n" +
                "        <!--\t\t<appender-ref ref=\"monitor\" />-->\n" +
                "    </logger>\n" +
                "    <logger name=\"com.yonyou.cloud.inotify\" level=\"WARN\" additivity=\"false\">\n" +
                "        <appender-ref ref=\"console\" />\n" +
                "        <!--\t\t<appender-ref ref=\"monitor\" />-->\n" +
                "    </logger>" +
                "    <logger name=\"com.yonyou.cloud.push\" level=\"WARN\" additivity=\"false\">\n" +
                "        <appender-ref />\n" +
                "        <!--\t\t<appender-ref ref=\"monitor\" />-->\n" +
                "    </logger>" +
                "    <logger name=\"com.yonyou.cloud.eos\" level=\"WARN\" additivity=\"false\">\n" +
                "        <!--\t\t<appender-ref ref=\"monitor\" />-->\n" +
                "    </logger>" +
                "    <logger name=\"com.yonyou.cloud.msg\" level=\"WARN\" additivity=\"false\" />\n" +
                "    <logger name=\"com.yonyou.cloud.yts\">\n" +
                "        <appender-ref ref=\"console\" />\n" +
                "        <appender-ref ref=\"rollingFile\" />\n" +
                "        <!--\t\t<appender-ref ref=\"monitor\" />-->\n" +
                "    </logger>" +
                "    <logger>\n" +
                "        <appender-ref ref=\"console\" />\n" +
                "        <appender-ref ref=\"rollingFile\" />\n" +
                "        <!--\t\t<appender-ref ref=\"monitor\" />-->\n" +
                "    </logger>" +
                "    <logger level=\"WARN\">\n" +
                "        <appender-ref ref=\"console\" />\n" +
                "        <appender-ref ref=\"rollingFile\" />\n" +
                "        <!--\t\t<appender-ref ref=\"monitor\" />-->\n" +
                "    </logger>" +
                "</configuration>";
        inputStream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        config = (LogConfig) method.invoke(manager, inputStream, now);
        assertNotNull(config);

        loggerSection = config.getLoggerSection();
        assertNotNull(loggerSection);
        List<LoggerConfig> loggerConfigs = loggerSection.getLoggerConfigs();
        assertNotNull(loggerConfigs);
        assertEquals(loggerConfigs.size(), 6);

        LoggerConfig loggerConfig = loggerConfigs.get(1);
        assertEquals(loggerConfig.getAdditivity(), "false");
    }

    @Test
    public void testParseProperty() throws Exception {
        Method method = ConfigManager.class.getDeclaredMethod("parseXmlConfig", new Class[]{InputStream.class, long.class});
        method.setAccessible(true);
        InputStream inputStream;
        inputStream = TestConfig.class.getResourceAsStream("/edap-property.xml");
        ConfigManager manager = new ConfigManager();
        long now = System.currentTimeMillis();
        LogConfig config = (LogConfig) method.invoke(manager, inputStream, now);
        Map<String, Property> propertyMap = config.getPropertySection().getPropertyMap();
        assertNotNull(propertyMap);
        assertEquals(propertyMap.size(), 10);

        assertEquals(propertyMap.get("testKey1").getValue(), null);
        assertEquals(propertyMap.get("testKey2").getValue(), "value2");
        assertEquals(propertyMap.get("testKey3").getValue(), "value3");
        assertEquals(propertyMap.get("testKey4").getValue(), "dparam1");
        assertEquals(propertyMap.get("testKey5").getValue(), "prefix-dparam1-postfix");
        assertEquals(propertyMap.get("testKey6").getValue(), "prefix-dparam1");
        assertEquals(propertyMap.get("testKey7").getValue(), "dparam1-postfix");
        assertEquals(propertyMap.get("testKey8").getValue(), "value2");
        assertEquals(propertyMap.get("testKey9").getValue(), " value2 ");
        assertEquals(propertyMap.get("testKey10").getValue(), "  ");

        propertyMap = withEnvironmentVariable("dparam1", "-Dvalue1")
                .execute(() -> {
                    InputStream inputStream2;
                    inputStream2 = TestConfig.class.getResourceAsStream("/edap-property.xml");
                    LogConfig cfg = (LogConfig) method.invoke(manager, inputStream2, now);
                    Map<String, Property> map = cfg.getPropertySection().getPropertyMap();
                    return map;
                });
        assertEquals(propertyMap.size(), 10);

        assertEquals(propertyMap.get("testKey1").getValue(), null);
        assertEquals(propertyMap.get("testKey2").getValue(), "value2");
        assertEquals(propertyMap.get("testKey3").getValue(), "value3");
        assertEquals(propertyMap.get("testKey4").getValue(), "-Dvalue1");
        assertEquals(propertyMap.get("testKey5").getValue(), "prefix--Dvalue1-postfix");
        assertEquals(propertyMap.get("testKey6").getValue(), "prefix--Dvalue1");
        assertEquals(propertyMap.get("testKey7").getValue(), "-Dvalue1-postfix");
        assertEquals(propertyMap.get("testKey8").getValue(), "value2");
        assertEquals(propertyMap.get("testKey9").getValue(), " value2 ");
    }

    @Test
    public void testLoadConfig() {
        ConfigManager configManager = new ConfigManager();
        configManager.loadConfig();
    }

    @Test
    public void testLogAdapter() {
        ConfigManager configManager = new ConfigManager();
        LogAdapter old = configManager.getLogAdapter();
        LogAdapter adapterSrc = new LogbackDemoAdapter();
        configManager.setLogAdapter(adapterSrc);
        LogAdapter adapter = configManager.getLogAdapter();
        assertNotNull(adapter);
        assertEquals(adapter, adapterSrc);
        configManager.setLogAdapter(old);
    }
}
