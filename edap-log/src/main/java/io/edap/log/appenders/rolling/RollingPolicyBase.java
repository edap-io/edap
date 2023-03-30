package io.edap.log.appenders.rolling;

import io.edap.log.appenders.FileAppender;

import java.text.ParseException;

public abstract  class RollingPolicyBase implements RollingPolicy {

    protected String fileNamePatternStr;

    protected CompressionMode compressionMode = CompressionMode.NONE;

    private boolean started;

    private FileAppender parent;

    public void setFileNamePattern(String fileNamePattern) {
        this.fileNamePatternStr = fileNamePattern;
    }

    public String getFileNamePattern() {
        return fileNamePatternStr;
    }

    protected void determineCompressionMode() {
        if (fileNamePatternStr.endsWith(".gz")) {
            compressionMode = CompressionMode.GZ;
        } else if (fileNamePatternStr.endsWith(".zip")) {
            compressionMode = CompressionMode.ZIP;
        } else {
            compressionMode = CompressionMode.NONE;
        }
    }

    public String removeCompressionSuffix(String name) {
        if (name.endsWith(".gz")) {
            return name.substring(0, name.length() - 3);
        } else if (name.endsWith(".zip")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    public CompressionMode getCompressionMode() {
        return compressionMode;
    }

    public boolean isStarted() {
        return started;
    }

    public void start() throws ParseException {
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
