/*
 * Copyright 2023 The edap Project
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

package io.edap.protocol.telnet.test;

import io.edap.nio.util.BytesBuilder;
import io.edap.protocol.telnet.IAC;
import io.edap.protocol.telnet.IACCommand;
import io.edap.protocol.telnet.TelnetDecoder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestTelnetDecoder {

    @Test
    public void testLastIndexOfBackslash() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        TelnetDecoder decoder = new TelnetDecoder();
        BytesBuilder bb = new BytesBuilder();

        Method method = TelnetDecoder.class.getDeclaredMethod("lastIndexOfBackslash", BytesBuilder.class);
        method.setAccessible(true);

        int index = (int)method.invoke(decoder, bb);
        assertEquals(-1, index);

        bb.write("./configure --prefix=/usr/local \\".getBytes());
        index = (int)method.invoke(decoder, bb);
        assertEquals(32, index);

        bb.setPos(0);
        bb.write("./configure --prefix=/usr/local \\a".getBytes());
        index = (int)method.invoke(decoder, bb);
        assertEquals(-1, index);

        bb.setPos(0);
        bb.write("\r\t ".getBytes());
        index = (int)method.invoke(decoder, bb);
        assertEquals(-1, index);

        bb.setPos(0);
        bb.write("./configure --prefix=/usr/local \\\r".getBytes());
        index = (int)method.invoke(decoder, bb);
        assertEquals(32, index);

        bb.setPos(0);
        bb.write("./configure --prefix=/usr/local \\\t".getBytes());
        index = (int)method.invoke(decoder, bb);
        assertEquals(32, index);

        bb.setPos(0);
        bb.write("./configure --prefix=/usr/local \\    ".getBytes());
        index = (int)method.invoke(decoder, bb);
        assertEquals(32, index);
    }

    @Test
    public void testParseArgs() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        TelnetDecoder decoder = new TelnetDecoder();

        Method method = TelnetDecoder.class.getDeclaredMethod("parseArgs", String.class);
        method.setAccessible(true);

        String[] args = (String[])method.invoke(decoder, "");
        assertArrayEquals(new String[0], args);

        args = (String[])method.invoke(decoder, " \t\r");
        assertArrayEquals(new String[0], args);

        args = (String[])method.invoke(decoder, " \t\r-t 5 ");
        assertArrayEquals(new String[]{"-t", "5"}, args);

        args = (String[])method.invoke(decoder, " \t\r-t  \r\t5 ");
        assertArrayEquals(new String[]{"-t", "5"}, args);
    }

    @Test
    public void testParseIacCommand() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        TelnetDecoder decoder = new TelnetDecoder();

        Method method = TelnetDecoder.class.getDeclaredMethod("parseIacCommand", byte.class);
        method.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    method.invoke(decoder, (byte)239);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("Illegal IAC Commands"));

        IAC iac = (IAC) method.invoke(decoder, (byte)240);
        assertEquals(IAC.SE, iac);
        iac = (IAC) method.invoke(decoder, (byte)241);
        assertEquals(IAC.NOP, iac);
        iac = (IAC) method.invoke(decoder, (byte)242);
        assertEquals(IAC.DM, iac);
        iac = (IAC) method.invoke(decoder, (byte)243);
        assertEquals(IAC.BREAK, iac);
        iac = (IAC) method.invoke(decoder, (byte)244);
        assertEquals(IAC.IP, iac);
        iac = (IAC) method.invoke(decoder, (byte)245);
        assertEquals(IAC.AO, iac);
        iac = (IAC) method.invoke(decoder, (byte)246);
        assertEquals(IAC.AYT, iac);
        iac = (IAC) method.invoke(decoder, (byte)247);
        assertEquals(IAC.EC, iac);
        iac = (IAC) method.invoke(decoder, (byte)248);
        assertEquals(IAC.EL, iac);
        iac = (IAC) method.invoke(decoder, (byte)249);
        assertEquals(IAC.GA, iac);
        iac = (IAC) method.invoke(decoder, (byte)250);
        assertEquals(IAC.SB, iac);
        iac = (IAC) method.invoke(decoder, (byte)251);
        assertEquals(IAC.WILL, iac);
        iac = (IAC) method.invoke(decoder, (byte)252);
        assertEquals(IAC.WONT, iac);
        iac = (IAC) method.invoke(decoder, (byte)253);
        assertEquals(IAC.DO, iac);
        iac = (IAC) method.invoke(decoder, (byte)254);
        assertEquals(IAC.DONT, iac);
        iac = (IAC) method.invoke(decoder, (byte)255);
        assertEquals(IAC.IAC, iac);
    }
}
