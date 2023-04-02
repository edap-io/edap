package io.edap.log.appenders.rolling;

import io.edap.log.LogEvent;
import io.edap.log.appenders.FileAppender;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.helps.EncoderPatternParser;
import io.edap.log.helps.EncoderPatternToken;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.log.helpers.Util.printError;

public class TimeBasedRollingPolicy extends RollingPolicyBase implements TriggeringPolicy {

    TimeBasedFileNamingAndTriggeringPolicy timeBasedFileNamingAndTriggeringPolicy;


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
                fileAppender.stop();
                fileAppender.setFile(nextFileName);
                fileAppender.start();
            } else {
                fileAppender.stop();
                File currentFile = new File(activeFileName);
                currentFile.renameTo(new File(datePatternFileName));
                fileAppender.start();
            }
            timeBasedFileNamingAndTriggeringPolicy.startArchiveTask(datePatternFileName, nextFileName);
        } catch (ParseException e) {
            throw new RuntimeException(e);
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
    public boolean isTriggeringEvent(FileAppender appender, LogEvent event, ByteArrayBuilder builder) {
        return timeBasedFileNamingAndTriggeringPolicy.isTriggeringEvent(appender, event, builder);
    }
}
