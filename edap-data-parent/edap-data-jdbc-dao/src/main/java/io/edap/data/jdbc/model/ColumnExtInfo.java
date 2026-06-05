package io.edap.data.jdbc.model;

import io.edap.data.jdbc.annotation.Jsonb;
import io.edap.data.jdbc.annotation.TypeConvertor;

public class ColumnExtInfo {

    private Jsonb jsonb;
    private TypeConvertor typeConvertor;

    public Jsonb getJsonb() {
        return jsonb;
    }

    public void setJsonb(Jsonb jsonb) {
        this.jsonb = jsonb;
    }

    public TypeConvertor getTypeConvertor() {
        return typeConvertor;
    }

    public void setTypeConvertor(TypeConvertor typeConvertor) {
        this.typeConvertor = typeConvertor;
    }
}
