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

package io.edap.util;

public class Constants {

    private Constants() {}

    /**
     * FNV_1a hash算法的hash初始值
     */
    public static final long FNV_1a_INIT_VAL = 0x811c9dc5;
    /**
     * FNV_1a hash算法的计算因子
     */
    public static final long FNV_1a_FACTOR_VAL = 0x1000193;
}
