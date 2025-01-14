package io.edap.util;

public class StuffedPair {
    // In honor of R4RS, my first language :)
    public static long cons( int x, int y ) {

        long u_x = (long)x << 32;
        long u_y = (long)y & 0xffffffffL;

        return u_x | u_y;
    }

    public static int car( long stuffed ) {

        return (int)(stuffed >>> 32);
    }

    public static int cdr( long stuffed ) {

        return (int)stuffed;
    }

    public static String toString( long stuffed ) {

        return "(" + car( stuffed ) + ", " + cdr ( stuffed ) + ")";
    }
}
