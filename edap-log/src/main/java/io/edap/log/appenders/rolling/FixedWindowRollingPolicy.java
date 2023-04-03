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
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.text.ParseException;
import java.util.List;

import static io.edap.log.helpers.Util.printError;

public class FixedWindowRollingPolicy extends RollingPolicyBase {
    /**
     * 最大的序号
     */
    private int maxIndex;
    /**
     * 最小的序号
     */
    private int minIndex;
    /**
     * 最大的窗口大小，强烈不建议窗口大小大于20
     */
    private static int MAX_WINDOW_SIZE = 20;

    public FixedWindowRollingPolicy() {
        minIndex = 1;
        maxIndex = 7;
    }

    /**
     * 最大的窗口大小，强烈不建议窗口大小大于20
     */
    public int getMaxWindowSize() {
        return MAX_WINDOW_SIZE;
    }

    @Override
    public void start() {
        try {
            if (maxIndex < minIndex) {
                maxIndex = minIndex;
            }

            final int maxWindowSize = getMaxWindowSize();
            if ((maxIndex - minIndex) > maxWindowSize) {
                maxIndex = minIndex + maxWindowSize;
            }
            boolean hasNumFunc = hasNumFunc();
            if (!hasNumFunc) {
                throw new RuntimeException("fileNamePattern han't %i");
            }
            super.start();
        } catch (Throwable t) {
            printError("parse fileNamePatternStr error", t);
        }
    }

    private boolean hasNumFunc() {
        String fileNamePattern = getFileNamePattern();
        EncoderPatternParser epp = new EncoderPatternParser(fileNamePattern);
        try {
            List<EncoderPatternToken> patternTokens = epp.parse();
            if (CollectionUtils.isEmpty(patternTokens)) {
                return false;
            }
            for (EncoderPatternToken token : patternTokens) {
                if (token.getType() == EncoderPatternToken.TokenType.ENCODER_FUNC) {
                    if (token.getKeyword().equals("i")) {
                        return true;
                    }
                }
            }
            printError("fileNamePattern han't %i error");
        } catch (Throwable t) {
            printError("fileNamePattern parse %i error", t);
        }
        return false;
    }

    @Override
    public void rollover() {

    }

    @Override
    public String getActiveFileName() throws ParseException {
        return null;
    }

    /**
     * 最大的序号
     */
    public int getMaxIndex() {
        return maxIndex;
    }

    public void setMaxIndex(int maxIndex) {
        this.maxIndex = maxIndex;
    }

    /**
     * 最小的序号
     */
    public int getMinIndex() {
        return minIndex;
    }

    public void setMinIndex(int minIndex) {
        this.minIndex = minIndex;
    }
}
