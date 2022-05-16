package io.edap.beanconvert.test.dto;

public class CarApiDTO {

    private int make;
    private int seatCount;
    private String type;

    public int getMake() {
        return make;
    }

    public void setMake(int make) {
        this.make = make;
    }

    public int getSeatCount() {
        return seatCount;
    }

    public void setSeatCount(int seatCount) {
        this.seatCount = seatCount;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
