package io.edap.log.test;

import io.edap.log.EdapLogContext;
import io.edap.log.LogAdapter;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.log.config.ConfigManager;
import io.edap.log.test.spi.EdapTestAdapter;
import io.edap.util.EdapTime;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestLog {

    static Logger LOG = LoggerManager.getLogger(TestLog.class);

    @Test
    public void testLog() throws ParserConfigurationException, IOException, SAXException {
        EdapTestAdapter edapTestAdapter = (EdapTestAdapter)ConfigManager.getLogAdapter();
        File f = new File("./edap.log");
        if (f.exists()) {
            f.delete();
        }
        if (edapTestAdapter != null) {
            edapTestAdapter.reloadConfig("/edap-log-basefile.xml");
        }

        LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));

        f = new File("./edap.log");
        String log = readFile(f);
        assertNotNull(log);
        if (f.exists()) {
            f.delete();
        }
        assertEquals(log.substring(23), " INFO  [main] [io.edap.log.test.TestLog] [] []  - name: edap,height: 90.0 \n");

        System.out.println(EdapLogContext.instance());
    }

    @Test
    public void testFileAppender() throws ParserConfigurationException, IOException, SAXException, ParseException {
        EdapTestAdapter edapTestAdapter = (EdapTestAdapter)ConfigManager.getLogAdapter();
        File f = new File("./edap-fileappender.log");
        if (f.exists()) {
            f.delete();
        }
        if (edapTestAdapter != null) {
            edapTestAdapter.reloadConfig("/edap-log-fileappender.xml");
        }

        long start = EdapTime.instance().currentTimeMillis();
        LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));
        long end = EdapTime.instance().currentTimeMillis();
        System.out.println("start:" + start);
        System.out.println("end:" + end);
        f = new File("./edap-fileappender.log");
        String log = readFile(f);
        assertNotNull(log);
        String date = log.substring(0, 23);
        Date logDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").parse(date);
        System.out.println("date:" + date);
        System.out.println("logDate.getTime():" + logDate.getTime());
        assertEquals(logDate.getTime() >= start && logDate.getTime() <= end, true);
        if (f.exists()) {
            f.delete();
        }
        assertEquals(log.substring(23), " INFO  [main] [io.edap.log.test.TestLog] [] []  - name: edap,height: 90.0 \n");

        System.out.println(EdapLogContext.instance());

    }

    @Test
    public void testFileAppenderPrudent() throws ParserConfigurationException, IOException, SAXException, ParseException {
        EdapTestAdapter edapTestAdapter = (EdapTestAdapter)ConfigManager.getLogAdapter();
        File f = new File("./edap-fileappenderprudent.log");
        if (f.exists()) {
            f.delete();
        }
        if (edapTestAdapter != null) {
            edapTestAdapter.reloadConfig("/edap-log-fileappenderprudent.xml");
        }

        long start = EdapTime.instance().currentTimeMillis();
        LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));
        long end = EdapTime.instance().currentTimeMillis();
        System.out.println("start:" + start);
        System.out.println("end:" + end);
        f = new File("./edap-fileappenderprudent.log");
        String log = readFile(f);
        assertNotNull(log);
        String date = log.substring(0, 23);
        Date logDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").parse(date);
        System.out.println("date:" + date);
        System.out.println("logDate.getTime():" + logDate.getTime());
        assertEquals(logDate.getTime() >= start && logDate.getTime() <= end, true);
        if (f.exists()) {
            f.delete();
        }
        assertEquals(log.substring(23), " INFO  [main] [io.edap.log.test.TestLog] [] []  - name: edap,height: 90.0 \n");

        System.out.println(EdapLogContext.instance());

    }

    public static String readFile(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        byte[] data = new byte[1024];
        int len = fis.read(data);
        if (len > 0) {
            return new String(data, 0, len, StandardCharsets.UTF_8);
        }
        return null;
    }

}
