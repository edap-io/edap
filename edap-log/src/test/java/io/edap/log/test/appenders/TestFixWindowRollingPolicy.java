package io.edap.log.test.appenders;

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.log.config.ConfigManager;
import io.edap.log.test.spi.EdapTestAdapter;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestFixWindowRollingPolicy {

    static Logger LOG = LoggerManager.getLogger(TestFixWindowRollingPolicy.class);

    @Test
    public void testSizeBaseRollingPolicy() {
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
                edapTestAdapter.reloadConfig("/edap-log-size-rollover.xml");
            }

            for (int i=0;i<100;i++) {
                LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));
            }

        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (SAXException e) {
            throw new RuntimeException(e);
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
    public void testSizeBaseRollingPolicyZipCompress() {
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
                edapTestAdapter.reloadConfig("/edap-log-size-rollover-zipcompress.xml");
            }

            for (int i=0;i<100;i++) {
                LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));
            }

            Thread.sleep(3000);

            f = new File("./logs/");
            assertEquals(f.exists(), true);
            File[] files = f.listFiles();
            List<String> logNames = new ArrayList<>();
            for (File logFile : files) {
                logNames.add(logFile.getName());
            }

            int count = 0;
            for (int i=1;i<6;i++) {
                String name = "edap-size-rollover." + i + ".log.zip";
                if (logNames.contains(name)) {
                    count++;
                }
            }
            assertEquals(count > 0, true);

        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (SAXException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
//            File f = new File("./logs/");
//            if (f.exists()) {
//                File[] files = f.listFiles();
//                for (File child : files) {
//                    child.delete();
//                }
//                f.delete();
//            }
        }
    }

    @Test
    public void testSizeBaseRollingPolicyGzCompress() {
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
                edapTestAdapter.reloadConfig("/edap-log-size-rollover-gzcompress.xml");
            }

            for (int i=0;i<100;i++) {
                LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));
            }

            Thread.sleep(1000);

            f = new File("./logs/");
            assertEquals(f.exists(), true);
            File[] files = f.listFiles();
            List<String> logNames = new ArrayList<>();
            for (File logFile : files) {
                logNames.add(logFile.getName());
            }
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            int count = 0;
            for (int i=1;i<6;i++) {
                String name = "edap-size-rollover." + i + ".log.gz";
                if (logNames.contains(name)) {
                    count++;
                }
            }

            assertEquals(files.length, count+1);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (SAXException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
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
    public void testSizeBaseRollingPolicyLz4Compress() {
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
                edapTestAdapter.reloadConfig("/edap-log-size-rollover-lz4compress.xml");
            }

            for (int i=0;i<100;i++) {
                LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));
            }

            Thread.sleep(1000);

            f = new File("./logs/");
            assertEquals(f.exists(), true);
            File[] files = f.listFiles();
            List<String> logNames = new ArrayList<>();
            for (File logFile : files) {
                logNames.add(logFile.getName());
            }
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            for (int i=1;i<2;i++) {
                String name = "edap-size-rollover." + i + ".log.lz4";
                assertEquals(logNames.contains(name), true);
            }

            assertEquals(files.length, 2);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (SAXException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
//            File f = new File("./logs/");
//            if (f.exists()) {
//                File[] files = f.listFiles();
//                for (File child : files) {
//                    child.delete();
//                }
//                f.delete();
//            }
        }
    }
}
