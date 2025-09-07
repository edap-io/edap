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

package io.edap.http.headervalue;

import io.edap.http.HeaderValue;
import io.edap.http.header.ContentTypeHeader;

/**
 */
public class ContentTypeValue extends HeaderValue {

    private String contentType;

    public static ContentTypeValue fromHeaderValue(HeaderValue headerValue) {
        if (headerValue instanceof ContentTypeValue) {
            return (ContentTypeValue)headerValue;
        }
        ContentTypeValue v = new ContentTypeValue(headerValue.getValue());
        String value       = v.getValue();
        int    index       = value.indexOf(";");
        String contentType;
        if (index == -1) {
            contentType = value.trim();
        } else {
            contentType = value.substring(0, index).trim();
        }
        v.setContentType(contentType);

        return v;
    }

    public ContentTypeValue(String data) {
        super(data);
        String v = data;
        int index = v.indexOf(";");
        String contentType;
        if (index == -1) {
            contentType = v.trim();
        } else {
            contentType = v.substring(0, index).trim();
        }
        this.contentType = contentType;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
