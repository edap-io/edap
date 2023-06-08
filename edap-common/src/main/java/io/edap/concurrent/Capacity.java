package io.edap.concurrent;

public class Capacity {

    /**
     * 最大容量
     */
    public static final int MAX_POWER2 = (1<<30);

    /**
     * 返回不小于指定容量并且为2的n次方的整数
     */
    public static int getCapacity(int capacity) {
        int c = 1;
        if(capacity >= MAX_POWER2) {
            c = MAX_POWER2;
        } else {
            while (c < capacity) {
                c <<= 1;
            }
        }

        if(isPowerOf2(c)) {
            return c;
        } else {
            throw new RuntimeException("Capacity is not a power of 2.");
        }
    }

    /*
     * 判断一个整数是否为2的n次方
     */
    private static final boolean isPowerOf2(final int p) {
        // thanks mcheng for the suggestion
        return (p & (p - 1)) == 0;
    }
}
