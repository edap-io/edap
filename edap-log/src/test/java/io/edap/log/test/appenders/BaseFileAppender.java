package io.edap.log.test.appenders;

import io.edap.log.Appender;
import io.edap.log.Encoder;
import io.edap.log.LogEvent;
import io.edap.log.LogOutputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static io.edap.log.helpers.Util.printError;

public class BaseFileAppender implements Appender {

    private Encoder encoder;

    private String name;

    private LogOutputStream logOutputStream;

    public void setEncoder(Encoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public void append(LogEvent logEvent) {
        if (encoder == null || logOutputStream == null) {
            printError("appender 还没初始化");
            return;
        }
        encoder.encode(logOutputStream, logEvent);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    public void setFile(String file) {
        FileLogOutputStream out = null;
        try {
            out = new FileLogOutputStream(file);
            this.logOutputStream = out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public LogOutputStream getLogoutStream() {
        return logOutputStream;
    }

    class FileLogOutputStream implements LogOutputStream {

        FileOutputStream fos;

        public FileLogOutputStream(String file) throws IOException {
            File f = new File(file);
            if (!f.exists()) {
                if (!f.getParentFile().exists()) {
                    f.getParentFile().mkdirs();
                    f.createNewFile();
                }
            }
            f = new File(file);
            if (f.exists()) {
                fos = new FileOutputStream(f, true);
                System.out.println(f.getAbsolutePath());
            } else {
                f.createNewFile();
                fos = new FileOutputStream(f, true);
                System.out.println(f.getAbsolutePath());
            }
        }

        @Override
        public void writeLog(byte[] data, int offset, int length) throws IOException {
            fos.write(data, offset, length);
        }
    }
}
