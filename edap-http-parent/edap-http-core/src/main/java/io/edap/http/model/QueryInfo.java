package io.edap.http.model;

import java.util.List;

import static io.edap.http.HttpConsts.DEFAULT_CHARSET;

public class QueryInfo {
    private String query;
    private byte[] queryBytes;

    public String getQuery() {
        if (query == null) {
            query = new String(queryBytes, DEFAULT_CHARSET);
        }
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public byte[] getQueryBytes() {
        return queryBytes;
    }

    public void setQueryBytes(byte[] queryBytes) {
        this.queryBytes = queryBytes;
    }
}
