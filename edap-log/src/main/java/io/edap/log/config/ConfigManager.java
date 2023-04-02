package io.edap.log.config;

import io.edap.log.*;
import io.edap.util.CollectionUtils;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static io.edap.log.helpers.Util.printError;
import static io.edap.util.StringUtil.isEmpty;
import static java.util.Collections.EMPTY_LIST;

public class ConfigManager {

    private static LoggerConfig DEFAULT_ROOT_LOGGER_CONFIG;

    static {
        LoggerConfig config = new LoggerConfig();
        config.setLevel("INFO");
        config.setName("ROOT");
        config.setAppenderRefs(Arrays.asList("console"));
        DEFAULT_ROOT_LOGGER_CONFIG = config;
    }

    private long lastUpdateTime;

    /**
     * edap-log到其他日志框架的适配器
     */
    private static LogAdapter logAdapter;

    public ConfigManager() {
        lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * 先在资源文件中查询edap-log.xml和edap-log.json5的文件如果找到并且解析成功则使用edap-log的配置文件。如果找不到则试图找实现
     * LogAdapter的SPI是否有提供其他日志框架适配器，有适配器则加载适配器的配置文件，否则生成一个默认的配置文件。
     */
    public LogConfig loadConfig() {
        LogConfig config = findEdapLogConfig();
        if (config == null) {
            config = findAdapterConfig();
        }
        if (config == null) {
            config = createDefaultLogConfig();
        }
        return config;
    }

    public static LogConfig createDefaultLogConfig() {
        LogConfig config = new LogConfig();

        return config;
    }

    private LogConfig findAdapterConfig() {
        try {
            ClassLoader managerClassLoader = ConfigManager.class.getClassLoader();
            ServiceLoader<LogAdapter> loader;
            loader = ServiceLoader.load(LogAdapter.class, managerClassLoader);
            Iterator<LogAdapter> iterator = loader.iterator();
            while (iterator.hasNext()) {
                LogAdapter provider = safelyInstantiate(iterator);
                if (provider != null) {
                    LogConfig config = provider.loadConfig();
                    if (config != null) {
                        logAdapter = provider;
                        provider.registerListener(new ConfigAlterationListener() {
                            @Override
                            public void listen(LogConfig logConfig) {
                                EdapLogContext.instance().reloadConfig(logConfig);
                            }
                        });
                        return config;
                    }
                }
            }
            printError("findAdapterConfig not found!");
        } catch (Throwable t) {
            printError("findAdapterConfig error!\n", t);
        }
        return null;
    }

    private Json5ConfigParser findJson5ConfigParser() {
        try {
            ClassLoader managerClassLoader = ConfigManager.class.getClassLoader();
            ServiceLoader<Json5ConfigParser> loader;
            loader = ServiceLoader.load(Json5ConfigParser.class, managerClassLoader);
            Iterator<Json5ConfigParser> iterator = loader.iterator();
            while (iterator.hasNext()) {
                Json5ConfigParser parser = safelyInstantiateParser(iterator);
                if (parser != null) {
                    return parser;
                }
            }
            printError("find Json5ConfigParser not found!");
        } catch (Throwable t) {
            printError("find Json5ConfigParser error!\n", t);
        }
        return null;
    }

    private static Json5ConfigParser safelyInstantiateParser(Iterator<Json5ConfigParser> iterator) {
        try {
            Json5ConfigParser parser = iterator.next();
            return parser;
        } catch (ServiceConfigurationError e) {
            printError("A edap-log json5ConfigParser eror:", e);
        }
        return null;
    }

    private static LogAdapter safelyInstantiate(Iterator<LogAdapter> iterator) {
        try {
            LogAdapter provider = iterator.next();
            return provider;
        } catch (ServiceConfigurationError e) {
            printError("A edap-log Adapter eror:", e);
        }
        return null;
    }

    /**
     * 在资源包中查找edap-log.xml和edap-log.json5的配置文件，如果没有找到则返回null，如果存在edap-log.xml，或者edap-log.json5的
     * 配置文件，则解析文件生成edap-log的配置实例。
     * @return
     */
    private LogConfig findEdapLogConfig() {
        InputStream configInStream = null;
        LogConfig logConfig = null;
        try {
            configInStream = ConfigManager.class.getResourceAsStream("/edap-log.xml");
            logConfig = parseXmlConfig(configInStream, lastUpdateTime);
        } catch (Throwable t) {
            System.err.println("findEdapLogConfig \"edap-log.xml\" error " + t.getMessage() + "\n");
        }
        if (logConfig != null) {
            return logConfig;
        }
        try {
            configInStream = ConfigManager.class.getResourceAsStream("/edap-log.json5");
        } catch (Throwable t) {
            System.err.println("findEdapLogConfig \"edap-log.json5\" error " + t.getMessage() + "\n");
        }
        if (configInStream != null) {
            Json5ConfigParser parser = findJson5ConfigParser();
            if (parser != null) {
                return parser.parseConfig(configInStream);
            }
        }
        return null;
    }

    public static LogConfig parseXmlConfig(InputStream configInStream, long lastTime) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = dbf.newDocumentBuilder();
        Document doc = builder.parse(configInStream);
        Element root = doc.getDocumentElement();
        List<AppenderConfig> appenderConfigs = parseAppenders(root.getElementsByTagName("appender"));
        if (CollectionUtils.isEmpty(appenderConfigs)) {
            return null;
        }
        List<LoggerConfig> loggerConfigs = parseLoggerConfigs(root.getElementsByTagName("logger"));

        List<LoggerConfig> rootConfigs = parseLoggerConfigs(root.getElementsByTagName("root"));
        LoggerConfig rootLoggerConfig;
        if (CollectionUtils.isEmpty(rootConfigs)) {
            rootLoggerConfig = createDefaultRootLoggerConfig();
        } else {
            rootLoggerConfig = rootConfigs.get(0);
        }

        LogConfig config = new LogConfig();
        AppenderConfigSection appenderSection = new AppenderConfigSection();
        appenderSection.setAppenderConfigs(appenderConfigs);
        appenderSection.setNeedReload(false);
        appenderSection.setLastReloadTime(lastTime);
        config.setAppenderSection(appenderSection);

        LoggerConfigSection loggerConfigSection = new LoggerConfigSection();
        loggerConfigSection.setRootLoggerConfig(rootLoggerConfig);
        loggerConfigSection.setNeedReload(false);
        loggerConfigSection.setLastReloadTime(lastTime);
        loggerConfigSection.setLoggerConfigs(loggerConfigs);

        config.setLoggerSection(loggerConfigSection);
        return config;
    }

    private static List<AppenderConfig> parseAppenders(NodeList appenders) {
        if (appenders == null || appenders.getLength() <= 0) {
            return EMPTY_LIST;
        }
        List<AppenderConfig> configs = new ArrayList<>();
        for (int i=0;i<appenders.getLength();i++) {
            Node node = appenders.item(i);
            String name = getAttributeValue(node.getAttributes(), "name");
            if (isEmpty(name)) {
                continue;
            }
            String clsName = getAttributeValue(node.getAttributes(), "class");
            if (isEmpty(clsName)) {
                continue;
            }
            AppenderConfig config = new AppenderConfig();
            config.setName(name);
            config.setClazzName(clsName);
            config.setArgs(parseArgNodes(node.getChildNodes()));
            configs.add(config);
        }
        return configs;
    }

    private static List<LogConfig.ArgNode> parseArgNodes(NodeList childNodes) {
        if (childNodes == null || childNodes.getLength() <= 0) {
            return EMPTY_LIST;
        }
        List<LogConfig.ArgNode> argNodes = new ArrayList<>();
        for (int i=0;i<childNodes.getLength();i++) {
            Node child = childNodes.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                LogConfig.ArgNode node = parseArgNode(child);
                if (node != null) {
                    argNodes.add(node);
                }
            }
        }
        return argNodes;
    }

    private static LogConfig.ArgNode parseArgNode(Node child) {
        LogConfig.ArgNode argNode = new LogConfig.ArgNode();
        argNode.setName(child.getNodeName());
        if (child.hasAttributes()) {
            Map<String, String> attrs = new HashMap<>();
            NamedNodeMap attrMap = child.getAttributes();
            for (int i=0;i<attrMap.getLength();i++) {
                attrs.put(attrMap.item(i).getNodeName(), attrMap.item(i).getNodeValue());
            }
            argNode.setAttributes(attrs);
        }
        NodeList childen = child.getChildNodes();
        if (childen.getLength() == 1) {
            argNode.setValue(childen.item(0).getTextContent());
        } else {
            List<LogConfig.ArgNode> childs = parseArgNodes(child.getChildNodes());
            if (childs != null) {
                argNode.setChilds(childs);
            }
        }
        return argNode;
    }

    private static List<LoggerConfig> parseLoggerConfigs(NodeList loggers) {
        if (loggers == null || loggers.getLength() <= 0) {
            return EMPTY_LIST;
        }
        List<LoggerConfig> loggerConfigs = new ArrayList<>();
        for (int i=0;i<loggers.getLength();i++) {
            Node logger = loggers.item(i);
            if (!logger.hasAttributes()) {
                continue;
            }
            NamedNodeMap attrs = logger.getAttributes();
            String name = getAttributeValue(attrs, "name");
            if (isEmpty(name) && !logger.getNodeName().equals("root")) {
                continue;
            }
            LoggerConfig loggerConfig = new LoggerConfig();
            loggerConfig.setName(name);
            String level = getAttributeValue(attrs, "level");
            if (isEmpty(level)) {
                level = "INFO";
            }
            loggerConfig.setLevel(level);
            String additivity = getAttributeValue(attrs, "additivity");
            if (isEmpty(additivity)) {
                additivity = "true";
            }
            loggerConfig.setAdditivity(additivity);
            List<String> refs = parseAppenderRefs(logger.getChildNodes());
            if (refs != null) {
                loggerConfig.setAppenderRefs(refs);
            }
            loggerConfigs.add(loggerConfig);
        }
        return loggerConfigs;
    }

    private static List<String> parseAppenderRefs(NodeList refs) {
        if (refs == null || refs.getLength() <= 0) {
            return null;
        }
        List<String> reflist = new ArrayList<>();
        for (int i=0;i<refs.getLength();i++) {
            Node node = refs.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                String ref = getAttributeValue(node.getAttributes(), "ref");
                if (ref == null) {
                    continue;
                }
                if (!reflist.contains(ref)) {
                    reflist.add(ref);
                }
            }
        }
        return reflist;
    }

    private static String getAttributeValue(NamedNodeMap namedNodeMap, String name) {
        if (namedNodeMap == null || namedNodeMap.getLength() <= 0) {
            return null;
        }
        Node node = namedNodeMap.getNamedItem(name);
        if (node == null) {
            return null;
        }
        return node.getNodeValue();
    }

    public static LogAdapter getLogAdapter() {
        return logAdapter;
    }

    public void setLogAdapter(LogAdapter logAdapter) {
        this.logAdapter = logAdapter;
    }

    /**
     * 获取默认ROOT的logger的配置
     * @return
     */
    public static LoggerConfig createDefaultRootLoggerConfig() {
        return DEFAULT_ROOT_LOGGER_CONFIG;
    }
}
