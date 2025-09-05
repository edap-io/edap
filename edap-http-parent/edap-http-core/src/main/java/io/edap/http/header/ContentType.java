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
import java.util.Locale;
import java.util.Map;

public class ContentType extends Header {

    public static String NAME = "Content-Type";

    private static Map<String, ContentType> CONTENT_TYPES = new HashMap<>();

    public ValueEnum getValueEnum() {
        return valueEnum;
    }

    public void setValueEnum(ValueEnum valueEnum) {
        this.valueEnum = valueEnum;
    }

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public enum ValueEnum {
        JSON("application/json"),
        PLAIN("text/plain"),
        HTML("text/html"),
        PROTOBUF("application/x-protobuf"),
        FORM_URLENCODED("application/x-www-form-urlencoded"),
        FORM_DATA("multipart/form-data"),
        UNKNOWN("UNKNOWN");

        private String value;

        ValueEnum(String value) {
            this.value = value;
        }

        public static ValueEnum from(String value) {
            String v = value.toLowerCase(Locale.ENGLISH);
            switch (v) {
                case "application/json":
                    return JSON;
                case "text/plain":
                    return PLAIN;
                case "text/html":
                    return HTML;
                case "application/x-protobuf":
                    return PROTOBUF;
                case "application/x-www-form-urlencoded":
                    return FORM_URLENCODED;
                case "multipart/form-data":
                    return FORM_DATA;
                default:
                    return UNKNOWN;
            }
        }
    }

    public static ContentType PLAIN    = from("text/plain");
    public static ContentType HTML     = from("text/html; charset=UTF-8");
    public static ContentType JSON     = from("application/json; charset=UTF-8");
    public static ContentType PROTOBUF = from("application/x-protobuf");

    private ValueEnum valueEnum;
    private Charset charset;

    public static ContentType from(String value) {
        ContentType contentType = CONTENT_TYPES.get(value);
        if (contentType == null) {
            contentType = new ContentType(value);
            int index = value.indexOf(";");
            String valueEnumStr;
            Charset charSet = Charset.forName("UTF-8");
            if (index != -1) {
                valueEnumStr = value.substring(0, index).trim().toLowerCase(Locale.ENGLISH);
                String charsetStr = value.substring(index + 1).trim();
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
                valueEnumStr = value.trim().toLowerCase(Locale.ENGLISH);
            }
            contentType.setValueEnum(ValueEnum.from(valueEnumStr));
            contentType.setCharset(charSet);
            CONTENT_TYPES.put(value, contentType);
        }

        return contentType;
    }

    private ContentType(String value) {
        super("Content-Type", value);
    }
}
