package io.edap.log.test.spi;

import io.edap.log.*;
import io.edap.log.config.AppenderConfig;
import io.edap.log.config.AppenderConfigSection;
import io.edap.log.config.ConfigManager;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.edap.log.config.ConfigManager.createDefaultLogConfig;
import static io.edap.log.config.ConfigManager.parseXmlConfig;
import static io.edap.log.helpers.Util.printError;

public class EdapTestAdapter implements LogAdapter {

    private String configFile;

    private ConfigAlterationListener listener;

    private Map<String, Appender> appenderMap;

    private LogConfig logConfig;

    public EdapTestAdapter() {
        this("/edap-log.xml");
    }

    public EdapTestAdapter(String configFile) {
        appenderMap = new HashMap<>();
        try {
            InputStream configInStream = null;
            try {
                configInStream = ConfigManager.class.getResourceAsStream(configFile);
            } catch (Throwable t) {
                throw t;
            }
            long now = System.currentTimeMillis();
            LogConfig config = parseXmlConfig(configInStream, now);
            if (config != null) {
                AppenderConfigSection section = config.getAppenderSection();
                List<AppenderConfig> appenderConfigList = section.getAppenderConfigs();
                for (AppenderConfig appenderConfig : appenderConfigList) {
                    Appender appender = AppenderManager.instance().createAppender(appenderConfig);
                    if (appender == null) {
                        continue;
                    }
                    appenderMap.put(appenderConfig.getName(), appender);
                }
                this.logConfig = config;
            }
        } catch (Throwable t) {
            printError("parse config error", t);
        }
        if (logConfig == null) {
            logConfig = createDefaultLogConfig();
        }
    }

    @Override
    public LogConfig loadConfig() {
        return logConfig;
    }

    public void reloadConfig(String configFile) throws ParserConfigurationException, IOException, SAXException {
        InputStream configInStream = null;
        try {
            configInStream = ConfigManager.class.getResourceAsStream(configFile);
        } catch (Throwable t) {
            throw t;
        }
        long now = System.currentTimeMillis();
        LogConfig config = parseXmlConfig(configInStream, now);
        if (config != null && listener != null) {
            listener.listen(config);
        }
    }

    @Override
    public boolean registerListener(ConfigAlterationListener listener) {
        this.listener = listener;
        return true;
    }

    @Override
    public LogWriter getLogDataWriter(String appenderName) {
        Appender appender = appenderMap.get(appenderName);
        return appender.getLogoutStream();
    }
}
