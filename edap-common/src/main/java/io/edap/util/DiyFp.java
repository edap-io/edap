package io.edap.util;

public class DiyFp {
    static final int significandSize = 64;

    // IEEE double characteristics
    static final int doubleMantissaSize = 52;
    static final int doubleExponentBias = 1023 + doubleMantissaSize;
    static final long u_doubleExponentMask = 0x7FF00000_00000000L;
    static final long u_doubleMantissaMask = 0x000FFFFF_FFFFFFFFL;
    static final long u_doubleHiddenBit = 0x00100000_00000000L;

    public long u_f;
    public int e;

    DiyFp( long u_f, int e ) {

        this.u_f = u_f;
        this.e = e;
    }

    public DiyFp( double d ) {

        long u_bits = Double.doubleToLongBits( d );

        int raw_e = (int)(( u_bits & u_doubleExponentMask ) >>> doubleMantissaSize);
        long u_significand = u_bits & u_doubleMantissaMask;

        if( raw_e != 0 ) {
            // normalized
            u_f = u_significand | u_doubleHiddenBit;
            e = raw_e - doubleExponentBias;
        }
        else {
            // denormalized
            u_f = u_significand;
            e =  1 + -doubleExponentBias;
        }
    }

    DiyFp minusUlp() {

        return new DiyFp( u_f - 1, e );
    }

    DiyFp plusUlp() {

        return new DiyFp( u_f + 1, e );
    }

    static DiyFp multiply( DiyFp x, DiyFp y ) {


        return new DiyFp( u_multiplySignificands( x.u_f, y.u_f ),
                multiplyExponents( x.e, y.e ));
    }


    static DiyFp minus( DiyFp x, DiyFp y) {

        assert x.e == y.e;
        assert x.u_f >= y.u_f;

        return new DiyFp( x.u_f - y.u_f, x.e );
    }

    DiyFp normalize() {
        int s = Long.numberOfLeadingZeros( u_f );
        return new DiyFp( u_f << s, e - s );
    }

    DiyFp normalizePlus() {
        DiyFp p = new DiyFp( (u_f << 1) + 1, e - 1 );
        return p.normalize();
    }

    DiyFp normalizeMinus() {
        DiyFp m = (u_f == u_doubleHiddenBit)
                ? new DiyFp( (u_f << 2) - 1, e - 2 )
                : new DiyFp( (u_f << 1) - 1, e - 1);
        return m.normalize();
    }

    public String toString() {

        return "[ " + Long.toUnsignedString(u_f) + " * 2**" + e + " ]";
    }

    // From here on down in the hacky static native-type stuff
    private static final long u_M32 = 0xFFFFFFFFL;

    static long u_multiplySignificands( long u_x, long u_y ) {

        long u_a = u_x >>> 32;
        long u_b = u_x & u_M32;
        long u_c = u_y >>> 32;
        long u_d = u_y & u_M32;

        long u_ac = u_a * u_c;
        long u_bc = u_b * u_c;
        long u_ad = u_a * u_d;
        long u_bd = u_b * u_d;

        long u_tmp = (u_bd >>> 32) + (u_ad & u_M32) + (u_bc & u_M32) + (1L << 31);

        return u_ac + (u_ad >>> 32) + (u_bc >>> 32) + (u_tmp >>> 32);
    }

    static int multiplyExponents( int x, int y ) {

        return x + y + 64;
    }

}
