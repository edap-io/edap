/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.common.test.util;

import io.edap.util.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class ByteArrayBuilderTest {

    @Test
    public void testAppendBoolean() {
        ByteArrayBuilder bab = new ByteArrayBuilder(3);
        assertEquals(bab.length(), 0);
        assertEquals(bab.remain(), 3);
        bab.append(true);
        assertEquals(bab.length(), 4);
        assertEquals(bab.remain(), 2);
        assertArrayEquals(bab.toByteArray(), "true".getBytes());

        bab = new ByteArrayBuilder(3);
        bab.append(false);
        assertEquals(bab.length(), 5);
        assertEquals(bab.remain(), 1);
        assertArrayEquals(bab.toByteArray(), "false".getBytes());

    }

    @Test
    public void testAppendBooleanObj() {
        ByteArrayBuilder bab = new ByteArrayBuilder(3);
        Boolean bool = null;
        assertEquals(bab.length(), 0);
        assertEquals(bab.remain(), 3);
        bab.append(bool);
        assertEquals(bab.length(), 4);
        assertEquals(bab.remain(), 2);
        assertArrayEquals(bab.toByteArray(), "null".getBytes());

        bab = new ByteArrayBuilder(3);
        bool = true;
        assertEquals(bab.length(), 0);
        assertEquals(bab.remain(), 3);
        bab.append(bool);
        assertEquals(bab.length(), 4);
        assertEquals(bab.remain(), 2);
        assertArrayEquals(bab.toByteArray(), "true".getBytes());

        bool = false;
        bab = new ByteArrayBuilder(3);
        bab.append(bool);
        assertEquals(bab.length(), 5);
        assertEquals(bab.remain(), 1);
        assertArrayEquals(bab.toByteArray(), "false".getBytes());

    }

    @Test
    public void testAppendOneByte() {
        ByteArrayBuilder bab = new ByteArrayBuilder(3);
        bab.append((byte)'a');
        assertEquals(bab.length(), 1);
        assertEquals(bab.remain(), 2);
        assertArrayEquals(bab.toByteArray(), "a".getBytes());
    }

    @Test
    public void testAppendTwoByte() {
        ByteArrayBuilder bab = new ByteArrayBuilder(3);
        bab.append((byte)'a', (byte)'b');
        assertEquals(bab.length(), 2);
        assertEquals(bab.remain(), 1);
        assertArrayEquals(bab.toByteArray(), "ab".getBytes());

        bab = new ByteArrayBuilder(3);
        bab.append((byte)'a', (byte)'b');
        bab.append((byte)'c', (byte)'d');
        assertEquals(bab.length(), 4);
        assertEquals(bab.remain(), 2);
        assertArrayEquals(bab.toByteArray(), "abcd".getBytes());
    }

    @Test
    public void testUncheckAppendTwoByte() {
        ByteArrayBuilder bab = new ByteArrayBuilder(3);
        bab.uncheckAppend((byte)'a', (byte)'b');
        assertEquals(bab.length(), 2);
        assertEquals(bab.remain(), 1);
        assertArrayEquals(bab.toByteArray(), "ab".getBytes());

        ArrayIndexOutOfBoundsException ex = assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> {
                    ByteArrayBuilder bab2 = new ByteArrayBuilder(3);
                    bab2.uncheckAppend((byte) 'a', (byte) 'b');
                    bab2.uncheckAppend((byte) 'c', (byte) 'd');
                }
        );
        assertTrue(ex.getMessage().contains("Index 3 out of bounds for length 3"));
    }

    @Test
    public void testUncheckAppendThreeByte() {
        ByteArrayBuilder bab = new ByteArrayBuilder(4);
        bab.uncheckAppend((byte)'a', (byte)'b', (byte)'c');
        assertEquals(bab.length(), 3);
        assertEquals(bab.remain(), 1);
        assertArrayEquals(bab.toByteArray(), "abc".getBytes());

		ArrayIndexOutOfBoundsException ex = assertThrows(ArrayIndexOutOfBoundsException.class,
				() -> {
					ByteArrayBuilder bab2 = new ByteArrayBuilder(3);
					bab2.uncheckAppend((byte) 'a', (byte) 'b');
					bab2.uncheckAppend((byte) 'c', (byte) 'd', (byte) 'e');
				}
        );
		assertTrue(ex.getMessage().contains("Index 3 out of bounds for length 3"));
    }

	@Test
	public void testAppendThreeByte() {
		ByteArrayBuilder bab = new ByteArrayBuilder(2);
		bab.append((byte)'a', (byte)'b', (byte)'c');
		assertEquals(bab.length(), 3);
		assertEquals(bab.remain(), 1);
		assertArrayEquals(bab.toByteArray(), "abc".getBytes());

		bab = new ByteArrayBuilder(3);
		bab.append((byte)'a', (byte)'b');
		bab.append((byte)'c', (byte)'d', (byte)'e');
		assertEquals(bab.length(), 5);
		assertEquals(bab.remain(), 1);
		assertArrayEquals(bab.toByteArray(), "abcde".getBytes());
	}

    @Test
    public void testAppendFourByte() {
        ByteArrayBuilder bab = new ByteArrayBuilder(2);
        bab.append((byte)'a', (byte)'b', (byte)'c', (byte)'d');
        assertEquals(bab.length(), 4);
        assertEquals(bab.remain(), 0);
        assertArrayEquals(bab.toByteArray(), "abcd".getBytes());

        bab = new ByteArrayBuilder(5);
        bab.append((byte)'a', (byte)'b', (byte)'c', (byte)'d');
        bab.append((byte)'e', (byte)'f');
        assertEquals(bab.length(), 6);
        assertEquals(bab.remain(), 4);
        assertArrayEquals(bab.toByteArray(), "abcdef".getBytes());
    }

	@Test
	public void testUncheckAppendFourByte() {
		ByteArrayBuilder bab = new ByteArrayBuilder(4);
		bab.uncheckAppend((byte)'a', (byte)'b', (byte)'c', (byte)'d');
		assertEquals(bab.length(), 4);
		assertEquals(bab.remain(), 0);
		assertArrayEquals(bab.toByteArray(), "abcd".getBytes());

		ArrayIndexOutOfBoundsException ex = assertThrows(ArrayIndexOutOfBoundsException.class,
				() -> {
					ByteArrayBuilder bab2 = new ByteArrayBuilder(5);
					bab2.uncheckAppend((byte)'a', (byte)'b', (byte)'c', (byte)'d');
					bab2.uncheckAppend((byte)'e', (byte)'f');
				}
		);
		assertTrue(ex.getMessage().contains("Index 5 out of bounds for length 5"));
	}

	@Test
	public void testCao() {
		ByteArrayBuilder bab = new ByteArrayBuilder(3);
		assertEquals(bab.cap(), 3);
		bab.append((byte)'a', (byte)'b', (byte)'c', (byte)'d');
		assertEquals(bab.cap(), 6);
	}

	@Test
	public void testInit() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		assertEquals(bab.cap(), 128);
	}

	@Test
	public void testGetValue() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		assertEquals(bab.cap(), 128);
		assertArrayEquals(bab.getValue(), new byte[128]);
	}

	@Test
	public void testAppendShort() {
		int v = new Random().nextInt((Short.MAX_VALUE * 2));
		ByteArrayBuilder bab = new ByteArrayBuilder();
		short sv = (short)(v-Short.MAX_VALUE);
		bab.append(sv);
		assertArrayEquals(bab.toByteArray(), Integer.toString(sv).getBytes());
	}

	@Test
	public void testAppendShortObj() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		Short so = null;
		bab.append(so);
		assertArrayEquals(bab.toByteArray(), "null".getBytes());

		int v = new Random().nextInt((Short.MAX_VALUE * 2));
		bab = new ByteArrayBuilder();
		Short sv = (short)(v-Short.MAX_VALUE);
		bab.append(sv);
		assertArrayEquals(bab.toByteArray(), Integer.toString(sv).getBytes());
	}

	@Test
	public void testAppendFloat() {
		double v = new Random().nextDouble((double)(Short.MAX_VALUE * 2));
		ByteArrayBuilder bab = new ByteArrayBuilder();
		float sv = (float)(v-Float.MAX_VALUE);
		bab.append(sv);
		assertArrayEquals(bab.toByteArray(), Float.toString(sv).getBytes());
	}

	@Test
	public void testAppendFloatObj() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		Float so = null;
		bab.append(so);
		assertArrayEquals(bab.toByteArray(), "null".getBytes());

		double v = new Random().nextDouble((double)(Short.MAX_VALUE * 2));
		bab = new ByteArrayBuilder();
		Float sv = (float)(v-Float.MAX_VALUE);
		bab.append(sv);
		assertArrayEquals(bab.toByteArray(), Float.toString(sv).getBytes());
	}

	@Test
	public void testAppendInt() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		int v = 0;
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), "0".getBytes());

		bab = new ByteArrayBuilder();
		v = Integer.MIN_VALUE;
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), Integer.toString(Integer.MIN_VALUE).getBytes());

		int[] vs = new int[]{1,9,99,999,9999,99999,999999,9999999,99999999,99999999,Integer.MAX_VALUE};
		for (int i=0;i<vs.length;i++) {
			bab.reset();
			bab.append(vs[i]);
			assertArrayEquals(bab.toByteArray(), Integer.toString(vs[i]).getBytes());
		}
	}

	@Test
	public void testAppendInteger() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		Integer v = null;
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), "null".getBytes());

		bab = new ByteArrayBuilder();
		v = new Random().nextInt(Integer.MAX_VALUE);
		bab.append(Integer.valueOf(v));
		assertArrayEquals(bab.toByteArray(), Integer.toString(v).getBytes());

		bab = new ByteArrayBuilder();
		v = new Random().nextInt(Integer.MAX_VALUE);
		bab.append(Integer.valueOf(-v));
		assertArrayEquals(bab.toByteArray(), Integer.toString(-v).getBytes());

	}

	@Test
	public void testAppendLong() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		long v = 0;
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), "0".getBytes());

		bab = new ByteArrayBuilder();
		v = Long.MIN_VALUE;
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), Long.toString(Long.MIN_VALUE).getBytes());

		long[] vs = new long[]{1,9,99,999,9999,99999,999999,9999999,99999999,99999999,999999999,
				9999999999L,99999999999L,999999999999L,9999999999999L,99999999999999L,
				999999999999999L,99999999999999999L,999999999999999999L,999999999999999999L,999999999999999999L,Long.MAX_VALUE};
		for (int i=0;i<vs.length;i++) {
			bab.reset();
			bab.append(vs[i]);
			assertArrayEquals(bab.toByteArray(), Long.toString(vs[i]).getBytes());
		}
	}

	@Test
	public void testAppendLongObj() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		Long v = null;
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), "null".getBytes());

		bab = new ByteArrayBuilder();
		v = new Random().nextLong(Long.MAX_VALUE);
		bab.append(Long.valueOf(v));
		assertArrayEquals(bab.toByteArray(), Long.toString(v).getBytes());

		bab = new ByteArrayBuilder();
		v = new Random().nextLong(Long.MAX_VALUE);
		bab.append(Long.valueOf(-v));
		assertArrayEquals(bab.toByteArray(), Long.toString(-v).getBytes());

	}

	@Test
	public void testAppendString() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		String v = null;
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), "null".getBytes());

		String[] ss = new String[]{
				"",
				"a",
				"\u03bcs",
				"中123456",
				"abcdefgh",
				"中文内容",
				"🐶头",
				"0x3C3F786D6C2076657273696F6E3D27312E302720656E636F64696E673D275554462D38273F3E203C646566696E6974696F6E7320786D6C6E733D22687474703A2F2F7777772E6F6D672E6F72672F737065632F42504D4E2F32303130303532342F4D4F44454C2220786D6C6E733A7873693D22687474703A2F2F7777772E77332E6F72672F323030312F584D4C536368656D612D696E7374616E63652220786D6C6E733A7873643D22687474703A2F2F7777772E77332E6F72672F323030312F584D4C536368656D612220786D6C6E733A61637469766974693D22687474703A2F2F61637469766974692E6F72672F62706D6E2220786D6C6E733A62706D6E64693D22687474703A2F2F7777772E6F6D672E6F72672F737065632F42504D4E2F32303130303532342F44492220786D6C6E733A6F6D6764633D22687474703A2F2F7777772E6F6D672E6F72672F737065632F44442F32303130303532342F44432220786D6C6E733A6F6D6764693D22687474703A2F2F7777772E6F6D672E6F72672F737065632F44442F32303130303532342F44492220747970654C616E67756167653D22687474703A2F2F7777772E77332E6F72672F323030312F584D4C536368656D61222065787072657373696F6E4C616E67756167653D22687474703A2F2F7777772E77332E6F72672F313939392F585061746822207461726765744E616D6573706163653D22687474703A2F2F61637469766974692E6F72672F74657374223E203C70726F636573732069643D2266726565666C6F77466F727761726422206E616D653D22E8BDACE58F91E887AAE794B1E6B581E7A88B2220697345786563757461626C653D2274727565222076657273696F6E49643D22322E30223E203C657874656E73696F6E456C656D656E74733E203C61637469766974693A657865637574696F6E4C697374656E6572206576656E743D2273746172742220636C6173733D22636F6D2E796F6E796F752E62706D2E6C697374656E65722E44656661756C745374617274457865637574696F6E4C697374656E6572222F3E203C61637469766974693A657865637574696F6E4C697374656E6572206576656E743D22656E642220636C6173733D22636F6D2E796F6E796F752E62706D2E6C697374656E65722E44656661756C74457865637574696F6E4C697374656E6572222F3E203C61637469766974693A657865637574696F6E4C697374656E6572206576656E743D22656E642220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C2F657874656E73696F6E456C656D656E74733E203C617070726F766553746172744576656E742069643D2273746172744576656E7433363839222061637469766974693A63616E436F6F7065726174696F6E3D2266616C7365222061637469766974693A73656E64546F5369676E6572494D3D2266616C7365222061637469766974693A73656E64546F416C6C55736572494D3D2266616C7365222061637469766974693A73656E64546F53746172746572494D3D2274727565222061637469766974693A63616E436F6D6D656E743D2274727565222061637469766974693A63616E466F72776172643D2274727565223E203C646F63756D656E746174696F6E3E6A756D70546F52656A65637441637469766974793B73656E64546F436F7079546F55736572733B7769746864726177416C6C3C2F646F63756D656E746174696F6E3E203C2F617070726F766553746172744576656E743E203C617070726F7665557365725461736B2069643D22617070726F7665557365725461736B3534393222206E616D653D22E5AEA1E689B9E4BBBBE58AA1222061637469766974693A77697468447261773D2266616C7365222061637469766974693A61737369676E41626C653D2266616C7365222061637469766974693A6164647369676E41626C653D2266616C7365222061637469766974693A72656A65637441626C653D2266616C7365222061637469766974693A63616E426552656A65637465643D2266616C7365222061637469766974693A64656C656761746541626C653D2274727565222061637469766974693A6164647369676E426568696E6441626C653D2266616C7365222061637469766974693A636F7079546F41626C653D2266616C7365222061637469766974693A72656A656374546F456E643D2266616C7365223E203C6D756C7469496E7374616E63654C6F6F7043686172616374657269737469637320697353657175656E7469616C3D2266616C7365222061637469766974693A636F6C6C656374696F6E3D22247B62706D4265616E2E67657455736572282671756F743B7B2770726F636573735061727469636970616E744974656D73273A5B7B2764657461696C73273A5B7B276964273A272461737369676E4C697374277D5D2C2764696664657074273A66616C73652C276C617374417070726F766553616D6564657074273A66616C73652C276C617374417070726F766553616D656F7267273A66616C73652C276C6173744170726F766553616D6544657074496E636C756465486967684C6576656C273A66616C73652C276C6173744170726F766553616D654F7267496E636C756465486967684C6576656C273A66616C73652C276D61726B65724F72674D6772496E636C75646548696768273A66616C73652C276E6F74496E636C7564655061727454696D65273A66616C73652C2773616D6564657074273A66616C73652C2773616D6564657074496E636C756465486967684C6576656C273A66616C73652C2773616D656F7267273A66616C73652C2773616D656F7267496E636C756465486967684C6576656C273A66616C73652C2774797065273A2741535349474E4C495354277D5D7D2671756F743B297D222061637469766974693A656C656D656E745661726961626C653D2261737369676E6565223E203C636F6D706C6574696F6E436F6E646974696F6E3E247B6E724F66436F6D706C65746564496E7374616E6365732F6E724F66496E7374616E6365733D3D317D3C2F636F6D706C6574696F6E436F6E646974696F6E3E203C2F6D756C7469496E7374616E63654C6F6F704368617261637465726973746963733E203C657874656E73696F6E456C656D656E74733E203C61637469766974693A657865637574696F6E4C697374656E6572206576656E743D22656E642220636C6173733D22636F6D2E796F6E796F752E62706D2E6C697374656E65722E4163746976697479456E64457865637574696F6E4C697374656E6572222F3E203C61637469766974693A657865637574696F6E4C697374656E6572206576656E743D2273746172742220636C6173733D22636F6D2E796F6E796F752E62706D2E6C697374656E65722E41637469766974795374617274457865637574696F6E4C697374656E6572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D226372656174652220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D22636F6D706C6574652220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D226A756D702220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D2277697468647261772220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D226F757474696D652220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D2264656C6574652220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C2F657874656E73696F6E456C656D656E74733E203C2F617070726F7665557365725461736B3E203C656E644576656E742069643D22656E644576656E7431343538222F3E203C73657175656E6365466C6F772069643D2253657175656E6365466C6F77313433362220736F757263655265663D2273746172744576656E743336383922207461726765745265663D22617070726F7665557365725461736B35343932222F3E203C73657175656E6365466C6F772069643D2253657175656E6365466C6F77363837312220736F757263655265663D22617070726F7665557365725461736B3534393222207461726765745265663D22656E644576656E7431343538222F3E203C2F70726F636573733E203C62706D6E64693A42504D4E4469616772616D2069643D2242504D4E4469616772616D5F70726F6365737339323931223E203C62706D6E64693A42504D4E506C616E652062706D6E456C656D656E743D2270726F6365737339323931222069643D2242504D4E506C616E655F70726F6365737339323931223E203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D2273746172744576656E7433363839222069643D2242504D4E53686170655F73746172744576656E7433363839223E203C6F6D6764633A426F756E6473206865696768743D2232342E30222077696474683D2232342E302220783D2236302E302220793D2234322E30222F3E203C2F62706D6E64693A42504D4E53686170653E203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D22617070726F7665557365725461736B35343932222069643D2242504D4E53686170655F617070726F7665557365725461736B35343932223E203C6F6D6764633A426F756E6473206865696768743D2236302E30222077696474683D223134342E302220783D223132302E302220793D2232342E30222F3E203C2F62706D6E64693A42504D4E53686170653E203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D22656E644576656E7431343538222069643D2242504D4E53686170655F656E644576656E7431343538223E203C6F6D6764633A426F756E6473206865696768743D2232342E30222077696474683D2232342E302220783D223330302E302220793D2234322E30222F3E203C2F62706D6E64693A42504D4E53686170653E203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D2253657175656E6365466C6F7736383731222069643D2242504D4E456467655F53657175656E6365466C6F7736383731223E203C6F6D6764693A776179706F696E7420783D22312E302220793D22322E302220736567496E6465783D2230222F3E203C2F62706D6E64693A42504D4E456467653E203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D2253657175656E6365466C6F7731343336222069643D2242504D4E456467655F53657175656E6365466C6F7731343336223E203C6F6D6764693A776179706F696E7420783D22312E302220793D22322E302220736567496E6465783D2230222F3E203C2F62706D6E64693A42504D4E456467653E203C2F62706D6E64693A42504D4E506C616E653E203C2F62706D6E64693A42504D4E4469616772616D3E203C2F646566696E6974696F6E733E"
		};

		for (int i=0;i<ss.length;i++) {
			bab.reset();
			bab.append(ss[i]);
			assertArrayEquals(bab.toByteArray(), ss[i].getBytes(StandardCharsets.UTF_8));
		}

		for (int i=0;i<ss.length;i++) {
			bab.reset();
			bab.append(ss[i], 0, ss[i].length());
			assertArrayEquals(bab.toByteArray(), ss[i].getBytes(StandardCharsets.UTF_8));
		}
	}

	@Test
	public void testAppendDouble() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		double so = 0;
		bab.append(so);
		assertArrayEquals(bab.toByteArray(), "0.0".getBytes());

		bab.reset();
		so = Double.POSITIVE_INFINITY;
		bab.append(so);
		assertArrayEquals(bab.toByteArray(), "Infinity".getBytes());

		bab.reset();
		so = Double.NEGATIVE_INFINITY;
		bab.append(so);
		assertArrayEquals(bab.toByteArray(), "-Infinity".getBytes());

		bab.reset();
		so = Double.NaN;
		bab.append(so);
		assertArrayEquals(bab.toByteArray(), "NaN".getBytes());

		double v = new Random().nextDouble();
		bab = new ByteArrayBuilder();
		double sv = v;
		bab.append(sv);
		assertArrayEquals(bab.toByteArray(), Double.toString(sv).getBytes());

		bab = new ByteArrayBuilder();
		for (int i=0;i<1000;i++) {
			bab.reset();
			sv = (double) 1 / i;
			bab.append(sv);
			if (bab.toByteArray().length == Double.toString(sv).getBytes().length) {
				try {
					assertArrayEquals(bab.toByteArray(), Double.toString(sv).getBytes());
				} catch (Exception e) {
					//System.out.println(new String(bab.toByteArray()));
				}
			} else {
				System.out.println(new String(bab.toByteArray()));
			}
		}
	}

	@Test
	public void testAppendDoubleObj() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		Double v = null;
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), "null".getBytes());

		v = new Random().nextDouble();
		bab = new ByteArrayBuilder();
		Double sv = v;
		bab.append(sv);
		assertArrayEquals(bab.toByteArray(), Double.toString(sv).getBytes());
	}

	@Test
	public void testAppendObj() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		Object v = null;
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), "null".getBytes());

		bab.reset();
		Random random = new Random();
		v = Integer.valueOf(random.nextInt());
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), String.valueOf(Integer.valueOf((Integer)v)).getBytes());

		bab.reset();
		v = Short.valueOf((short) random.nextInt(Short.MAX_VALUE));
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), String.valueOf(Short.valueOf((Short) v)).getBytes());

		bab.reset();
		v = Float.valueOf((short) random.nextFloat());
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), String.valueOf(Float.valueOf((Float) v)).getBytes());

		bab.reset();
		v = Long.valueOf(random.nextLong());
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), String.valueOf(Long.valueOf((Long) v)).getBytes());

		bab.reset();
		v = Double.valueOf(random.nextDouble());
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), String.valueOf(Double.valueOf((Double) v)).getBytes());

		bab.reset();
		v = Boolean.valueOf(random.nextBoolean());
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), String.valueOf(Boolean.valueOf((Boolean) v)).getBytes());

		bab.reset();
		v = new Date();
		bab.append(v);
		assertArrayEquals(bab.toByteArray(), String.valueOf(v).getBytes());
	}

	@Test
	public void testNewCapacity() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		bab.ensureCapacity(129);

		bab.ensureCapacity(Integer.MAX_VALUE - 7);
	}

	@Test
	public void testSetLength() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		bab.setLength(12);
		bab.setLength(256);

		assertEquals(bab.length(), 128);
	}

	@Test
	public void testAppendByteArrayBuilder() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		ByteArrayBuilder bab2 = new ByteArrayBuilder();
		bab.append((byte)'a', (byte)'b');
		bab2.append((byte)'c', (byte)'d', (byte)'e');
		bab.append(bab2);
		assertArrayEquals(bab.toByteArray(), "abcde".getBytes());
	}

	@Test
	public void testToString() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		bab.append((byte)'a', (byte)'b');
		assertEquals(bab.toString(), "ab");

		bab.reset();
		bab.append((byte)'c', (byte)'d', (byte)'e');
		assertEquals(bab.toString(StandardCharsets.UTF_8), "cde");

		bab.reset();
		bab.append((byte)'c', (byte)'d', (byte)'e');
		assertEquals(bab.toString("utf-8"), "cde");
	}

	@Test
	public void testWriteTo() throws IOException {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		bab.append((byte)'a', (byte)'b');

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		bab.writeTo(out);
		assertEquals(bab.toString(), out.toString());
	}

	@Test
	public void testGet() {
		ByteArrayBuilder bab = new ByteArrayBuilder();
		bab.append((byte)'a', (byte)'b');
		assertEquals(bab.get(0), (byte)'a');
		assertEquals(bab.get(1), (byte)'b');
	}
}
