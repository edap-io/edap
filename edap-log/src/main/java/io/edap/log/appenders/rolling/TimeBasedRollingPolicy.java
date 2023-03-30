package io.edap.log.appenders.rolling;

import io.edap.log.LogEvent;
import io.edap.log.appenders.FileAppender;
import io.edap.log.helps.EncoderPatternParser;
import io.edap.log.helps.EncoderPatternToken;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

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


    @Override
    public void start() {
        try {
            EncoderPatternParser epp = new EncoderPatternParser(fileNamePatternStr);
            String dateFormat = getDateFormat(epp.parse());
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
        for (EncoderPatternToken token : tokens) {
            if (token.getType() != EncoderPatternToken.TokenType.ENCODER_FUNC) {
                continue;
            }
            String keyword = token.getKeyword();
            if ("d".equals(keyword) || "date".equals(keyword)) {
                String pattern = token.getPattern();
                int kwIndex = pattern.indexOf(keyword);
                int kuoIndex = pattern.indexOf("}");
                return pattern.substring(kwIndex + keyword.length() + 1, kuoIndex);
            }
        }
        return null;
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
        ReentrantLock lock = getParent().getLock();
        if (lock == null) {
            lock = this.lock;
        }
        try {
            lock.lock();

        } finally {
            lock.unlock();
        }
    }

    @Override
    public String getActiveFileName() {
        String rawFileName = getParent().rawFileProperty();
        if (!StringUtil.isEmpty(rawFileName)) {
            return rawFileName;
        } else {

        }
        return null;
    }

}
