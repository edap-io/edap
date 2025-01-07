package io.edap.log.test.appenders;

import io.edap.log.*;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.util.CollectionUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import static io.edap.log.helpers.Util.printError;

public class BaseFileAppender implements Appender {

    private Encoder encoder;

    private String name;

    private FileLogWriter logWriter;

    public void setEncoder(Encoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public void append(LogEvent logEvent) {
        if (encoder == null || logWriter == null) {
            printError("appender 还没初始化");
            return;
        }
        ByteArrayBuilder builder = encoder.encode(logEvent);
        logWriter.rollover(logEvent);
        try {
            builder.writeToLogOut(logWriter);
        } catch (IOException e) {
            printError("writeToLogOut error", e);
        }
    }

    public void batchAppend(List<LogEvent> logEvents) throws IOException {
        if (encoder == null || logWriter == null) {
            printError("appender 还没初始化");
            return;
        }
        ByteArrayBuilder builder = AbstractEncoder.LOCAL_BYTE_ARRAY_BUILDER.get();
        if (CollectionUtils.isEmpty(logEvents)) {
            return;
        }
        for (LogEvent logEvent : logEvents) {
            logWriter.rollover(logEvent);
        }
        try {
            builder.writeToLogOut(logWriter);
        } catch (IOException e) {
            printError("writeToLogOut error", e);
        }
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
        FileLogWriter out = null;
        try {
            out = new FileLogWriter(file);
            this.logWriter = out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public LogWriter getLogoutStream() {
        return logWriter;
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public boolean isStarted() {
        return false;
    }

    class FileLogWriter implements RolloverLogWriter {

        FileOutputStream fos;

        public FileLogWriter(String file) throws IOException {
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
        public void rollover(LogEvent event) {

        }

        @Override
        public void writeLog(byte[] data, int offset, int length) throws IOException {
            fos.write(data, offset, length);
        }
    }
}
