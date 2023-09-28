package io.edap.log;


import io.edap.log.config.AppenderConfigSection;
import io.edap.log.config.ConfigManager;
import io.edap.log.config.LoggerConfigSection;
import io.edap.log.spi.EdapLogFactory;

public class EdapLogContext {

    private AppenderManager appenderManager = AppenderManager.instance();

    private EdapLogFactory edapLogFactory;

    private EdapLogContext() {}

    public void setEdapLogFactory(EdapLogFactory edapLogFactory) {
        this.edapLogFactory = edapLogFactory;
    }

    public EdapLogFactory getEdapLogFactory() {
        return this.edapLogFactory;
    }


    public void reloadConfig(LogConfig logConfig) {
        appenderManager.reloadConfig(logConfig.getAppenderSection());
        LoggerConfigSection loggerConfigSection = logConfig.getLoggerSection();
        loggerConfigSection.setNeedReload(true);
        edapLogFactory.reload(loggerConfigSection);
    }

    public void reload() {
        ConfigManager configManager = new ConfigManager();
        LogConfig logConfig = configManager.loadConfig();
        if (logConfig != null && logConfig.getAppenderSection() != null) {
            AppenderConfigSection appenderConfigSection = logConfig.getAppenderSection();
            appenderConfigSection.setNeedReload(true);
            AppenderManager.instance().reloadConfig(appenderConfigSection);
        }
        if (logConfig != null && logConfig.getLoggerSection() != null) {
            if (edapLogFactory == null) {
                edapLogFactory = new EdapLogFactory();
            }
            LoggerConfigSection loggerConfigSection = logConfig.getLoggerSection();
            loggerConfigSection.setNeedReload(true);
            edapLogFactory.reload(loggerConfigSection);
        }
    }

    public static final EdapLogContext instance() {
        return EdapLogContext.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final EdapLogContext INSTANCE = new EdapLogContext();
    }
}
