package io.edap.log.appenders.rolling;

import io.edap.log.LogCompression;
import io.edap.log.appenders.FileAppender;
import io.edap.log.compression.CompressionManager;

import java.text.ParseException;
import java.util.Map;

public abstract class RollingPolicyBase implements RollingPolicy {

    protected String fileNamePatternStr;

    protected LogCompression compression = null;

    private boolean started;

    private FileAppender parent;

    public void setFileNamePattern(String fileNamePattern) {
        this.fileNamePatternStr = fileNamePattern;
    }

    public String getFileNamePattern() {
        return fileNamePatternStr;
    }

    protected void determineCompressionMode() {
        Map<String, LogCompression> compressions = CompressionManager.getInstance().getCompressionMap();
        int lastDot = fileNamePatternStr.lastIndexOf(".");
        if (lastDot == -1) {
            return;
        }
        String suffix = fileNamePatternStr.substring(lastDot+1);
        compression = compressions.get(suffix);
    }

    @Override
    public LogCompression getCompression() {
        return compression;
    }

    public boolean isStarted() {
        return started;
    }

    public void start() throws ParseException {
        determineCompressionMode();
        started = true;
    }

    public void stop() {
        started = false;
    }

    public void setParent(FileAppender appender) {
        this.parent = appender;
    }

    public FileAppender getParent() {
        return this.parent;
    }

}
