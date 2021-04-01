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

import java.util.function.*;

public interface LogArgs {

    LogArgs arg(boolean arg);
    LogArgs arg(byte arg);
    LogArgs arg(char arg);
    LogArgs arg(short arg);
    LogArgs arg(float arg);
    LogArgs arg(int arg);
    LogArgs arg(long arg);
    LogArgs arg(double arg);
    LogArgs arg(String arg);
    LogArgs arg(Object arg);


    LogArgs arg(BooleanSupplier supplier);
    LogArgs arg(IntSupplier supplier);
    LogArgs arg(LongSupplier supplier);
    LogArgs arg(DoubleSupplier supplier);
    LogArgs arg(Supplier<?> supplier);

    LogArgs threw(Throwable cause);
    LogArgs message(Object message);
    LogArgs format(String format);
    int level();
    void reset();
}
