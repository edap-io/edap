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
import java.io.IOException;
import java.util.Iterator;
import java.util.ServiceLoader;

public class TestLog {

    static Logger LOG = LoggerManager.getLogger(TestLog.class);

    @Test
    public void testLog() throws ParserConfigurationException, IOException, SAXException {
        EdapTestAdapter edapTestAdapter = (EdapTestAdapter)ConfigManager.getLogAdapter();
        if (edapTestAdapter != null) {
            edapTestAdapter.reloadConfig("/edap-log-basefile.xml");
        }

        LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));
        System.out.println(EdapLogContext.instance());
    }

}
