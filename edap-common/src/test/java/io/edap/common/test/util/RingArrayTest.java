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

import io.edap.util.RingArray;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

public class RingArrayTest {

	@Test
	public void testConstructor() throws NoSuchFieldException, IllegalAccessException {
		RingArray array = new RingArray(DemoEvent::new, 5);
		Field field = RingArray.class.getDeclaredField("values");
		field.setAccessible(true);
		Object[] values = (Object[]) field.get(array);
		assertNotNull(values);
		assertEquals(values.length, 5);
		for (int i=0;i<5;i++) {
			Object o = values[i];
			assertTrue(o instanceof DemoEvent);
			DemoEvent event = (DemoEvent) o;
			assertNotNull(event);
			assertEquals(event.valInt, 0);
			assertEquals(event.valLong, 0);
			assertEquals(event.valString, null);
		}
	}

	@Test
	public void testPut() {
		RingArray<DemoEvent> array = new RingArray(DemoEvent::new, 5);
		array.put(demoEvent -> {
			demoEvent.valInt    = 1;
			demoEvent.valLong   = 10001;
			demoEvent.valString = "text1";
		});
		assertEquals(array.size(), 1);
		assertEquals(array.get(0).valString, "text1");
		assertEquals(array.get(0).valInt, 1);
		assertEquals(array.get(0).valLong, 10001);

		array.put(demoEvent -> {
			demoEvent.valInt    = 2;
			demoEvent.valLong   = 10002;
			demoEvent.valString = "text2";
		});
		assertEquals(array.size(), 2);
		assertEquals(array.get(1).valString, "text2");
		assertEquals(array.get(1).valInt, 2);
		assertEquals(array.get(1).valLong, 10002);

		array.put(demoEvent -> {
			demoEvent.valInt    = 3;
			demoEvent.valLong   = 10003;
			demoEvent.valString = "text3";
		});
		assertEquals(array.size(), 3);
		assertEquals(array.get(2).valString, "text3");
		assertEquals(array.get(2).valInt, 3);
		assertEquals(array.get(2).valLong, 10003);

		array.put(demoEvent -> {
			demoEvent.valInt    = 4;
			demoEvent.valLong   = 10004;
			demoEvent.valString = "text4";
		});
		assertEquals(array.size(), 4);
		assertEquals(array.get(3).valString, "text4");
		assertEquals(array.get(3).valInt, 4);
		assertEquals(array.get(3).valLong, 10004);

		array.put(demoEvent -> {
			demoEvent.valInt    = 5;
			demoEvent.valLong   = 10005;
			demoEvent.valString = "text5";
		});
		assertEquals(array.size(), 5);
		assertEquals(array.get(4).valString, "text5");
		assertEquals(array.get(4).valInt, 5);
		assertEquals(array.get(4).valLong, 10005);

		array.put(demoEvent -> {
			demoEvent.valInt    = 6;
			demoEvent.valLong   = 10006;
			demoEvent.valString = "text6";
		});
		assertEquals(array.size(), 5);
		assertEquals(array.get(4).valString, "text6");
		assertEquals(array.get(4).valInt, 6);
		assertEquals(array.get(4).valLong, 10006);
		assertEquals(array.get(0).valInt, 2);
		assertEquals(array.get(1).valInt, 3);
		assertEquals(array.get(2).valInt, 4);
		assertEquals(array.get(3).valInt, 5);

		array.put(demoEvent -> {
			demoEvent.valInt    = 7;
			demoEvent.valLong   = 10007;
			demoEvent.valString = "text7";
		});
		assertEquals(array.size(), 5);
		assertEquals(array.get(0).valInt, 3);
		assertEquals(array.get(1).valInt, 4);
		assertEquals(array.get(2).valInt, 5);
		assertEquals(array.get(3).valInt, 6);
		assertEquals(array.get(4).valInt, 7);

		array.put(demoEvent -> {
			demoEvent.valInt    = 8;
			demoEvent.valLong   = 10008;
			demoEvent.valString = "text8";
		});
		assertEquals(array.size(), 5);
		assertEquals(array.get(0).valInt, 4);
		assertEquals(array.get(1).valInt, 5);
		assertEquals(array.get(2).valInt, 6);
		assertEquals(array.get(3).valInt, 7);
		assertEquals(array.get(4).valInt, 8);

		array.put(demoEvent -> {
			demoEvent.valInt    = 9;
			demoEvent.valLong   = 10009;
			demoEvent.valString = "text9";
		});
		assertEquals(array.size(), 5);
		assertEquals(array.get(0).valInt, 5);
		assertEquals(array.get(1).valInt, 6);
		assertEquals(array.get(2).valInt, 7);
		assertEquals(array.get(3).valInt, 8);
		assertEquals(array.get(4).valInt, 9);

		array.put(demoEvent -> {
			demoEvent.valInt    = 10;
			demoEvent.valLong   = 10010;
			demoEvent.valString = "text10";
		});
		assertEquals(array.size(), 5);
		assertEquals(array.get(0).valInt, 6);
		assertEquals(array.get(1).valInt, 7);
		assertEquals(array.get(2).valInt, 8);
		assertEquals(array.get(3).valInt, 9);
		assertEquals(array.get(4).valInt, 10);

		array.put(demoEvent -> {
			demoEvent.valInt    = 11;
			demoEvent.valLong   = 10011;
			demoEvent.valString = "text11";
		});
		assertEquals(array.size(), 5);
		assertEquals(array.get(0).valInt, 7);
		assertEquals(array.get(1).valInt, 8);
		assertEquals(array.get(2).valInt, 9);
		assertEquals(array.get(3).valInt, 10);
		assertEquals(array.get(4).valInt, 11);
	}

	@Test
	public void testGet() throws NoSuchFieldException, IllegalAccessException {
		RingArray<DemoEvent> array = new RingArray(DemoEvent::new, 5);
		array.put(demoEvent -> {
			demoEvent.valInt    = 1;
			demoEvent.valLong   = 10001;
			demoEvent.valString = "text1";
		});
		array.put(demoEvent -> {
			demoEvent.valInt    = 2;
			demoEvent.valLong   = 10002;
			demoEvent.valString = "text2";
		});
		array.put(demoEvent -> {
			demoEvent.valInt    = 3;
			demoEvent.valLong   = 10003;
			demoEvent.valString = "text3";
		});
		array.put(demoEvent -> {
			demoEvent.valInt    = 4;
			demoEvent.valLong   = 10004;
			demoEvent.valString = "text4";
		});
		array.put(demoEvent -> {
			demoEvent.valInt    = 5;
			demoEvent.valLong   = 10005;
			demoEvent.valString = "text5";
		});

		assertEquals(array.size(), 5);
		assertEquals(array.get(0).valInt, 1);
		assertEquals(array.get(0).valLong, 10001);
		assertEquals(array.get(1).valInt, 2);
		assertEquals(array.get(1).valLong, 10002);
		assertEquals(array.get(2).valInt, 3);
		assertEquals(array.get(2).valLong, 10003);
		assertEquals(array.get(3).valInt, 4);
		assertEquals(array.get(3).valLong, 10004);
		assertEquals(array.get(4).valInt, 5);
		assertEquals(array.get(4).valLong, 10005);

		Field headIndexField = RingArray.class.getDeclaredField("headIndex");
		headIndexField.setAccessible(true);
		headIndexField.set(array, 1);
		assertEquals(array.size(), 5);
		assertEquals(array.get(0).valInt, 2);
		assertEquals(array.get(0).valLong, 10002);
		assertEquals(array.get(1).valInt, 3);
		assertEquals(array.get(1).valLong, 10003);
		assertEquals(array.get(2).valInt, 4);
		assertEquals(array.get(2).valLong, 10004);
		assertEquals(array.get(3).valInt, 5);
		assertEquals(array.get(3).valLong, 10005);
		assertEquals(array.get(4).valInt, 1);
		assertEquals(array.get(4).valLong, 10001);

		headIndexField.set(array, 2);
		assertEquals(array.size(), 5);
		assertEquals(array.get(0).valInt, 3);
		assertEquals(array.get(0).valLong, 10003);
		assertEquals(array.get(1).valInt, 4);
		assertEquals(array.get(1).valLong, 10004);
		assertEquals(array.get(2).valInt, 5);
		assertEquals(array.get(2).valLong, 10005);
		assertEquals(array.get(3).valInt, 1);
		assertEquals(array.get(3).valLong, 10001);
		assertEquals(array.get(4).valInt, 2);
		assertEquals(array.get(4).valLong, 10002);

		headIndexField.set(array, 3);
		assertEquals(array.size(), 5);
		assertEquals(array.get(0).valInt, 4);
		assertEquals(array.get(0).valLong, 10004);
		assertEquals(array.get(1).valInt, 5);
		assertEquals(array.get(1).valLong, 10005);
		assertEquals(array.get(2).valInt, 1);
		assertEquals(array.get(2).valLong, 10001);
		assertEquals(array.get(3).valInt, 2);
		assertEquals(array.get(3).valLong, 10002);
		assertEquals(array.get(4).valInt, 3);
		assertEquals(array.get(4).valLong, 10003);

		headIndexField.set(array, 4);
		assertEquals(array.size(), 5);
		assertEquals(array.get(0).valInt, 5);
		assertEquals(array.get(0).valLong, 10005);
		assertEquals(array.get(1).valInt, 1);
		assertEquals(array.get(1).valLong, 10001);
		assertEquals(array.get(2).valInt, 2);
		assertEquals(array.get(2).valLong, 10002);
		assertEquals(array.get(3).valInt, 3);
		assertEquals(array.get(3).valLong, 10003);
		assertEquals(array.get(4).valInt, 4);
		assertEquals(array.get(4).valLong, 10004);

		Field sizeField = RingArray.class.getDeclaredField("size");
		sizeField.setAccessible(true);
		sizeField.set(array, 4);
		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> {
					array.get(4);
				});
		assertTrue(thrown.getMessage().contains("RingArray size is 4"));
	}

	public class DemoEvent {
		private long   valLong;
		private String valString;
		private int    valInt;
	}
}