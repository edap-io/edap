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

package io.edap.protocol.telnet.consts;

public class IacConsts {

    public static final byte SE_VAL    = (byte)240;
    public static final byte NOP_VAL   = (byte)241;
    public static final byte DM_VAL    = (byte)242;
    public static final byte BREAK_VAL = (byte)243;
    public static final byte IP_VAL    = (byte)244;
    public static final byte AO_VAL    = (byte)245;
    public static final byte AYT_VAL   = (byte)246;
    public static final byte EC_VAL    = (byte)247;
    public static final byte EL_VAL    = (byte)248;
    public static final byte GA_VAL    = (byte)249;
    public static final byte SB_VAL    = (byte)250;
    public static final byte WILL_VAL  = (byte)251;
    public static final byte WONT_VAL  = (byte)252;
    public static final byte DO_VAL    = (byte)253;
    public static final byte DONT_VAL  = (byte)254;
    public static final byte IAC_VAL   = (byte)255;

    private IacConsts() {}
}
