/*
 * Copyright 2021 The edap Project
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

package io.edap.log;

public class LogLevel {
    public static final int OFF   = 0x700;   // highest conceivable level, used to turn off logging
    public static final int ERROR = 0x600;
    public static final int WARN  = 0x500;
    public static final int INFO  = 0x400;
    public static final int CONF  = 0x300;
    public static final int DEBUG = 0x200;
    public static final int TRACE = 0x100;

    private LogLevel() {}
}