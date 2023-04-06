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

import io.edap.log.LogCompression;
import io.edap.log.LogEvent;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.helps.EncoderPatternParser;
import io.edap.log.helps.EncoderPatternToken;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import static io.edap.log.appenders.rolling.SizeBasedTriggeringPolicy.parseFileSize;
import static io.edap.log.helpers.Util.printMsg;

public class SizeAndTimeBasedFNATP extends TimeBasedFileNamingAndTriggeringPolicyBase{

    enum RollType {
        TIME,
        SIZE
    }

    /**
     * 设置不正确时使用最大200MB
     */
    private long maxLength = 200 * 1024 * 1024;

    /**
     * 最大文件大小设置的字符串可能包含"KB,MB,GB"的字符
     */
    private String maxFileSize;

    /**
     * 当前使用的文件个数的序号
     */
    volatile int currentSeq;

    volatile RollType rollType = RollType.SIZE;

    @Override
    public void start() {
        parseMaxFileSize();
        String fileNamePattern = tbrp.getFileNamePattern();
        EncoderPatternParser epp = new EncoderPatternParser(fileNamePattern);
        try {
            patternTokens = epp.parse();
        } catch (Throwable t) {
            throw new RuntimeException("fileNamePattern 解析失败");
        }
        dateFormat = parseSizeAndTimePattern();
        currentSeq = 0;
        if (!StringUtil.isEmpty(dateFormat)) {
            long maxTime = 0;
            try {
                maxTime = getCurrentMaxTime(dateFormat);
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
            if (maxTime > 0) {
                currentMaxTime = maxTime;
            }
        }
        currentSeq = scanAndGetSeq();
        started = true;
    }

    @Override
    public void rollover() {
        if (rollType == RollType.SIZE) {
            currentSeq++;
        } else {
            this.currentMaxTime = getMaxTime(dateFormat, currentMaxTime + 1);
            currentSeq = 0;
        }
    }

    private void parseMaxFileSize() {
        if (!StringUtil.isEmpty(getMaxFileSize())) {
            long size = parseFileSize(getMaxFileSize());
            if (size > 0) {
                setMaxLength(size);
            }
        }
    }

    @Override
    public boolean isTriggeringEvent(File activeFile, LogEvent event, ByteArrayBuilder builder) {
        if (event.getLogTime() > currentMaxTime) {
            rollType = RollType.TIME;
            return true;
        }
        if (builder.length() + activeFile.length() > maxLength) {
            rollType = RollType.SIZE;
            return true;
        }
        return false;
    }

    public long getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(long maxLength) {
        this.maxLength = maxLength;
    }

    public String getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(String maxFileSize) {
        this.maxFileSize = maxFileSize;
        parseMaxFileSize();
    }

    /**
     * 扫描日志文件获取当天日期下按大小分文件的最大的序列号
     * @return
     */
    private int scanAndGetSeq() {
        String fileName = calFileName(System.currentTimeMillis(), 0);
        File logFile = new File(fileName);
        if (!logFile.exists()) {
            return 0;
        }
        File logDir = logFile.getParentFile();
        File[] logFiles = logDir.listFiles();
        FileNamePattern fnp = parseFileNamePattern();
        String dateFormatPart;
        int lastSlash = fnp.dateFormatPart.lastIndexOf("/");
        if (lastSlash != -1) {
            dateFormatPart = fnp.dateFormatPart.substring(lastSlash+1);
        } else {
            dateFormatPart = fnp.dateFormatPart;
        }
        int maxSeq = 0;
        for (File log : logFiles) {
            String logName = log.getName();
            if (logName.startsWith(dateFormatPart) && logName.endsWith(fnp.seqSuffix)) {
                System.out.println(log.getName());
                try {
                    int seq = Integer.parseInt(logName.substring(dateFormatPart.length() + fnp.getMidPart().length(),
                            logName.length() - fnp.seqSuffix.length()));
                    if (seq > maxSeq) {
                        maxSeq = seq;
                    }
                } catch (Exception e) {
                    printMsg("parseInt error");
                }
            }
        }
        return maxSeq;
    }

    private String calFileName(long logTime, int seq) {
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<patternTokens.size();i++) {
            EncoderPatternToken token = patternTokens.get(i);
            if (token.getType() == EncoderPatternToken.TokenType.ENCODER_FUNC) {
                String keyword = "";
                try {
                    keyword = token.getKeyword();
                } catch (ParseException e) {
                    throw new RuntimeException("fileNamePattern 解析失败");
                }
                switch (keyword) {
                    case "d":
                    case "date":
                        String pattern = token.getPattern();
                        int kwIndex = pattern.indexOf(keyword);
                        int kuoIndex = pattern.indexOf("}");
                        dateFormat = pattern.substring(kwIndex + keyword.length() + 1, kuoIndex);
                        sb.append(new SimpleDateFormat(dateFormat).format(new Date(logTime)));
                        break;
                    case "i":
                        sb.append(seq);
                        break;
                    default:
                }
            } else {
                sb.append(token.getPattern());
            }
        }
        return sb.toString();
    }

    private FileNamePattern parseFileNamePattern(Date now) {
        String dateFormatPart = "";
        StringBuilder sb = new StringBuilder();
        String midPart = "";
        for (int i=0;i<patternTokens.size();i++) {
            EncoderPatternToken token = patternTokens.get(i);
            if (token.getType() == EncoderPatternToken.TokenType.ENCODER_FUNC) {
                String keyword = "";
                try {
                    keyword = token.getKeyword();
                } catch (ParseException e) {
                    throw new RuntimeException("fileNamePattern 解析失败");
                }
                switch (keyword) {
                    case "d":
                    case "date":
                        String pattern = token.getPattern();
                        int kwIndex = pattern.indexOf(keyword);
                        int kuoIndex = pattern.indexOf("}");
                        dateFormat = pattern.substring(kwIndex + keyword.length() + 1, kuoIndex);
                        sb.append(new SimpleDateFormat(dateFormat).format(now));
                        dateFormatPart = sb.toString();
                        sb.delete(0, sb.length());
                        break;
                    case "i":
                        if (sb.length() > 0) {
                            midPart = sb.toString();
                        }
                        sb.delete(0, sb.length());
                        break;
                    default:
                }
            } else {
                sb.append(token.getPattern());
            }
        }
        String suffix = sb.toString();
        FileNamePattern fp = new FileNamePattern();
        fp.setDateFormatPart(dateFormatPart);
        fp.setSeqSuffix(suffix);
        fp.setMidPart(midPart);
        return fp;
    }

    private FileNamePattern parseFileNamePattern() {
        return parseFileNamePattern(new Date());
    }

    class FileNamePattern {
        /**
         * 日志文件带当前日期的字符串部分，用于文件前置匹配
         */
        private String dateFormatPart;
        /**
         * 文件序号后的文件名部分，用于后置匹配
         */
        private String seqSuffix;
        private String midPart;


        /**
         * 日志文件带当前日期的字符串部分，用于文件前置匹配
         */
        public String getDateFormatPart() {
            return dateFormatPart;
        }

        public void setDateFormatPart(String dateFormatPart) {
            this.dateFormatPart = dateFormatPart;
        }

        /**
         * 文件序号后的文件名部分，用于后置匹配
         */
        public String getSeqSuffix() {
            return seqSuffix;
        }

        public void setSeqSuffix(String seqSuffix) {
            this.seqSuffix = seqSuffix;
        }

        /**
         * 时间和序号之间的字符长度
         */
        public String getMidPart() {
            return midPart;
        }

        public void setMidPart(String midPart) {
            this.midPart = midPart;
        }
    }

    private String parseSizeAndTimePattern() {
        if (CollectionUtils.isEmpty(patternTokens)) {
            throw new RuntimeException("文件名设置错误，需包含$d{}以及%i");
        }
        int dateIndex = -1;
        int seqIndex = -1;
        String dateFormat = "";
        for (int i=0;i<patternTokens.size();i++) {
            EncoderPatternToken token = patternTokens.get(i);
            if (token.getType() == EncoderPatternToken.TokenType.ENCODER_FUNC) {
                String keyword = "";
                try {
                    keyword = token.getKeyword();
                } catch (ParseException e) {
                    throw new RuntimeException("fileNamePattern 解析失败");
                }
                switch (keyword) {
                    case "d":
                    case "date":
                        String pattern = token.getPattern();
                        int kwIndex = pattern.indexOf(keyword);
                        int kuoIndex = pattern.indexOf("}");
                        dateFormat = pattern.substring(kwIndex + keyword.length() + 1, kuoIndex);
                        dateIndex = i;
                        break;
                    case "i":
                        seqIndex = i;
                        break;
                    default:
                }
            }
        }
        if (dateIndex < 0) {
            throw new RuntimeException("fileNamePattern 需包含%d{}的日志格式化部分");
        }
        if (seqIndex < 0) {
            throw new RuntimeException("fileNamePattern 需包含%i的序号部分");
        }
        if (dateIndex > seqIndex) {
            throw new RuntimeException("fileNamePattern 中\"%i\"需在\"%d{}\"的后面");
        }
        return dateFormat;
    }

    @Override
    public String getCurrentPeriodsFileNameWithoutCompressionSuffix() {
        String fileName = calFileName(currentMaxTime,currentSeq);
        return removeCompressionSuffix(fileName);
    }

    @Override
    public String getElapsedPeriodsFileName() {
        long maxTime;
        int curSeq;
        if (rollType == RollType.SIZE) {
            maxTime = currentMaxTime;
            curSeq = currentSeq + 1;
        } else {
            maxTime = currentMaxTime + 1;
            curSeq = 0;
        }
        String fileName = calFileName(maxTime, curSeq);
        return removeCompressionSuffix(fileName);
    }

    @Override
    public List<String> getExpireNames(int count) {
        if (count <= 0) {
            return null;
        }
        SimpleDateFormat dateFormat1 = new SimpleDateFormat(dateFormat);
        Calendar now = Calendar.getInstance();
        System.out.println("now=" + dateFormat1.format(now.getTime()));
        int minTimeUnit = getRolloverTimeUnit();
        now.add(minTimeUnit, -(count+1));

        List<String> needDeleteFiles = new ArrayList<>();
        String fileName = calFileName(now.getTimeInMillis(), 0);
        File logFile = new File(fileName);
        if (!logFile.getParentFile().exists()) {
            return null;
        }
        File logDir = logFile.getParentFile();
        File[] logFiles = logDir.listFiles();
        FileNamePattern fnp = parseFileNamePattern(now.getTime());
        String dateFormatPart;
        int lastSlash = fnp.dateFormatPart.lastIndexOf("/");
        if (lastSlash != -1) {
            dateFormatPart = fnp.dateFormatPart.substring(lastSlash+1);
        } else {
            dateFormatPart = fnp.dateFormatPart;
        }
        for (File log : logFiles) {
            String logName = log.getName();
            if (logName.startsWith(dateFormatPart) && logName.endsWith(fnp.seqSuffix)) {
                System.out.println(log.getName());
                try {
                    Integer.parseInt(logName.substring(dateFormatPart.length() + fnp.getMidPart().length(),
                            logName.length() - fnp.seqSuffix.length()));
                    needDeleteFiles.add(dateFormatPart + "/" + logName);
                } catch (Exception e) {
                    printMsg("parseInt error");
                }
            }
        }
        return needDeleteFiles;
    }
}
