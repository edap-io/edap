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

package io.edap.protocol.telnet;

import static io.edap.protocol.telnet.consts.IacConsts.*;

public enum IAC {

    SE   (SE_VAL,    "SE",               "End of subnegotiation parameters"),
    NOP  (NOP_VAL,   "NOP",              "No operation"),
    DM   (DM_VAL,    "Data Mark",        "The data stream portion of a Synch. This should always be accompanied by a TCP Urgent notification"),
    BREAK(BREAK_VAL, "Break",            "NVT character BRK"),
    IP   (IP_VAL,    "Interrupt Process","The function IP"),
    AO   (AO_VAL,    "Abort output",     "The function AO"),
    AYT  (AYT_VAL,   "Are You There",    "The function AYT"),
    EC   (EC_VAL,    "Erase character",  "The function EC"),
    EL   (EL_VAL,    "Erase Line",       "The function EL"),
    GA   (GA_VAL,    "Go ahead",         "The GA signal"),
    SB   (SB_VAL,    "SB",               "Indicates that what follows is subnegotiation of the indicated option"),
    WILL (WILL_VAL,  "WILL",             "Indicates the desire to begin performing, or confirmation that you are now performing, the indicated option"),
    WONT (WONT_VAL,  "WON'T",            "Indicates the refusal to perform, or continue performing, the indicated option"),
    DO   (DO_VAL,    "DO",               "Indicates the request that the other party perform, or confirmation that you are expecting the other party to perform, the indicated option"),
    DONT (DONT_VAL,  "DON'T",            "Indicates the demand that the other party stop performing, or confirmation that you are no longer expecting the other party to perform, the indicated option"),
    IAC  (IAC_VAL,   "IAC",              "Data Byte 255");


    byte   code;
    String name;
    String meaning;

    IAC(byte code, String name, String meaning) {
        this.code    = code;
        this.name    = name;
        this.meaning = meaning;
    }

    public byte getCode() {
        return code;
    }

    public String getName() {
        return this.name;
    }

    public String getMeaning() {
        return meaning;
    }
}
