/*
 * Copyright 2021 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.log;

import java.util.function.Function;
import java.util.function.Supplier;

public class Args {

    private Args() {}

    /**
     *  Forms a supplier of the given object reference.
     *
     *  @param <T> Value type.
     *  @param value The value to supply.
     *  @return A {@link Supplier} of the given value.
     */
    public static <T> Supplier<T> ref(T value) {
        return () -> value;
    }

    /**
     *  Maps the value returned by the given supplier to any type via the given transform.
     *
     *  @param <T> Value type.
     *  @param supplier The source of the value to transform.
     *  @param transform The transform function.
     *  @return A {@link Supplier} that will perform the transform when invoked.
     */
    public static <T> Supplier<?> map(Supplier<? extends T> supplier, Function<? super T, ?> transform) {
        return () -> transform.apply(supplier.get());
    }
}
