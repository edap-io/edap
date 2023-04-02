/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.log.appenders.rolling;

import io.edap.log.helps.EncoderPatternParser;
import io.edap.log.helps.EncoderPatternToken;
import io.edap.util.StringUtil;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static io.edap.log.helpers.Util.printError;

public abstract class TimeBasedFileNamingAndTriggeringPolicyBase
        implements TimeBasedFileNamingAndTriggeringPolicy {

    protected TimeBasedRollingPolicy tbrp;

    /**
     * 下一次切换日志文件前最大的时间戳
     */
    protected volatile long currentMaxTime = Long.MAX_VALUE;

    protected boolean started = false;

    private List<EncoderPatternToken> patternTokens;

    private String dateFormat;

    public void setTimeBasedRollingPolicy(TimeBasedRollingPolicy tbrp) {
        this.tbrp = tbrp;
    }

    public boolean isStarted() {
        return started;
    }

    @Override
    public void start() {
        String fileNamePattern = tbrp.getFileNamePattern();
        EncoderPatternParser epp = new EncoderPatternParser(fileNamePattern);
        try {
            patternTokens = epp.parse();
            String dateFormat = getDateFormat(patternTokens);
            this.dateFormat = dateFormat;
            if (!StringUtil.isEmpty(dateFormat)) {
                long maxTime = getCurrentMaxTime(dateFormat);
                if (maxTime > 0) {
                    currentMaxTime = maxTime;
                }
            }
        } catch (Throwable t) {
            printError("parse fileNamePattern error", t);
        }
    }

    @Override
    public void startArchiveTask(String currentFileName, String nextPeriodName) {
        this.currentMaxTime = getMaxTime(dateFormat, currentMaxTime + 1);
    }

    @Override
    public void stop() {
        started = false;
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

    private long getMaxTime(String dateFormat, long mills) {
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
        Date date = null;
        try {
            date = new SimpleDateFormat(dateFormat).parse(dateStr);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
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
    public String getElapsedPeriodsFileName() {
        StringBuilder name = new StringBuilder();
        for (EncoderPatternToken token : patternTokens) {
            if (token.getType() == EncoderPatternToken.TokenType.TEXT) {
                name.append(token.getPattern());
            } else if (token.getType() == EncoderPatternToken.TokenType.ENCODER_FUNC) {
                String keyword = null;
                try {
                    keyword = token.getKeyword();
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
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

    @Override
    public String getCurrentPeriodsFileNameWithoutCompressionSuffix() {
        StringBuilder name = new StringBuilder();
        for (EncoderPatternToken token : patternTokens) {
            if (token.getType() == EncoderPatternToken.TokenType.TEXT) {
                name.append(token.getPattern());
            } else if (token.getType() == EncoderPatternToken.TokenType.ENCODER_FUNC) {
                String keyword = null;
                try {
                    keyword = token.getKeyword();
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
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

    public String removeCompressionSuffix(String name) {
        if (name.endsWith(".gz")) {
            return name.substring(0, name.length() - 3);
        } else if (name.endsWith(".zip")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
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
