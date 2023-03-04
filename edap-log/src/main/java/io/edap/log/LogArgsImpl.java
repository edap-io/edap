/*
 * Copyright 2022 The edap Project
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

import static io.edap.log.Logger.MAX_ARGS;

/**
 * @author : louis@easyea.com
 * {@code @date} : 2022/12/08
 */
public class LogArgsImpl implements LogArgs {

    public static final class TooManyArgsException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        TooManyArgsException(String m) { super(m); }
    }

    public static final class DuplicateValueException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        DuplicateValueException(String m) { super(m); }
    }

    private int level;
    private String format;
    private int argc;
    private final Object[] argv = new Object[MAX_ARGS];
    private Throwable throwable;

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    @Override
    public void reset() {
        setFormat(null);
        for (int i = 0; i < argc; i++) {
            argv[i] = null;
        }
        argc = 0;
        setThrowable(null);
    }

    public LogArgs level(int level) {
        this.level = level;
        return this;
    }

    @Override
    public LogArgs arg(boolean arg) {
        return appendArg(arg);
    }

    @Override
    public LogArgs arg(byte arg) {
        return appendArg(arg);
    }

    @Override
    public LogArgs arg(char arg) {
        return appendArg(arg);
    }

    @Override
    public LogArgs arg(short arg) {
        return appendArg(arg);
    }

    @Override
    public LogArgs arg(float arg) {
        return appendArg(arg);
    }

    @Override
    public LogArgs arg(int arg) {
        return appendArg(arg);
    }

    @Override
    public LogArgs arg(long arg) {
        return appendArg(arg);
    }

    @Override
    public LogArgs arg(double arg) {
        return appendArg(arg);
    }

    @Override
    public LogArgs arg(String arg) {
        return appendArg(arg);
    }

    @Override
    public LogArgs arg(Object arg) {
        return appendArg(arg);
    }

    @Override
    public LogArgs arg(BooleanSupplier supplier) {
        return appendArg(supplier.getAsBoolean());
    }

    @Override
    public LogArgs arg(IntSupplier supplier) {
        return appendArg(supplier.getAsInt());
    }

    @Override
    public LogArgs arg(LongSupplier supplier) {
        return appendArg(supplier.getAsLong());
    }

    @Override
    public LogArgs arg(DoubleSupplier supplier) {
        return appendArg(supplier.getAsDouble());
    }

    @Override
    public LogArgs arg(Supplier<?> supplier) {
        return appendArg(supplier.get());
    }

    @Override
    public LogArgs threw(Throwable cause) {
        if (this.getThrowable() != null) {
            throw new DuplicateValueException("Duplicate call to threw()");
        }
        this.setThrowable(cause);
        return this;
    }

    @Override
    public LogArgs message(Object message) {
        return format("{}").arg(message);
    }

    @Override
    public LogArgs format(String format) {
        if (this.getFormat() != null) {
            throw new DuplicateValueException("Duplicate call to format()");
        }
        this.setFormat(format);
        return this;
    }

    private LogArgs appendArg(Object arg) {
        if (argc == MAX_ARGS) {
            throw new TooManyArgsException("Number of args cannot exceed " + MAX_ARGS);
        }
        argv[argc++] = arg;
        return this;
    }

    public int level() {
        return this.level;
    }

    public Object[] getArgv() {
        return argv;
    }

    public int getArgc() {
        return argc;
    }
}
