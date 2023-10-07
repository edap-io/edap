/*
 * The MIT License
 *
 * Copyright 2017 louis.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.edap.http;

import io.edap.http.codec.HttpFastBufDataRange;

/**
 *
 * @author louis
 */
public class KeyValueRanges {
    
    public final HttpFastBufDataRange[] keys;
    public final HttpFastBufDataRange[] values;
    public int length = 0;
    
    public KeyValueRanges(int capacity) {
        if (capacity < 1) {
            capacity = 1;
        }
        keys   = new HttpFastBufDataRange[capacity];
        values = new HttpFastBufDataRange[capacity];
        for (int i=0;i<capacity;i++) {
            keys[i]   = new HttpFastBufDataRange();
            values[i] = new HttpFastBufDataRange();
        }
    }
    
    public KeyValueRanges reset() {
        length = 0;
        return this;
    }
    
    public int add() {
        if (length >= keys.length) {
            throw new ArrayIndexOutOfBoundsException("KeyValueRanges length " + length);
        }
        return length++;
    }

}
