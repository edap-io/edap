/*
 * Copyright 2020 The edap Project
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

package io.edap.protobuf.wire;

import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;

/**
 * protocol buffer 扩展tag的结构定义
 */
public class Extensions {

    /**
     * 扩展开始的Tag编号
     */
    private int startTag;
    /**
     * 扩展结束的Tag编号
     */
    private int endTag;

    public Extensions setEndTag(int endTag) {
        this.endTag = endTag;
        return this;
    }

    public int getEndTag() {
        return endTag;
    }

    public Extensions setStartTag(int startTag) {
        this.startTag = startTag;
        return this;
    }

    public int getStartTag() {
        return startTag;
    }

    public static Extensions parseExtensions(String expression) throws ProtoParseException {
        String exp = expression.trim();
        if (exp.isEmpty()) {
            throw new ProtoParseException("extensions expression is empty");
        }
        int index = exp.indexOf("to");
        if (index < 0) {
            throw new ProtoParseException("extensions expression must be start to end");
        }
        String sstart = exp.substring(0, index).trim();
        if (sstart.isEmpty()) {
            throw new ProtoParseException("extensions expression start empty");
        }
        int start = ProtoParser.parseInt(sstart);
        if (start < 1) {
            throw new ProtoParseException("extensions expression start cann't be " + start);
        }
        String sEnd = exp.substring(index + 2).trim();
        if (sEnd.isEmpty()) {
            throw new ProtoParseException("extensions expression end empty");
        }
        int end;
        if ("max".equalsIgnoreCase(sEnd)) {
            end = WireFormat.MAX_TAG_VALUE;
        } else {
            end = ProtoParser.parseInt(sEnd);
        }
        if (end <= start) {
            throw new ProtoParseException("extensions start tag <= end tag");
        }
        Extensions ext = new Extensions();
        ext.setStartTag(start).setEndTag(end);
        return ext;
    }
}