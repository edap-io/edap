package io.edap.log.appenders.rolling;

import io.edap.log.LogEvent;
import io.edap.log.appenders.FileAppender;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.util.StringUtil;

import java.io.File;
import java.text.ParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.edap.log.helpers.Util.printError;

public class TimeBasedRollingPolicy extends RollingPolicyBase implements TriggeringPolicy {

    ExecutorService executorService = Executors.newSingleThreadExecutor();

    TimeBasedFileNamingAndTriggeringPolicy timeBasedFileNamingAndTriggeringPolicy;

    /**
     * 保留历史文件的个数，默认不删除
     */
    protected int maxHistory = 0;

    @Override
    public void start() {
        try {
            if (timeBasedFileNamingAndTriggeringPolicy == null) {
                timeBasedFileNamingAndTriggeringPolicy = new DefaultTimeBasedFileNamingAndTriggeringPolicy();
            }
            timeBasedFileNamingAndTriggeringPolicy.setTimeBasedRollingPolicy(this);
            timeBasedFileNamingAndTriggeringPolicy.start();

            super.start();
        } catch (Throwable t) {
            printError("parse fileNamePatternStr error", t);
        }
    }

    @Override
    public void stop() {

    }

    @Override
    public void rollover() {
        // 当日志时间大于当前文件允许的最大时间时切换日志文件，如果设置不正确则不切换文件
        FileAppender fileAppender = getParent();
        try {
            // 如果当前使用的日志文件为日志切换设置的文件名则不需要重命名文件，直接生成新的文件，然后讲Appender的
            // 的文件切换为新的文件即可。如果当前日志文件不是时间匹配的文件，则将当前文件重命名为时间匹配的文件名后
            // 生成新的文件，然后设置Appender的文件为新生成的文件。
            String activeFileName = getActiveFileName();
            TimeBasedFileNamingAndTriggeringPolicy tbfat = timeBasedFileNamingAndTriggeringPolicy;
            String datePatternFileName = tbfat.getCurrentPeriodsFileNameWithoutCompressionSuffix();
            String nextFileName = tbfat.getElapsedPeriodsFileName();
            if (activeFileName.equals(datePatternFileName)) {
                fileAppender.closeOutputStream();
                fileAppender.setFile(nextFileName);
                fileAppender.openFile();
            } else {
                fileAppender.closeOutputStream();
                File currentFile = new File(activeFileName);
                currentFile.renameTo(new File(datePatternFileName));
                fileAppender.openFile();
            }
            timeBasedFileNamingAndTriggeringPolicy.rollover();
            startArchiveTask(datePatternFileName, nextFileName);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    public void startArchiveTask(final String currentFileName, final String nextPeriodName) {
        String fileName = timeBasedFileNamingAndTriggeringPolicy.getExpireName(maxHistory);
        executorService.submit(() -> doArchive(currentFileName, fileName));
    }

    private void doArchive(String currentFileName, String needDeleteFileName) {
        File archiveDir = new File(currentFileName);
        if (!archiveDir.exists()) {
            return;
        }
        // 如果有设置压缩则先压缩刚切换的日志文件
        switch (compressionMode) {
            case GZ:
                compressionFile(currentFileName, compressionMode);
                break;
            case ZIP:
                compressionFile(currentFileName, compressionMode);
                break;
            default:

        }
        // 删除过时的日志文件
        printError("maxHistory=" + maxHistory);
        if (maxHistory > 0) {

            printError("currentFileName=" + currentFileName);
            printError("getExpireName=" + needDeleteFileName);
            if (needDeleteFileName == null) {
                return;
            }
            File f = new File(needDeleteFileName);
            if (f.exists()) {
                printError("delete=" + needDeleteFileName);
                f.delete();
            } else {
                printError("exists is false=" + needDeleteFileName);
                if (compressionMode != CompressionMode.NONE) {
                    int lastDot = needDeleteFileName.lastIndexOf(".");
                    if (lastDot != -1) {
                        String noCompressionName = needDeleteFileName.substring(0, lastDot);
                        f = new File(noCompressionName);
                        if (f.exists()) {
                            printError("delete=" + noCompressionName);
                            f.delete();
                        }
                    }
                }
            }
        }
    }

    private void compressionFile(String currentFileName, CompressionMode compressionMode) {

    }

    @Override
    public String getActiveFileName() throws ParseException {
        String rawFileName = getParent().rawFileProperty();
        if (!StringUtil.isEmpty(rawFileName)) {
            return rawFileName;
        } else {
            return timeBasedFileNamingAndTriggeringPolicy.getCurrentPeriodsFileNameWithoutCompressionSuffix();
        }
    }



    @Override
    public boolean isTriggeringEvent(FileAppender appender, LogEvent event, ByteArrayBuilder builder) {
        return timeBasedFileNamingAndTriggeringPolicy.isTriggeringEvent(appender, event, builder);
    }

    /**
     * 保留历史文件的个数，默认不删除
     */
    public int getMaxHistory() {
        return maxHistory;
    }

    public void setMaxHistory(int maxHistory) {
        this.maxHistory = maxHistory;
    }
}
