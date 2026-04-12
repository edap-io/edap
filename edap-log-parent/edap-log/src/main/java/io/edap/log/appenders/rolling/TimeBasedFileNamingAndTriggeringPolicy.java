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

import java.util.List;

public interface TimeBasedFileNamingAndTriggeringPolicy extends TriggeringPolicy {

    void setTimeBasedRollingPolicy(TimeBasedRollingPolicy tbrp);

    String getElapsedPeriodsFileName();

    String getCurrentPeriodsFileNameWithoutCompressionSuffix();

    void rollover();

    /**
     * 根据保留的日志文件的数量来获取过期的日志文件名
     * @return
     */
    List<String> getExpireNames(int count);
}
