package io.edap.http.headervalue;

import io.edap.http.HeaderValue;
import io.edap.http.header.ContentType;

/**
 */
public class ContentTypeValue extends HeaderValue {

    private ContentType.ValueEnum contentType;

    public static ContentTypeValue fromHeaderValue(HeaderValue headerValue) {
        if (headerValue instanceof ContentTypeValue) {
            return (ContentTypeValue)headerValue;
        }
        ContentTypeValue v = new ContentTypeValue(headerValue.getData());
        String value = v.getValue();
        int index = value.indexOf(";");
        ContentType.ValueEnum contentType;
        if (index == -1) {
            contentType = ContentType.ValueEnum.from(value.trim());
        } else {
            contentType = ContentType.ValueEnum.from(value.substring(0, index).trim());
        }
        v.setContentType(contentType);

        return v;
    }

    public ContentTypeValue(byte[] data) {
        super(data);
    }

    public ContentType.ValueEnum getContentType() {
        return contentType;
    }

    public void setContentType(ContentType.ValueEnum contentType) {
        this.contentType = contentType;
    }
}
