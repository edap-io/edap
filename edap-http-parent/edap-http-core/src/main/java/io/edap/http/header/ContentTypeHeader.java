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

package io.edap.http.header;

import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.HashMap;
import java.util.Map;

public class ContentTypeHeader extends Header {

    public static String NAME = "Content-Type";

    private String  contentType;
    private Charset charset;

    private static Map<String, ContentTypeHeader> CONTENT_TYPES = new HashMap<>();

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public static ContentTypeHeader PLAIN           = from("text/plain");
    public static ContentTypeHeader HTML            = from("text/html; charset=UTF-8");
    public static ContentTypeHeader JSON            = from("application/json; charset=UTF-8");
    public static ContentTypeHeader PROTOBUF        = from("application/x-protobuf");
    public static ContentTypeHeader FORM_URLENCODED = from("application/x-www-form-urlencoded");

    public static ContentTypeHeader from(String value) {
        value = value.trim();
        ContentTypeHeader contentTypeHeader = CONTENT_TYPES.get(value);
        String            contentType;
        if (contentTypeHeader == null) {
            int index = value.indexOf(";");
            Charset charSet = null;
            if (index != -1) {
                String charsetStr = value.substring(index + 1).trim();
                contentType = value.substring(0, index).trim();
                index = charsetStr.indexOf("=");
                if (index != -1) {
                    if ("charset".equalsIgnoreCase(charsetStr.substring(0, index).trim())) {
                        String charsetName = charsetStr.substring(index + 1).trim();
                        try {
                            charSet = Charset.forName(charsetName);
                        } catch (UnsupportedCharsetException e) {

                        }
                    }
                }
            } else {
                contentType = value;
            }
            contentTypeHeader = new ContentTypeHeader(value);
            contentTypeHeader.setCharset(charSet);
            contentTypeHeader.setContentType(contentType);
            CONTENT_TYPES.put(value, contentTypeHeader);
        }

        return contentTypeHeader;
    }

    private ContentTypeHeader(String value) {
        super("Content-Type", value);
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
