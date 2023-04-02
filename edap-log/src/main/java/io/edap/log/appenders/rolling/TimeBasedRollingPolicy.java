package io.edap.log.appenders.rolling;

import io.edap.log.LogEvent;
import io.edap.log.appenders.FileAppender;
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

public class TimeBasedRollingPolicy extends RollingPolicyBase {

    /**
     * 下一次切换日志文件前最大的时间戳
     */
    private long currentMaxTime = Long.MAX_VALUE;

    private ReentrantLock lock = new ReentrantLock(false);

    private List<EncoderPatternToken> patternTokens;

    private String dateFormat;


    @Override
    public void start() {
        try {
            EncoderPatternParser epp = new EncoderPatternParser(fileNamePatternStr);
            List<EncoderPatternToken> tokens = epp.parse();
            this.patternTokens = tokens;
            String dateFormat = getDateFormat(tokens);
            this.dateFormat = dateFormat;
            if (!StringUtil.isEmpty(dateFormat)) {
                long maxTime = getCurrentMaxTime(dateFormat);
                if (maxTime > 0) {
                    currentMaxTime = maxTime;
                }
            }
        } catch (Throwable t) {
            printError("parse fileNamePatternStr error", t);
        }
    }

    @Override
    public void stop() {

    }

    private String getDateFormat(List<EncoderPatternToken> tokens) throws ParseException {
        String dateFormat = null;
        for (EncoderPatternToken token : tokens) {
            if (token.getType() != EncoderPatternToken.TokenType.ENCODER_FUNC) {
                continue;
            }
            String keyword = token.getKeyword();
            if ("d".equals(keyword) || "date".equals(keyword)) {
                String pattern = token.getPattern();
                int kwIndex = pattern.indexOf(keyword);
                int kuoIndex = pattern.indexOf("}");
                dateFormat = pattern.substring(kwIndex + keyword.length() + 1, kuoIndex);
            }
        }
        return dateFormat;
    }

    private long getMaxTime(String dateFormat, long mills) throws ParseException {
        char c;
        int minTimeUnit = Calendar.YEAR;
        for (int i=0;i<dateFormat.length();i++) {
            c = dateFormat.charAt(i);
            switch (c) {
                case 'M':
                    minTimeUnit = Calendar.MONTH;
                    break;
                case 'd':
                    minTimeUnit = Calendar.DAY_OF_MONTH;
                    break;
                case 'H':
                    minTimeUnit = Calendar.HOUR_OF_DAY;
                    break;
                case 'm':
                    minTimeUnit = Calendar.MINUTE;
                    break;
                case 's':
                    minTimeUnit = Calendar.SECOND;
                    break;
                default:
            }
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(mills);
        String dateStr = new SimpleDateFormat(dateFormat).format(cal.getTime());
        Date date = new SimpleDateFormat(dateFormat).parse(dateStr);
        cal.setTime(date);
        cal.add(minTimeUnit, 1);
        cal.add(Calendar.MILLISECOND, -1);
        return cal.getTimeInMillis();
    }

    private long getCurrentMaxTime(String dateFormat) throws ParseException {
        char c;
        int minTimeUnit = Calendar.YEAR;
        for (int i=0;i<dateFormat.length();i++) {
            c = dateFormat.charAt(i);
            switch (c) {
                case 'M':
                    minTimeUnit = Calendar.MONTH;
                    break;
                case 'd':
                    minTimeUnit = Calendar.DAY_OF_MONTH;
                    break;
                case 'H':
                    minTimeUnit = Calendar.HOUR_OF_DAY;
                    break;
                case 'm':
                    minTimeUnit = Calendar.MINUTE;
                    break;
                case 's':
                    minTimeUnit = Calendar.SECOND;
                    break;
                default:
            }
        }
        Calendar cal = Calendar.getInstance();
        String dateStr = new SimpleDateFormat(dateFormat).format(cal.getTime());
        Date date = new SimpleDateFormat(dateFormat).parse(dateStr);
        cal.setTime(date);
        cal.add(minTimeUnit, 1);
        cal.add(Calendar.MILLISECOND, -1);
        return cal.getTimeInMillis();
    }

    @Override
    public void rollover(LogEvent logEvent) {
        // 当日志时间大于当前文件允许的最大时间时切换日志文件，如果设置不正确则不切换文件
        if (logEvent.getLogTime() <= currentMaxTime) {
            return;
        }
        FileAppender fileAppender = getParent();
        ReentrantLock lock = null;
        if (fileAppender != null) {
            lock = fileAppender.getLock();
        }
        if (lock == null) {
            lock = this.lock;
        }
        try {
            lock.lock();
            // 如果当前使用的日志文件为日志切换设置的文件名则不需要重命名文件，直接生成新的文件，然后讲Appender的
            // 的文件切换为新的文件即可。如果当前日志文件不是时间匹配的文件，则将当前文件重命名为时间匹配的文件名后
            // 生成新的文件，然后设置Appender的文件为新生成的文件。
            String activeFileName = getActiveFileName();
            String datePatternFileName = getCurrentFileNameWithoutCompressionSuffix();
            String nextFileName = getNextPeriodFileName(patternTokens);
            currentMaxTime = getMaxTime(dateFormat, currentMaxTime+1);
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
            startClearAndCompressionTask();
        } catch (ParseException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 启动一个异步线程处理日志压缩已经清理过期文件的任务
     */
    private void startClearAndCompressionTask() {
    }

    @Override
    public String getActiveFileName() throws ParseException {
        String rawFileName = getParent().rawFileProperty();
        if (!StringUtil.isEmpty(rawFileName)) {
            return rawFileName;
        } else {
            return getCurrentFileNameWithoutCompressionSuffix();
        }
    }

    private String getNextPeriodFileName(List<EncoderPatternToken> patternTokens) throws ParseException {
        StringBuilder name = new StringBuilder();
        for (EncoderPatternToken token : patternTokens) {
            if (token.getType() == EncoderPatternToken.TokenType.TEXT) {
                name.append(token.getPattern());
            } else if (token.getType() == EncoderPatternToken.TokenType.ENCODER_FUNC) {
                String keyword = token.getKeyword();
                if ("d".equals(keyword) || "date".equals(keyword)) {
                    String pattern = token.getPattern();
                    int kwIndex = pattern.indexOf(keyword);
                    int kuoIndex = pattern.indexOf("}");
                    String dateFormat = pattern.substring(kwIndex + keyword.length() + 1, kuoIndex);
                    name.append(getNextPeriodFileDateStr(dateFormat));
                }
            }
        }

        return removeCompressionSuffix(name.toString());
    }

    private String getCurrentFileNameWithoutCompressionSuffix() throws ParseException {
        StringBuilder name = new StringBuilder();
        for (EncoderPatternToken token : patternTokens) {
            if (token.getType() == EncoderPatternToken.TokenType.TEXT) {
                name.append(token.getPattern());
            } else if (token.getType() == EncoderPatternToken.TokenType.ENCODER_FUNC) {
                String keyword = token.getKeyword();
                if ("d".equals(keyword) || "date".equals(keyword)) {
                    String pattern = token.getPattern();
                    int kwIndex = pattern.indexOf(keyword);
                    int kuoIndex = pattern.indexOf("}");
                    String dateFormat = pattern.substring(kwIndex + keyword.length() + 1, kuoIndex);
                    name.append(getCurrentDateStr(dateFormat));
                }
            }
        }

        return removeCompressionSuffix(name.toString());
    }

    private String getNextPeriodFileDateStr(String dateFormat) {
        if (StringUtil.isEmpty(dateFormat)) {
            return "";
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(currentMaxTime + 1);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(dateFormat);
        return simpleDateFormat.format(cal.getTime());
    }

    private String getCurrentDateStr(String dateFormat) {
        if (StringUtil.isEmpty(dateFormat)) {
            return "";
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(currentMaxTime);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(dateFormat);
        return simpleDateFormat.format(cal.getTime());
    }

}
