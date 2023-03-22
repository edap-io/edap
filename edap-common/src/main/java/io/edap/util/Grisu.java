package io.edap.util;

import static io.edap.util.DiyFp.*;

public class Grisu {
    /**
     * The default formatter with 16 {@code max_int_digits}, 10 {@code max_frac_digits},
     * and using little 'e' for the {@code exp_char}.
     */
    public static final Grisu fmt = new Grisu( 16, 10, 'e' );

    // NOTE: get rid of these and pass them into the method? Or maybe passing
    // them in should override these?
    public final int max_int_digits;
    public final int max_frac_digits;
    public final byte exp_char;

    /**
     * Grisu2 is capable of printing out {@value #max_grisu_precision} digits
     * of total precision with the 64 but longs. A longer long would allow more.
     */
    static final int max_grisu_precision = 17;

    /**
     * The longest printed representation is {@value #longest_double_output}. As
     * long as {@code max_int_digits} is less than {@value #max_grisu_precision}
     * this holds, plus 1 for minus sign, plus 1 for the decimal point, plus 5
     * for the exponent (+/-, e, 3 digits).
     */
    public static final int longest_double_output = max_grisu_precision + 1 + 1 + 5;

    protected static final byte[] nan_text = "NaN".getBytes();
    protected static final byte[] inf_text = "Infinity".getBytes();
    protected static final byte[] zero_text = "0.0".getBytes();

    /**
     * ByteArray holder for per thread scratch space
     */
    private static class ByteArray {
        public byte[] buffer = new byte[longest_double_output];
        public char[] charb = new char[longest_double_output];
    }

    private ThreadLocal<ByteArray> tlBuffers = new ThreadLocal<ByteArray>() {
        @Override protected ByteArray initialValue() {
            return new ByteArray();
        }
    };

    /**
     * Create a formatter with a set of defaults.
     *
     * @param max_int_digits
     * @param max_frac_digits
     * @param exp_char
     */
    public Grisu( int max_int_digits, int max_frac_digits, char exp_char ) {

        this.max_int_digits = max_int_digits;
        this.max_frac_digits = max_frac_digits;
        this.exp_char = (byte)exp_char;
    }

    /**
     * Prints the double floating point value into a {@link #String}. Underneath it
     * calls {@link #doubleToBytes(byte[], int, double)} with a newly allocated
     * temporary buffer and then news off a String from that.
     *
     * @param value The double value
     * @return The printed representation
     */
    public String doubleToString( double value ) {

        ByteArray buf = tlBuffers.get();
        int len = doubleToBytes( buf.buffer, 0, value );

        return new String( toChars( buf, len ), 0, len );
    }

    protected static char[] toChars( ByteArray buf, int len ) {

        for( int i = 0; i < len; ++i )
            buf.charb[i] = (char)buf.buffer[i];

        return buf.charb;
    }


    /**
     * Will print the specific double value to the buffer starting at offset using
     * the Grisu2 algorithm described by Florian Loitsch in
     * <a href="http://florian.loitsch.com/publications"><i>Printing Floating-Point
     * Numbers Quickly and Accurately with Integers</i></a>.
     * It gives the shortest correct result that will round trip about 99.8% of the
     * time and a correct one, but not the shortest, the rest of the time.
     * <p>
     * No garbage is generate in the call.
     *
     * @param buffer A buffer that has at least {@value #longest_double_output}
     * more bytes allocated. This isn't checked so you can get away with less,
     * if you are sure if will fit.
     *
     * @param boffset Where to begin writing.
     */
    public int doubleToBytes( byte[] buffer, int boffset, double value ) {

        // unpack double
        long u_vbits = Double.doubleToRawLongBits( value );

        boolean visneg = (u_vbits >>> 63) == 1;
        int ve = (int)((u_vbits & u_doubleExponentMask) >>> doubleMantissaSize);
        long u_vf = u_vbits & u_doubleMantissaMask;

        // all ones in the exponent means this is special.
        // Get the special cases out of the way: NaN, infinities, zero(s)
        if( ve == (int) (u_doubleExponentMask >>> doubleMantissaSize) ) {

            if( u_vf != 0 ) {

                System.arraycopy( nan_text, 0, buffer, boffset, nan_text.length );
                return nan_text.length;
            }
            else if( visneg ) {

                buffer[boffset] = '-';
                System.arraycopy( inf_text, 0, buffer, boffset + 1, inf_text.length );
                return 1 + inf_text.length;
            }
            else {

                System.arraycopy( inf_text, 0, buffer, boffset, inf_text.length );
                return inf_text.length;
            }
        }

        // denormalize normals and fix exponent bias
        if( ve != 0 ) {
            // normalized, add the implied bit back on
            u_vf |= u_doubleHiddenBit;
            ve -= doubleExponentBias;
        }
        else {
            // zero
            if( u_vf == 0 ) {

                // NOTE: Should I bother with +/-0.0 distinctions?
                System.arraycopy( zero_text, 0, buffer, boffset, zero_text.length );
                return zero_text.length;
            }
            // denormalized
            ve =  1 - doubleExponentBias;
        }

        // So we have a number to stringify now
        int pos = 0;
        if( visneg ) { // NOTE: value < 0 was forcing hotspot to mix mmx instrs, perf impact?

            buffer[boffset + pos] = '-';
            pos = 1;
        }

        long u_lenpow = u_quickpath( buffer, boffset + pos, u_vf, ve );
        if( u_lenpow == 0 )
            u_lenpow = u_grisu2( buffer, boffset + pos, u_vf, ve );

        return pos + formatBuffer( buffer, boffset + pos, StuffedPair.car( u_lenpow ), StuffedPair.cdr( u_lenpow ));
    }

    static int qps = 0;
    protected static long u_quickpath( byte[] buffer, int boffset, long u_vf, int ve ) {

        int leadingzeros = Long.numberOfLeadingZeros( u_vf );
        int trailingzeros = Long.numberOfTrailingZeros( u_vf );
        int middlebits = Double.SIZE - leadingzeros - trailingzeros;

        // for us bias includes the mantissa bits, so our radix piont is the
        // far right of all the bits -- not 1.1101, but 11101(.0)

        if( ve >= 0 || -ve <= trailingzeros ) {

            // it is an integer, but is it a small enough integer to fit in a long
            if( middlebits + trailingzeros + ve <= Long.SIZE ) {

                long u_vinteger = ve >= 0 ? u_vf << ve : u_vf >>> -ve;
                return u_printLong( buffer, boffset, u_vinteger );
            }
        }

        return 0;
    }

    protected static long u_printLong( byte[] buffer, int boffset, long u_vinteger ) {

        assert u_vinteger != 0;

        int ndigits = CachedPowers.numUnsignedLongDigits( u_vinteger );
        int rounded = 0;

        for (; ndigits > max_grisu_precision; ndigits--, rounded++) {
            long u_vinteger_div10 = (u_vinteger >>> 1) / 5;
            int u_vinteger_mod10 = (int) (u_vinteger - u_vinteger_div10 * 10);

            u_vinteger = u_vinteger_div10;
            if (u_vinteger_mod10 >= 5) {
                u_vinteger++;
            }
        }

        for( int i = 0; i < ndigits; ++i ) {

            // NOTE: Long.remainderUnsigned() uses BigInteger
            long u_vinteger_div10 = (u_vinteger >>> 1) / 5;
            int u_vinteger_mod10 = (int)(u_vinteger - u_vinteger_div10 * 10);

            buffer[boffset + ndigits - i - 1] = (byte)('0' + u_vinteger_mod10);

            u_vinteger = u_vinteger_div10;
        }

        return StuffedPair.cons(ndigits, rounded);
    }

    protected static void round( byte[] buffer, int pos, long u_delta, long u_rest, long  u_onef, long u_winf ) {

        while (Long.compareUnsigned( u_rest, u_winf ) < 0
                && Long.compareUnsigned( u_delta - u_rest, u_onef ) >= 0
                && (Long.compareUnsigned( u_rest + u_onef, u_winf ) < 0
                || Long.compareUnsigned( u_winf - u_rest, u_rest + u_onef - u_winf ) > 0)) {

            buffer[pos - 1]--;
            u_rest += u_onef;
        }
    }

    protected static long u_digitGen( long u_vf, int ve, long u_pf, int pe, long u_delta, byte[] buffer, int boffset, int base10exp ) {

        long u_onef = 1L << -pe;
        long u_fracMask = u_onef - 1;
        long u_winf = u_pf - u_vf;

        // NOTE: We overflowing slightly into the long on a few occasions (eg 502973).

        // Grab the integral and fractional parts.
        long u_intpart = u_pf >>> -ve;
        long u_fracpart = u_pf & u_fracMask;

        int digits = CachedPowers.numUnsignedLongDigits( u_intpart );
        int pos = boffset;

        // Write the integer part.
        while( digits > 0 ) {

            // n div 10^x = n div 2^x div 5^x
            // can't just divide because of sign bit
            long pow10 = CachedPowers.u_pow10[digits - 1];
            int u_dig = (int)((u_intpart >>> digits - 1) / (pow10 >>> digits - 1));

            u_intpart = u_intpart - (u_dig * pow10);

            // no leading zeros
            if( !(pos == boffset && u_dig == 0) )
                buffer[pos++] = (byte)('0' + u_dig);

            long u_more = (u_intpart << -pe) + u_fracpart;
            digits--;

            // No use going any further, so truncate it off and round.
//            assert u_more >= 0;
            if( u_delta < 0 || u_more <= u_delta ) {
//            if( Long.compareUnsigned( u_more, u_delta ) <= 0 ) {

                base10exp += digits;
                round( buffer, pos, u_delta, u_more, CachedPowers.u_pow10[digits] << -pe, u_winf );
                return StuffedPair.cons( pos - boffset, base10exp );
            }
        }

        // Write the fractional part
        for (;;) {

            u_fracpart *= 10;
            u_delta *= 10;

            int u_dig = (int)(u_fracpart >>> -pe);

            // no leading zeros
            if( !(pos == boffset && u_dig == 0) )
                buffer[pos++] = (byte)('0' + u_dig);

            u_fracpart &= u_fracMask;
            digits--;

            // no use going any further. Trunc and round as above.
            if (Long.compareUnsigned(u_fracpart, u_delta) <= 0) {

                base10exp += digits;
                round(buffer, pos, u_delta, u_fracpart, u_onef, u_winf * CachedPowers.u_pow10[-digits]);
                return StuffedPair.cons( pos - boffset, base10exp );
            }
        }
    }

    protected static long u_grisu2( byte[] buffer, int boffset, long u_vf, int ve ) {

        // calculate the lower and upper bounds
        int shiftval = u_vf == u_doubleHiddenBit ? 2 : 1; // is 2^n, then shift another bit
        long u_mf = (u_vf << shiftval) - 1;
        int me = ve - shiftval;

        long u_pf = (u_vf << 1) + 1;
        int pe = ve - 1;

        // normalize the three ersatz floats
        shiftval = Long.numberOfLeadingZeros( u_vf );
        u_vf <<= shiftval;
        ve -= shiftval;

        shiftval = Long.numberOfLeadingZeros( u_mf );
        u_mf <<= shiftval;
        me -= shiftval;

        shiftval = Long.numberOfLeadingZeros( u_pf );
        u_pf <<= shiftval;
        pe -= shiftval;

        // Find the correct cached power of 10 in ersatz float representation
        int index = CachedPowers.cacheIndexFrom2Exp( ve );
        int base10exp = CachedPowers.exponentFromIndex( index );
        long u_powf = CachedPowers.u_f[index];
        int powe = CachedPowers.e[index];

        // multiple the value and the window by the cached approximation
        u_vf = DiyFp.u_multiplySignificands( u_powf, u_vf );
        ve = DiyFp.multiplyExponents( powe, ve );

        u_mf = DiyFp.u_multiplySignificands( u_powf, u_mf );
        me = DiyFp.multiplyExponents( powe, me );

        u_pf = DiyFp.u_multiplySignificands( u_powf, u_pf );
        pe = DiyFp.multiplyExponents( powe, pe );

        return u_digitGen(u_vf, ve, u_pf, pe, u_pf - u_mf, buffer, boffset, base10exp);
    }

    protected int appendExponent(byte[] buffer, int boffset, int base10exp ) {

        int pos = boffset;

        buffer[pos++] = exp_char;

        if (base10exp < 0) {

            buffer[pos++] = '-';
            base10exp = -base10exp;
        }
        else {

            buffer[pos++] = '+';
        }

        if (base10exp >= 100) {

            buffer[pos] = (byte)('0' + (base10exp / 100));
            base10exp %= 100;
            buffer[pos + 1] = (byte)('0' + (base10exp / 10));
            buffer[pos + 2] = (byte)('0' + (base10exp % 10));
            pos += 3;
        }
        else if (base10exp >= 10) {

            buffer[pos] = (byte)('0' + (base10exp / 10));
            buffer[pos + 1] = (byte)('0' + (base10exp % 10));
            pos += 2;
        }
        else {

            buffer[pos] = (byte)('0' + base10exp);
            pos += 1;
        }

        return pos - boffset;
    }

    /**
     *
     * Grisu just gives you a string of numbers and a base 10 exponent.
     * This makes it easier to read (e.g, 0.1 isn't rendered as 1 * 10^-1)
     * This is conceptually simple, but intricate in practice, especially when
     * trying to write into a buffer at a offset.
     * This only write positive values. It is assumed the negative sign will
     * be taken care of elsewhere.
     * returns the final length of the formatted number
     * @param buffer The buffer {@link #u_grisu2(byte[], int, double)} printed into.
     * @param boffset The start position of the digits. The same argument to {@link #u_grisu2(byte[], int, double)}.
     * @param blen The length of the digit string returned from {@link #u_grisu2(byte[], int, double)}
     * @param exp The exponent returned from {@link #u_grisu2(byte[], int, double)}
     * @return The final length of the formatted number
     */
    protected int formatBuffer( byte[] buffer, int boffset, int blen, int exp ) {

        int givendigits = blen;

        // This is going to get ugly. SESE-minded people should avert their eyes.
        if( exp >= 0 ) {

            int totaldigits = givendigits + exp;

            if( totaldigits <= max_int_digits ) {

                // easiest, just extend zeros, if any, and append zero fraction
                // 12e0 -> 12.0 and 432e1 -> 4320.0
                for( ; givendigits < totaldigits; ++givendigits )
                    buffer[boffset + givendigits] = '0';

                buffer[boffset + totaldigits] = '.';
                buffer[boffset + totaldigits + 1] = '0';

                return totaldigits + 2;
            }

            else {
                // These will all be large numbers:
                // 123456789e0 -> 1.23456789e8 and 123e10 -> 1.23e12
                int elen;
                if( givendigits == 1 ) {
                    // 1e10 -> 1e10
                    elen = appendExponent( buffer, boffset + givendigits, exp );
                }
                else {
                    // 123e10 -> 1.23e12
                    System.arraycopy( buffer, boffset + 1, buffer, boffset + 2, givendigits );
                    buffer[boffset + 1] = '.';
                    blen += 1; // now we have a '.' in the number
                    elen = appendExponent( buffer, boffset + blen, exp + givendigits - 1 );
                }

                return blen + elen;
            }
        }
        else {
            // From here on down, we have negative exponents

            int fracdigits = -exp;
            int intdigits = Math.max( givendigits - fracdigits, 0 );
            int totaldigits = intdigits + fracdigits;

            if( intdigits > 0 && intdigits <= max_int_digits ) {

                // 12345e-2 -> 123.45
                System.arraycopy( buffer, boffset + intdigits, buffer, boffset + intdigits + 1, fracdigits );
                buffer[boffset + intdigits] = '.';

                return givendigits + 1; // just inserted a dot
            }

            else if( totaldigits <= max_frac_digits ) {

                // 12345e-5 -> 0.12345 and 12e-3 -> 0.012
                int leadingzeros = fracdigits - givendigits;
                int leadingspace = leadingzeros + 2;

                System.arraycopy( buffer, boffset, buffer, boffset + leadingspace, givendigits );

                buffer[boffset] = '0';
                buffer[boffset + 1] = '.';
                for( int i = 0; i < leadingzeros; ++i )
                    buffer[boffset + 2 + i] = '0';

                return leadingspace + givendigits;
            }
            else {

                if( givendigits == 1 ) {
                    int explen = appendExponent( buffer, boffset + givendigits, exp );
                    return boffset + givendigits + explen;
                }
                else {

                    // These are all exponential form at least `precision` digits long or
                    // with at least `precision` integer digits.
                    // 123456789e-2 -> 1.23456789e+6 and 1234e-20 -> 1.234e-17
                    System.arraycopy( buffer, boffset + 1, buffer, boffset + 2, givendigits - 1 );
                    buffer[boffset + 1] = '.';

                    int newexp = exp + givendigits - 1;
                    int explen = appendExponent( buffer, boffset + givendigits + 1, newexp );

                    return 1 + givendigits + explen;
                }
            }
        }
        // Why is there no way to suppress unreachable code ERRORS? Sometimes it is needed
        //assert false : "Unreachable";
    }
}
