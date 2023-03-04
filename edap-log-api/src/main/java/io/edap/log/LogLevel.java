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

    public static final byte[] OFF_BYTES   = new byte[]{'O', 'F', 'F'};   // highest conceivable level, used to turn off logging
    public static final byte[] ERROR_BYTES = new byte[]{'E', 'R', 'R', 'O', 'R'};
    public static final byte[] WARN_BYTES  = new byte[]{'W', 'A', 'R', 'N'};
    public static final byte[] INFO_BYTES  = new byte[]{'I', 'N', 'F', 'O'};
    public static final byte[] CONF_BYTES  = new byte[]{'C', 'O', 'N', 'F'};
    public static final byte[] DEBUG_BYTES = new byte[]{'D', 'E', 'B', 'U', 'G'};
    public static final byte[] TRACE_BYTES = new byte[]{'T', 'R', 'A', 'C', 'E'};

    private LogLevel() {}
}