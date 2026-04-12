package io.edap.util;

public class CachedPowers {
    static final double bits_per_10 = Math.log(2) / Math.log(10);

    static final long u_pow5[] = {

            1L, 5L, 25L, 125L, 625L, 3125L, 15625L, 78125L, 390625L, 1953125L, 9765625L,
            48828125L, 244140625L, 1220703125L, 6103515625L, 30517578125L, 152587890625L,
            762939453125L, 3814697265625L, 19073486328125L, 95367431640625L, 476837158203125L,
            2384185791015625L, 11920928955078125L, 59604644775390625L, 298023223876953125L,
            1490116119384765625L, 7450580596923828125L
    };

    static final long u_pow10[] = {

            1L, 10L, 100L, 1000L, 10000L, 100000L, 1000000L, 10000000L, 100000000L,
            1000000000L, 10000000000L, 100000000000L, 1000000000000L, 10000000000000L,
            100000000000000L, 1000000000000000L, 10000000000000000L, 100000000000000000L,
            1000000000000000000L
    };

    static final int start_ten_exp = -348;

    // Stolen from Florian Loitsch's original paper. I've double checked
    // and regenerated them from bignum multiplication and all is good.
    // Didn't really see a need to include the generation as it was in Python.
    static final long u_f[] = {

            0xfa8fd5a0_081c0288L, 0xbaaee17f_a23ebf76L,	0x8b16fb20_3055ac76L, 0xcf42894a_5dce35eaL,
            0x9a6bb0aa_55653b2dL, 0xe61acf03_3d1a45dfL,	0xab70fe17_c79ac6caL, 0xff77b1fc_bebcdc4fL,
            0xbe5691ef_416bd60cL, 0x8dd01fad_907ffc3cL,	0xd3515c28_31559a83L, 0x9d71ac8f_ada6c9b5L,
            0xea9c2277_23ee8bcbL, 0xaecc4991_4078536dL,	0x823c1279_5db6ce57L, 0xc2109436_4dfb5637L,
            0x9096ea6f_3848984fL, 0xd77485cb_25823ac7L,	0xa086cfcd_97bf97f4L, 0xef340a98_172aace5L,
            0xb23867fb_2a35b28eL, 0x84c8d4df_d2c63f3bL,	0xc5dd4427_1ad3cdbaL, 0x936b9fce_bb25c996L,
            0xdbac6c24_7d62a584L, 0xa3ab6658_0d5fdaf6L,	0xf3e2f893_dec3f126L, 0xb5b5ada8_aaff80b8L,
            0x87625f05_6c7c4a8bL, 0xc9bcff60_34c13053L,	0x964e858c_91ba2655L, 0xdff97724_70297ebdL,
            0xa6dfbd9f_b8e5b88fL, 0xf8a95fcf_88747d94L,	0xb9447093_8fa89bcfL, 0x8a08f0f8_bf0f156bL,
            0xcdb02555_653131b6L, 0x993fe2c6_d07b7facL,	0xe45c10c4_2a2b3b06L, 0xaa242499_697392d3L,
            0xfd87b5f2_8300ca0eL, 0xbce50864_92111aebL,	0x8cbccc09_6f5088ccL, 0xd1b71758_e219652cL,
            0x9c400000_00000000L, 0xe8d4a510_00000000L,	0xad78ebc5_ac620000L, 0x813f3978_f8940984L,
            0xc097ce7b_c90715b3L, 0x8f7e32ce_7bea5c70L,	0xd5d238a4_abe98068L, 0x9f4f2726_179a2245L,
            0xed63a231_d4c4fb27L, 0xb0de6538_8cc8ada8L,	0x83c7088e_1aab65dbL, 0xc45d1df9_42711d9aL,
            0x924d692c_a61be758L, 0xda01ee64_1a708deaL,	0xa26da399_9aef774aL, 0xf209787b_b47d6b85L,
            0xb454e4a1_79dd1877L, 0x865b8692_5b9bc5c2L,	0xc83553c5_c8965d3dL, 0x952ab45c_fa97a0b3L,
            0xde469fbd_99a05fe3L, 0xa59bc234_db398c25L,	0xf6c69a72_a3989f5cL, 0xb7dcbf53_54e9beceL,
            0x88fcf317_f22241e2L, 0xcc20ce9b_d35c78a5L,	0x98165af3_7b2153dfL, 0xe2a0b5dc_971f303aL,
            0xa8d9d153_5ce3b396L, 0xfb9b7cd9_a4a7443cL,	0xbb764c4c_a7a44410L, 0x8bab8eef_b6409c1aL,
            0xd01fef10_a657842cL, 0x9b10a4e5_e9913129L,	0xe7109bfb_a19c0c9dL, 0xac2820d9_623bf429L,
            0x80444b5e_7aa7cf85L, 0xbf21e440_03acdd2dL,	0x8e679c2f_5e44ff8fL, 0xd433179d_9c8cb841L,
            0x9e19db92_b4e31ba9L, 0xeb96bf6e_badf77d9L,	0xaf87023b_9bf0ee6bL
    };

    static final int e[] = {
            -1220, -1193, -1166, -1140, -1113, -1087, -1060, -1034, -1007,  -980,
            -954,  -927,  -901,  -874,  -847,  -821,  -794,  -768,  -741,  -715,
            -688,  -661,  -635,  -608,  -582,  -555,  -529,  -502,  -475,  -449,
            -422,  -396,  -369,  -343,  -316,  -289,  -263,  -236,  -210,  -183,
            -157,  -130,  -103,   -77,   -50,   -24,     3,    30,    56,    83,
            109,   136,   162,   189,   216,   242,   269,   295,   322,   348,
            375,   402,   428,   455,   481,   508,   534,   561,   588,   614,
            641,   667,   694,   720,   747,   774,   800,   827,   853,   880,
            907,   933,   960,   986,  1013,  1039,  1066
    };

    static int cacheIndexFrom2Exp( int e ) {

        double dk = -((61 + e) * bits_per_10 + start_ten_exp);
        int n = (int)(dk + 1); // TESTME: this seems like an error waiting to happen

        return n/8 + 1;
    }

    static int exponentFromIndex( int i ) {

        return -(start_ten_exp + i * 8);
    }

    // I wonder if this gets compiled to cmov ops? Not sure if it would
    // be beneficial or not...
    // NOTE: compiled to all branches w/ some str reduction
    protected static int numUnsignedIntDigits( int u_x ) {

        int n = 1;

        if( Integer.compareUnsigned( u_x, 100_000_000 ) >= 0 ) {
            u_x = (u_x >>> 8) / 390625; // x / 2**8 / 5**8
            n += 8;
        }

        // after here, we are guaranteed that u_x can no longer have a high bit.
        if( u_x >= 10_000 ) {
            u_x /= 10_000;
            n += 4;
        }

        if( u_x >= 100 ) {
            u_x /= 100;
            n += 2;
        }

        if( u_x >= 10 ) {
            u_x /= 10;
            n += 1;
        }

        return n;
    }

    protected static int numUnsignedLongDigits( long u_x ) {

        int n = 1;

        if( Long.compareUnsigned( u_x, 10_000_000_000_000_000L) >= 0 ) {
            u_x = (u_x >>> 16) / 152587890625L;  // x/10^16 = x/2^16/5^16
            n += 16;
        }

        // after here, we are guaranteed that u_x can no longer have a high bit.
        if( u_x >= 100_000_000L ) {
            u_x /= 100_000_000L;
            n += 8;
        }

        if( u_x >= 10_000L ) {
            u_x /= 10_000L;
            n += 4;
        }

        if( u_x >= 100L ) {
            u_x /= 100L;
            n += 2;
        }

        if( u_x >= 10L ) {
            u_x /= 10L;
            n += 1;
        }

        return n;
    }

}
