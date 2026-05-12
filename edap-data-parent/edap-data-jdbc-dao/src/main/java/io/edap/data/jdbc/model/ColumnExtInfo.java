package io.edap.data.jdbc.model;

import io.edap.data.jdbc.annotation.Jsonb;

public class ColumnExtInfo {

    private Jsonb jsonb;

    public Jsonb getJsonb() {
        return jsonb;
    }

    public void setJsonb(Jsonb jsonb) {
        this.jsonb = jsonb;
    }
}
