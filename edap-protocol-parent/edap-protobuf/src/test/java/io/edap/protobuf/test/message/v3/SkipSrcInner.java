package io.edap.protobuf.test.message.v3;

public class SkipSrcInner {
    private int valInt;
    private int valFixed32;

    private long valFixed64;

    private float valFloat;

    private double valDouble;

    private int[] valIntArray;

    private Project project;
    private String valStr;

    private Object valObj;

    public int getValInt() {
        return valInt;
    }

    public void setValInt(int valInt) {
        this.valInt = valInt;
    }

    public int getValFixed32() {
        return valFixed32;
    }

    public void setValFixed32(int valFixed32) {
        this.valFixed32 = valFixed32;
    }

    public long getValFixed64() {
        return valFixed64;
    }

    public void setValFixed64(long valFixed64) {
        this.valFixed64 = valFixed64;
    }

    public float getValFloat() {
        return valFloat;
    }

    public void setValFloat(float valFloat) {
        this.valFloat = valFloat;
    }

    public double getValDouble() {
        return valDouble;
    }

    public void setValDouble(double valDouble) {
        this.valDouble = valDouble;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public String getValStr() {
        return valStr;
    }

    public void setValStr(String valStr) {
        this.valStr = valStr;
    }

    public Object getValObj() {
        return valObj;
    }

    public void setValObj(Object valObj) {
        this.valObj = valObj;
    }

    public int[] getValIntArray() {
        return valIntArray;
    }

    public void setValIntArray(int[] valIntArray) {
        this.valIntArray = valIntArray;
    }
}
