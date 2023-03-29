package io.edap.log.test;

import io.edap.log.EdapLogContext;
import io.edap.log.LogAdapter;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.log.config.ConfigManager;
import io.edap.log.test.spi.EdapTestAdapter;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestLog {

    static Logger LOG = LoggerManager.getLogger(TestLog.class);

    @Test
    public void testLog() throws ParserConfigurationException, IOException, SAXException {
        EdapTestAdapter edapTestAdapter = (EdapTestAdapter)ConfigManager.getLogAdapter();
        if (edapTestAdapter != null) {
            edapTestAdapter.reloadConfig("/edap-log-basefile.xml");
        }

        LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));

        File f = new File("./edap.log");
        String log = readFile(f);
        assertNotNull(log);
        f.delete();
        assertEquals(log.substring(23), " INFO  [main] [io.edap.log.test.TestLog] [] []  - name: edap,height: 90.0 \n");

        System.out.println(EdapLogContext.instance());
    }

    private static String readFile(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        byte[] data = new byte[1024];
        int len = fis.read(data);
        if (len > 0) {
            return new String(data, 0, len, StandardCharsets.UTF_8);
        }
        return null;
    }

}
