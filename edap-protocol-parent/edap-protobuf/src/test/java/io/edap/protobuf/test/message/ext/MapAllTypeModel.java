package io.edap.protobuf.test.message.ext;

import io.edap.protobuf.test.message.v3.Project;

import java.util.Map;

public class MapAllTypeModel {

    private long pk;

    private Map<Byte,      Project> byteKey;
    private Map<Boolean,   Project> boolKey;
    private Map<Character, Project> charKey;
    private Map<Short,     Project> shortKey;
    private Map<Integer,   Project> intKey;
    private Map<Long,      Project> longKey;
    private Map<Float,     Project> floatKey;
    private Map<Double,    Project> doubleKey;

    private Map<String, Byte     > byteVal;
    private Map<String, Boolean  > boolVal;
    private Map<String, Character> charVal;
    private Map<String, Short    > shortVal;
    private Map<String, Integer  > intVal;
    private Map<String, Long     > longVal;
    private Map<String, Float    > floatVal;
    private Map<String, Double   > doubleVal;

    public long getPk() {
        return pk;
    }

    public void setPk(long pk) {
        this.pk = pk;
    }

    public Map<Byte, Project> getByteKey() {
        return byteKey;
    }

    public void setByteKey(Map<Byte, Project> byteKey) {
        this.byteKey = byteKey;
    }

    public Map<Boolean, Project> getBoolKey() {
        return boolKey;
    }

    public void setBoolKey(Map<Boolean, Project> boolKey) {
        this.boolKey = boolKey;
    }

    public Map<Character, Project> getCharKey() {
        return charKey;
    }

    public void setCharKey(Map<Character, Project> charKey) {
        this.charKey = charKey;
    }

    public Map<Short, Project> getShortKey() {
        return shortKey;
    }

    public void setShortKey(Map<Short, Project> shortKey) {
        this.shortKey = shortKey;
    }

    public Map<Integer, Project> getIntKey() {
        return intKey;
    }

    public void setIntKey(Map<Integer, Project> intKey) {
        this.intKey = intKey;
    }

    public Map<Long, Project> getLongKey() {
        return longKey;
    }

    public void setLongKey(Map<Long, Project> longKey) {
        this.longKey = longKey;
    }

    public Map<Float, Project> getFloatKey() {
        return floatKey;
    }

    public void setFloatKey(Map<Float, Project> floatKey) {
        this.floatKey = floatKey;
    }

    public Map<Double, Project> getDoubleKey() {
        return doubleKey;
    }

    public void setDoubleKey(Map<Double, Project> doubleKey) {
        this.doubleKey = doubleKey;
    }

    public Map<String, Byte> getByteVal() {
        return byteVal;
    }

    public void setByteVal(Map<String, Byte> byteVal) {
        this.byteVal = byteVal;
    }

    public Map<String, Boolean> getBoolVal() {
        return boolVal;
    }

    public void setBoolVal(Map<String, Boolean> boolVal) {
        this.boolVal = boolVal;
    }

    public Map<String, Character> getCharVal() {
        return charVal;
    }

    public void setCharVal(Map<String, Character> charVal) {
        this.charVal = charVal;
    }

    public Map<String, Short> getShortVal() {
        return shortVal;
    }

    public void setShortVal(Map<String, Short> shortVal) {
        this.shortVal = shortVal;
    }

    public Map<String, Integer> getIntVal() {
        return intVal;
    }

    public void setIntVal(Map<String, Integer> intVal) {
        this.intVal = intVal;
    }

    public Map<String, Long> getLongVal() {
        return longVal;
    }

    public void setLongVal(Map<String, Long> longVal) {
        this.longVal = longVal;
    }

    public Map<String, Float> getFloatVal() {
        return floatVal;
    }

    public void setFloatVal(Map<String, Float> floatVal) {
        this.floatVal = floatVal;
    }

    public Map<String, Double> getDoubleVal() {
        return doubleVal;
    }

    public void setDoubleVal(Map<String, Double> doubleVal) {
        this.doubleVal = doubleVal;
    }
}
