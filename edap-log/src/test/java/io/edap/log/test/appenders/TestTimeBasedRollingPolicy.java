package io.edap.log.test.appenders;

import io.edap.log.*;
import io.edap.log.appenders.rolling.RollingFileAppender;
import io.edap.log.appenders.rolling.TimeBasedRollingPolicy;
import io.edap.log.config.ConfigManager;
import io.edap.log.test.TestLog;
import io.edap.log.test.spi.EdapTestAdapter;
import io.edap.util.EdapTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import static io.edap.log.test.TestLog.readFile;
import static org.junit.jupiter.api.Assertions.*;

public class TestTimeBasedRollingPolicy {

    static Logger LOG = LoggerManager.getLogger(TestLog.class);

    @ParameterizedTest
    @ValueSource(strings = {
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd HH",
            "yyyy-MM-dd",
            "yyyy-MM",
            "yyyy",
    })
    public void testStart(String dateFormat) throws NoSuchFieldException, IllegalAccessException {
        TimeBasedRollingPolicy policy = new TimeBasedRollingPolicy();
        policy.setFileNamePattern("${logging.path}/javascript-${spring.application.name}.%d{" + dateFormat + "}.log.bak");
        policy.start();
        Field currentMaxTimeField = policy.getClass().getDeclaredField("currentMaxTime");
        currentMaxTimeField.setAccessible(true);
        long currentMaxTime = (long)currentMaxTimeField.get(policy);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(currentMaxTime);

        String currentDateStr = new SimpleDateFormat(dateFormat).format(cal.getTime());
        System.out.println(currentDateStr);

        currentMaxTime++;
        cal.setTimeInMillis(currentMaxTime);
        String nextMillsDateStr = new SimpleDateFormat(dateFormat).format(cal.getTime());
        System.out.println(nextMillsDateStr);

        assertNotEquals(currentDateStr, nextMillsDateStr);
    }

    @Test
    public void testAvalidDateFormat() throws NoSuchFieldException, IllegalAccessException {
        TimeBasedRollingPolicy policy = new TimeBasedRollingPolicy();
        policy.setFileNamePattern("${logging.path}/javascript-${spring.application.name}.%d{}.log.bak");
        policy.start();
        Field currentMaxTimeField = policy.getClass().getDeclaredField("currentMaxTime");
        currentMaxTimeField.setAccessible(true);
        long currentMaxTime = (long)currentMaxTimeField.get(policy);

        assertEquals(currentMaxTime, Long.MAX_VALUE);

        policy = new TimeBasedRollingPolicy();
        policy.setFileNamePattern("${logging.path}/javascript-${spring.application.name}.%p{}.log.bak");
        policy.start();
        currentMaxTimeField = policy.getClass().getDeclaredField("currentMaxTime");
        currentMaxTimeField.setAccessible(true);
        currentMaxTime = (long)currentMaxTimeField.get(policy);

        assertEquals(currentMaxTime, Long.MAX_VALUE);

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(currentMaxTime);
        String currentDateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(cal.getTime());
        System.out.println(currentDateStr);

        policy = new TimeBasedRollingPolicy();
        policy.setFileNamePattern("");
        policy.start();
        currentMaxTimeField = policy.getClass().getDeclaredField("currentMaxTime");
        currentMaxTimeField.setAccessible(true);
        currentMaxTime = (long)currentMaxTimeField.get(policy);
    }

    @Test
    public void testGetActiveFileName() throws ParseException, NoSuchFieldException, IllegalAccessException {
        TimeBasedRollingPolicy policy = new TimeBasedRollingPolicy();
        RollingFileAppender rollingFileAppender = new RollingFileAppender();
        policy.setParent(rollingFileAppender);
        policy.setFileNamePattern("${logging.path}/javascript-${spring.application.name}.%d{yyyy-MM-dd}.log");
        policy.start();
        String activeFileName = policy.getActiveFileName();
        Field currentMaxTimeField = policy.getClass().getDeclaredField("currentMaxTime");
        currentMaxTimeField.setAccessible(true);
        long currentMaxTime = (long)currentMaxTimeField.get(policy);
        assertEquals(activeFileName,
                "${logging.path}/javascript-${spring.application.name}." +
                        new SimpleDateFormat("yyyy-MM-dd").format(new Date(currentMaxTime))+ ".log");

        policy = new TimeBasedRollingPolicy();
        rollingFileAppender = new RollingFileAppender();
        policy.setParent(rollingFileAppender);
        policy.setFileNamePattern("${logging.path}/javascript-${spring.application.name}.%s{yyyy-MM-dd}.log");
        policy.start();
        activeFileName = policy.getActiveFileName();
        assertEquals(activeFileName,
                "${logging.path}/javascript-${spring.application.name}..log");

        policy = new TimeBasedRollingPolicy();
        rollingFileAppender = new RollingFileAppender();
        rollingFileAppender.setFile("./edap-rolling.log");
        policy.setParent(rollingFileAppender);
        policy.setFileNamePattern("${logging.path}/javascript-${spring.application.name}.%d{yyyy-MM-dd}.log");
        policy.start();
        activeFileName = policy.getActiveFileName();
        assertEquals(activeFileName, "./edap-rolling.log");

        policy = new TimeBasedRollingPolicy();
        rollingFileAppender = new RollingFileAppender();
        policy.setParent(rollingFileAppender);
        policy.setFileNamePattern("${logging.path}/javascript-${spring.application.name}.%d{}.log");
        policy.start();
        activeFileName = policy.getActiveFileName();
        assertEquals(activeFileName, "${logging.path}/javascript-${spring.application.name}..log");
    }

    @Test
    public void testRollover() throws ParserConfigurationException, IOException, SAXException, NoSuchFieldException, IllegalAccessException {
        try {
            EdapTestAdapter edapTestAdapter = (EdapTestAdapter) ConfigManager.getLogAdapter();
            File f = new File("./logs/");
            if (f.exists()) {
                File[] files = f.listFiles();
                for (File child : files) {
                    child.delete();
                }
                f.delete();
            }
            if (edapTestAdapter != null) {
                edapTestAdapter.reloadConfig("/edap-log-day-rollover.xml");
            }

            LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));
            f = new File("./logs/edap-day-rollover.log");
            String log = readFile(f);
            assertNotNull(log);

            assertEquals(log.substring(23), " INFO  [main] [io.edap.log.test.TestLog] [] []  - name: edap,height: 90.0 \n");

            Appender appender = AppenderManager.instance().getAppender("rollingFile");
            System.out.println("appender=" + appender);

            long now = EdapTime.instance().currentTimeMillis();
            LogEvent logEvent = new LogEvent();
            logEvent.setLogTime(now);
            logEvent.setArgv(new Object[]{"edap", 90.0});
            logEvent.setFormat("name: {},height: {}");
            logEvent.setLevel(LogLevel.INFO);
            logEvent.setThreadName("main");
            logEvent.setLoggerName("io.edap.log.test.TestLog");
            for (int i=0;i<5;i++) {
                logEvent.setLogTime(now + (i*24*60*60*1000));
                appender.append(logEvent);
            }

        } finally {
            File f = new File("./logs/");
            if (f.exists()) {
                File[] files = f.listFiles();
                for (File child : files) {
                    child.delete();
                }
                f.delete();
            }
        }

    }

    @Test
    public void testRolloverNoFile() throws ParserConfigurationException, IOException, SAXException {
        try {
            EdapTestAdapter edapTestAdapter = (EdapTestAdapter) ConfigManager.getLogAdapter();
            File f = new File("./logs/");
            if (f.exists()) {
                File[] files = f.listFiles();
                for (File child : files) {
                    child.delete();
                }
                f.delete();
            }

            if (edapTestAdapter != null) {
                edapTestAdapter.reloadConfig("/edap-log-day-rollover-nofile.xml");
            }

            Appender appender = AppenderManager.instance().getAppender("rollingFile");
            System.out.println("appender=" + appender);

            long now = EdapTime.instance().currentTimeMillis();
            LogEvent logEvent = new LogEvent();
            logEvent.setLogTime(now);
            logEvent.setArgv(new Object[]{"edap", 90.0});
            logEvent.setFormat("name: {},height: {}");
            logEvent.setLevel(LogLevel.INFO);
            logEvent.setThreadName("main");
            logEvent.setLoggerName("io.edap.log.test.TestLog");
            for (int i=0;i<5;i++) {
                logEvent.setLogTime(now + (i*24*60*60*1000));
                appender.append(logEvent);
            }

        } finally {
            File f = new File("./logs/");
            if (f.exists()) {
                File[] files = f.listFiles();
                for (File child : files) {
                    child.delete();
                }
                f.delete();
            }
        }
    }
}
