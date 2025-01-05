package io.edap.log.config;

import io.edap.log.*;
import io.edap.log.appenders.ConsoleAppender;
import io.edap.log.appenders.rolling.RollingFileAppender;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static io.edap.log.consts.LogConsts.*;
import static io.edap.log.helpers.Util.printError;
import static io.edap.util.StringUtil.isEmpty;
import static java.util.Collections.EMPTY_LIST;

public class ConfigManager {

    private static LoggerConfig DEFAULT_ROOT_LOGGER_CONFIG;

    static {
        LoggerConfig config = new LoggerConfig();
        config.setLevel("INFO");
        config.setName("ROOT");
        config.setAppenderRefs(Arrays.asList(DEFAULT_CONSOLE_APPENDER_NAME, DEFAULT_FILE_APPENDER_NAME));
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

        AppenderConfigSection appenderSection = new AppenderConfigSection();
        List<AppenderConfig> appenderConfigs = new ArrayList<>();
        appenderConfigs.add(createDefaultConsoleAppenderConfig());
        appenderConfigs.add(createDefaultFileAppenderConfig());
        appenderSection.setAppenderConfigs(appenderConfigs);
        config.setAppenderSection(appenderSection);

        // 设置默认的ROOT节点配置
        LoggerConfigSection loggerConfigSection = new LoggerConfigSection();
        loggerConfigSection.setRootLoggerConfig(DEFAULT_ROOT_LOGGER_CONFIG);

        config.setLoggerSection(loggerConfigSection);

        return config;
    }

    private static AppenderConfig createDefaultConsoleAppenderConfig() {
        AppenderConfig config = new AppenderConfig();
        config.setName(DEFAULT_CONSOLE_APPENDER_NAME);
        config.setClazzName(ConsoleAppender.class.getName());

        List<LogConfig.ArgNode> args = new ArrayList();
        // 添加FileAppender的默认Encoder
        args.add(createDefaultConsoleEncoderNode());
        config.setArgs(args);

        return config;
    }

    private static LogConfig.ArgNode createDefaultConsoleEncoderNode() {
        LogConfig.ArgNode node = new LogConfig.ArgNode();
        node.setName("encoder");

        List<LogConfig.ArgNode> children = new ArrayList<>();
        children.add(createDefaultConsoleEncoderPattern());
        node.setChilds(children);

        return node;
    }

    private static LogConfig.ArgNode createDefaultConsoleEncoderPattern() {
        LogConfig.ArgNode node = new LogConfig.ArgNode();
        node.setName("pattern");
        node.setValue("%date{MM-dd HH:mm:ss.SSS} %-5level [%thread] [%logger{36}] - %msg %ex%n");

        return node;
    }

    private static AppenderConfig createDefaultFileAppenderConfig() {
        AppenderConfig config = new AppenderConfig();
        config.setName(DEFAULT_FILE_APPENDER_NAME);
        config.setClazzName(RollingFileAppender.class.getName());

        List<LogConfig.ArgNode> args = new ArrayList();
        // 添加FileAppender的默认Encoder
        String logFileName = "./app.log";
        args.add(createDefaultFileEncoderNode(logFileName));
        args.add(createDayRolloverNode(logFileName));
        config.setArgs(args);

        return config;
    }

    private static LogConfig.ArgNode createDayRolloverNode(String file) {
        LogConfig.ArgNode node = new LogConfig.ArgNode();
        node.setName("rollingPolicy");
        Map<String, String> atts = new HashMap<>();
        atts.put("class", "io.edap.log.appenders.rolling.TimeBasedRollingPolicy");
        node.setAttributes(atts);

        List<LogConfig.ArgNode> args = new ArrayList();
        int dotIndex = file.lastIndexOf(".");
        if (dotIndex == -1) {
            file += "-%d{yyyy-MM-dd}";
        } else {
            file = file.substring(0, dotIndex) + "-%d{yyyy-MM-dd}" + file.substring(dotIndex);
        }
        args.add(createSimpleNode("fileNamePattern", file));
        args.add(createSimpleNode("maxHistory", "14"));
        node.setChilds(args);

        return node;
    }

    private static LogConfig.ArgNode createSimpleNode(String name, String value) {
        LogConfig.ArgNode node = new LogConfig.ArgNode();
        node.setName(name);
        node.setValue(value);
        return node;
    }

    private static LogConfig.ArgNode createDefaultFileEncoderNode(String logFileName) {
        LogConfig.ArgNode node = new LogConfig.ArgNode();
        node.setName("encoder");

        List<LogConfig.ArgNode> children = new ArrayList<>();
        children.add(createDefaultFileEncoderPattern());
        children.add(createSimpleNode("file", logFileName));
        node.setChilds(children);

        return node;
    }

    private static LogConfig.ArgNode createDefaultFileEncoderPattern() {
        LogConfig.ArgNode node = new LogConfig.ArgNode();
        node.setName("pattern");
        node.setValue("%date{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%logger{36}] - %msg %ex%n");

        return node;
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
        String sysConfigPath = System.getProperty(CONFIG_FILE_PROPERTY);
        if (!StringUtil.isEmpty(sysConfigPath)) {
            if (sysConfigPath.startsWith("/")) {
                logConfig = getConfigFromFile(sysConfigPath);
            } else {
                logConfig = getConfigFromResources(sysConfigPath);
            }
            if (logConfig != null) {
                return logConfig;
            }
        }
        sysConfigPath = System.getenv(CONFIG_FILE_PROPERTY);
        if (!StringUtil.isEmpty(sysConfigPath)) {
            if (sysConfigPath.startsWith("/")) {
                logConfig = getConfigFromFile(sysConfigPath);
            } else {
                logConfig = getConfigFromResources(sysConfigPath);
            }
            if (logConfig != null) {
                return logConfig;
            }
        }
        try {
            configInStream = ConfigManager.class.getResourceAsStream("/edap-log.xml");
            logConfig = parseXmlConfig(configInStream, lastUpdateTime);
            if (logConfig != null) {
                return logConfig;
            }
        } catch (Throwable t) {
            System.err.println("findEdapLogConfig \"edap-log.xml\" error " + t.getMessage() + "\n");
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

    private LogConfig getConfigFromResources(String name) {
        try {
            InputStream configInStream = ConfigManager.class.getResourceAsStream("/" + name);
            return parseXmlConfig(configInStream, lastUpdateTime);
        } catch (Throwable t) {
            System.err.println("findEdapLogConfig \"edap-log.xml\" error " + t.getMessage() + "\n");
        }
        return null;
    }

    private LogConfig getConfigFromFile(String file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            return parseXmlConfig(fis, lastUpdateTime);
        } catch (Throwable t) {
            System.err.println("findEdapLogConfig \"edap-log.xml\" error " + t.getMessage() + "\n");
        }
        return null;
    }

    public static LogConfig parseXmlConfig(InputStream configInStream, long lastTime) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = dbf.newDocumentBuilder();
        Document doc = builder.parse(configInStream);
        Element root = doc.getDocumentElement();
        Map<String, Property> properties = parseProperty(root.getElementsByTagName("property"));
        Map<String, QueueConfig> queueConfigMap = parseQueueConfig(root.getElementsByTagName("queue"), properties);
        List<AppenderConfig> appenderConfigs = parseAppenders(root.getElementsByTagName("appender"), properties);
        if (CollectionUtils.isEmpty(appenderConfigs)) {
            return null;
        }
        List<LoggerConfig> loggerConfigs = parseLoggerConfigs(root.getElementsByTagName("logger"), properties);

        List<LoggerConfig> rootConfigs = parseLoggerConfigs(root.getElementsByTagName("root"), properties);
        LoggerConfig rootLoggerConfig;
        if (CollectionUtils.isEmpty(rootConfigs)) {
            rootLoggerConfig = createDefaultRootLoggerConfig();
        } else {
            rootLoggerConfig = rootConfigs.get(0);
        }

        LogConfig config = new LogConfig();

        PropertySection propertySection = new PropertySection();
        propertySection.setPropertyMap(properties);
        config.setPropertySection(propertySection);

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

    private static Map<String, QueueConfig> parseQueueConfig(NodeList queueNodes, Map<String, Property> properties) {
        Map<String, QueueConfig> queueConfigs = new HashMap<>();
        if (queueNodes == null || queueNodes.getLength() <= 0) {
            return queueConfigs;
        }

        for (int i=0;i<queueNodes.getLength();i++) {
            Node node = queueNodes.item(i);
            String name = getAttributeValue(node.getAttributes(), "name");
            if (isEmpty(name)) {
                continue;
            }
            String clazz = getAttributeValue(node.getAttributes(), "class");
            if (StringUtil.isEmpty(clazz)) {
                continue;
            }
            clazz = findAndEvalEnvValue(clazz, properties);
            QueueConfig qc = new QueueConfig();
            qc.setName(name);
            qc.setClazzName(clazz);

            qc.setArgs(parseArgNodes(node.getChildNodes(), properties));
        }
        return queueConfigs;
    }

    private static Map<String, Property> parseProperty(NodeList properties) {
        Map<String, Property> propertyMap = new HashMap<>();
        if (properties == null || properties.getLength() <= 0) {
            return propertyMap;
        }

        for (int i=0;i<properties.getLength();i++) {
            Node node = properties.item(i);
            String name = getAttributeValue(node.getAttributes(), "name");
            if (isEmpty(name)) {
                continue;
            }
            Property prop = new Property();
            prop.setName(name);
            String value = getAttributeValue(node.getAttributes(), "value");
            if (isEmpty(value)) {
                value = node.getTextContent();
            }
            if (!isEmpty(value)) {
                prop.setValue(findAndEvalEnvValue(value, propertyMap));
            }
            propertyMap.put(name, prop);
        }

        return propertyMap;
    }

    private static String findAndEvalEnvValue(String value, Map<String, Property> propertyMap) {
        int start = notSpaceIndex(value);
        if (start < value.length()) {
            int varStart = value.indexOf("${", start);
            int varEnd   = value.indexOf("}", varStart);
            if (varStart >= 0 && varEnd > varStart + 2) {
                StringBuilder eval = new StringBuilder();
                if (varStart > 0) {
                    eval.append(value.substring(0, varStart));
                }
                String key = value.substring(varStart+2, varEnd);
                Property prop = propertyMap.get(key);
                if (prop != null) {
                    eval.append(prop.getValue());
                } else {
                    eval.append(evalEnvValue(key));
                }
                eval.append(value.substring(varEnd + 1));
                return eval.toString();
            }
        }
        return value;
    }

    private static String evalEnvValue(String key) {
        String value = System.getProperty(key);
        if (StringUtil.isEmpty(value)) {
            value = System.getenv(key);
        }
        if (StringUtil.isEmpty(value)) {
            return key;
        }
        return value;
    }

    private static int notSpaceIndex(String value) {
        int i = 0;
        for (;i<value.length();i++) {
            char c = value.charAt(i);
            if (c != ' ' && c != '\t') {
                return i;
            }
        }
        return i;
    }

    private static List<AppenderConfig> parseAppenders(NodeList appenders, Map<String, Property> properties) {
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
            config.setArgs(parseArgNodes(node.getChildNodes(), properties));
            configs.add(config);
        }
        return configs;
    }

    private static List<LogConfig.ArgNode> parseArgNodes(NodeList childNodes, Map<String, Property> properties) {
        if (childNodes == null || childNodes.getLength() <= 0) {
            return EMPTY_LIST;
        }
        List<LogConfig.ArgNode> argNodes = new ArrayList<>();
        for (int i=0;i<childNodes.getLength();i++) {
            Node child = childNodes.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                LogConfig.ArgNode node = parseArgNode(child, properties);
                if (node != null) {
                    argNodes.add(node);
                }
            }
        }
        return argNodes;
    }

    private static LogConfig.ArgNode parseArgNode(Node child, Map<String, Property> properties) {
        LogConfig.ArgNode argNode = new LogConfig.ArgNode();
        argNode.setName(child.getNodeName());
        if (child.hasAttributes()) {
            Map<String, String> attrs = new HashMap<>();
            NamedNodeMap attrMap = child.getAttributes();
            for (int i=0;i<attrMap.getLength();i++) {
                attrs.put(attrMap.item(i).getNodeName(), findAndEvalEnvValue(attrMap.item(i).getTextContent(), properties));
            }
            argNode.setAttributes(attrs);
        }
        NodeList childen = child.getChildNodes();
        if (childen.getLength() == 1) {
            argNode.setValue(findAndEvalEnvValue(childen.item(0).getTextContent(), properties));
        } else {
            List<LogConfig.ArgNode> childs = parseArgNodes(child.getChildNodes(), properties);
            if (childs != null) {
                argNode.setChilds(childs);
            }
        }
        return argNode;
    }

    private static List<LoggerConfig> parseLoggerConfigs(NodeList loggers, Map<String, Property> properties) {
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
            } else {
                level = findAndEvalEnvValue(level, properties);
            }
            loggerConfig.setLevel(level);
            String additivity = getAttributeValue(attrs, "additivity");
            if (isEmpty(additivity)) {
                additivity = "true";
            } else {
                additivity = findAndEvalEnvValue(additivity, properties);
            }
            loggerConfig.setAdditivity(additivity);

            String async = getAttributeValue(attrs, "async");
            if ("true".equalsIgnoreCase(async) || "1".equalsIgnoreCase(async) || "t".equalsIgnoreCase(async)) {
                loggerConfig.setAsync(true);
            } else {
                loggerConfig.setAsync(false);
            }

            loggerConfig.setQueue(getAttributeValue(attrs, "queue"));

            List<String> refs = parseAppenderRefs(logger.getChildNodes(), properties);
            if (refs != null) {
                loggerConfig.setAppenderRefs(refs);
            }
            loggerConfigs.add(loggerConfig);
        }
        return loggerConfigs;
    }

    private static List<String> parseAppenderRefs(NodeList refs, Map<String, Property> properties) {
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
                ref = findAndEvalEnvValue(ref, properties);
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
        return node.getTextContent();
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
