package io.edap.data.jdbc.model;

import io.edap.data.jdbc.annotation.Geometry;
import io.edap.data.jdbc.annotation.Inet;
import io.edap.data.jdbc.annotation.Jsonb;
import io.edap.data.jdbc.annotation.TypeConvertor;

public class ColumnExtInfo {

    private Jsonb jsonb;
    private Inet inet;
    private TypeConvertor typeConvertor;
    private Geometry geometry;

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

    public Inet getInet() {
        return inet;
    }

    public void setInet(Inet inet) {
        this.inet = inet;
    }

    public Geometry getGeometry() {
        return geometry;
    }

    public void setGeometry(Geometry geometry) {
        this.geometry = geometry;
    }
}
