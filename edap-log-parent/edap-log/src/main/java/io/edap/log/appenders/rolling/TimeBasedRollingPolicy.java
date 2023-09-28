package io.edap.log.appenders.rolling;

import io.edap.log.LogEvent;
import io.edap.log.appenders.FileAppender;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.io.File;
import java.text.ParseException;
import java.util.List;

import static io.edap.log.helpers.Util.printError;

public class TimeBasedRollingPolicy extends RollingPolicyBase implements TriggeringPolicy {

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
            List<String> fileNames = timeBasedFileNamingAndTriggeringPolicy.getExpireNames(maxHistory);
            startArchiveTask(datePatternFileName, fileNames);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setFileAppender(FileAppender fileAppender) {
        if (getParent() == null) {
            setParent(fileAppender);
        }
    }

    public void startArchiveTask(final String currentFileName, List<String> needDeleteFileNames) {

        executorService.submit(() -> doArchive(currentFileName, needDeleteFileNames));
    }

    private void doArchive(String currentFileName, List<String> needDeleteFileNames) {
        File archiveDir = new File(currentFileName);
        if (!archiveDir.exists()) {
            return;
        }
        // 如果有设置压缩则先压缩刚切换的日志文件
        if (compression != null) {
            String zipName = currentFileName.endsWith("." + compression.getSuffix())
                    ?currentFileName:currentFileName + "." + compression.getSuffix();
            try {
                compression.compress(new File(currentFileName), new File(zipName));
                File f = new File(currentFileName);
                if (f.exists()) {
                    f.delete();
                }
            } catch (Throwable t) {
                printError("compression.compress error", t);
            }
        }
        // 删除过时的日志文件
        if (maxHistory <= 0) {
            return;
        }
        if (CollectionUtils.isEmpty(needDeleteFileNames)) {
            return;
        }
        for (String needDeleteFileName : needDeleteFileNames) {
            File f = new File(needDeleteFileName);
            if (f.exists()) {
                f.delete();
                continue;
            }
            // 如果有压缩判断压缩后的文件是否存在如果存在则删除
            if (compression == null) {
                continue;
            }
            int lastDot = needDeleteFileName.lastIndexOf(".");
            if (lastDot == -1) {
                continue;
            }
            String noCompressionName = needDeleteFileName.substring(0, lastDot);
            f = new File(noCompressionName);
            if (f.exists()) {
                f.delete();
            }
        }
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
    public boolean isTriggeringEvent(final File activeFile, LogEvent event, ByteArrayBuilder builder) {
        return timeBasedFileNamingAndTriggeringPolicy.isTriggeringEvent(activeFile, event, builder);
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
