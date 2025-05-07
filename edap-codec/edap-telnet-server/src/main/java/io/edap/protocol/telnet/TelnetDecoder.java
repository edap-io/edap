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

import io.edap.Decoder;
import io.edap.buffer.FastBuf;
import io.edap.nio.ParseResult;
import io.edap.nio.util.BytesBuilder;
import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.edap.protocol.telnet.consts.IacConsts.*;

public class TelnetDecoder implements Decoder<TelnetRequest, TelnetServerNioSession> {

    @Override
    public ParseResult<TelnetRequest> decode(FastBuf bufIn, TelnetServerNioSession nioSession) {
        ParseResult<TelnetRequest> result = new ParseResult<>();
        FastBuf _buf   = bufIn;
        int     remain = _buf.remain();
        long    pos    = _buf.rpos();
        BytesBuilder bytes = new BytesBuilder();
        if (remain > 0) {
            byte b = _buf.get(pos);
            if (b == IAC_VAL) {
                IACCommand iacCommand;
                if (remain == 5) {
                    pos++;
                    IAC iac = parseIacCommand(_buf.get(pos++));
                    iacCommand = new IACCommand();
                    iacCommand.setCommand(iac);
                    iac = parseIacCommand(_buf.get(pos++));
                    if (iac != IAC.IAC) {
                        throw new RuntimeException("Illegal IAC Commands " + iac);
                    }
                    iacCommand.setIac(parseIacCommand(_buf.get(pos++)));
                    iacCommand.setOption(_buf.get(pos++) & 0xFF);

                    result.setFinished(true);
                    result.setMessage(iacCommand);
                }
            } else {
                for (int i=0;i<remain;i++) {
                    b = _buf.get(pos++);
                    if (b == '\n') {
                        // 该行有"\\" 结尾则命令行不结束，将"\\"以及以后的部分替换为空格继续解析
                        int lastIndexBackslash = lastIndexOfBackslash(bytes);
                        if (lastIndexBackslash == -1) {
                            String commandLine = bytes.toString(StandardCharsets.UTF_8);
                            ShellCommand command = new ShellCommand();
                            int spaceIndex = commandLine.indexOf(" ");
                            if (spaceIndex == -1) {
                                command.setCommand(commandLine);
                            } else {
                                command.setCommand(commandLine.substring(0, spaceIndex));
                                command.setArgs(parseArgs(commandLine.substring(spaceIndex + 1)));
                            }
                            result.setMessage(command);
                            result.setFinished(true);
                            _buf.rpos(pos);
                            return result;
                        } else {
                            bytes.setPos(lastIndexBackslash);
                        }
                    } else if (b != '\r') {
                        bytes.write(b);
                    }
                }
            }
        }
        _buf.rpos(pos);
        return result;
    }

    private String[] parseArgs(String argStr) {
        if (StringUtil.isEmpty(argStr)) {
            return new String[0];
        }
        List<String> args = new ArrayList<>();
        argStr = argStr.trim();
        int start = 0;
        int index = argStr.indexOf(' ', start);
        while (index != -1) {
            args.add(argStr.substring(start, index));
            start = index + 1;
            for (;start<argStr.length();start++) {
                char c = argStr.charAt(start);
                if (c != ' ' && c != '\r' && c != '\t') {
                    break;
                }
            }
            index = argStr.indexOf(' ', start);
        }
        if (start < argStr.length()) {
            args.add(argStr.substring(start));
        }
        return args.toArray(new String[0]);
    }

    private int lastIndexOfBackslash(BytesBuilder bb) {
        if (bb.length() == 0) {
            return -1;
        }
        for (int i=bb.length()-1;i>=0;i--) {
            byte b = bb.get(i);
            if (b == '\\') {
                return i;
            } else if (b != '\r' && b != ' ' && b != '\t') {
                return -1;
            }
        }
        return -1;
    }

    private IAC parseIacCommand(byte b) {
        if (b < SE_VAL) {
            throw new RuntimeException("Illegal IAC Commands " + b);
        }
        switch (b) {
            case SE_VAL:
                return IAC.SE;
            case NOP_VAL:
                return IAC.NOP;
            case DM_VAL:
                return IAC.DM;
            case BREAK_VAL:
                return IAC.BREAK;
            case IP_VAL:
                return IAC.IP;
            case AO_VAL:
                return IAC.AO;
            case AYT_VAL:
                return IAC.AYT;
            case EC_VAL:
                return IAC.EC;
            case EL_VAL:
                return IAC.EL;
            case GA_VAL:
                return IAC.GA;
            case SB_VAL:
                return IAC.SB;
            case WILL_VAL:
                return IAC.WILL;
            case WONT_VAL:
                return IAC.WONT;
            case DO_VAL:
                return IAC.DO;
            case DONT_VAL:
                return IAC.DONT;
            default:
                return IAC.IAC;
        }
    }

    @Override
    public void reset() {

    }
}
