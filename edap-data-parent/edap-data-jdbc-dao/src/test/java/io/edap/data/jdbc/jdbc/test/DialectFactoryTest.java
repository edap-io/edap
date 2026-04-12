/*
 * Copyright 2020 The edap Project
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

package io.edap.data.jdbc.jdbc.test;

import io.edap.data.jdbc.DaoOption;
import io.edap.data.jdbc.DatabaseType;
import io.edap.data.jdbc.LimitDialect;
import io.edap.data.jdbc.dialect.PostgresqlLimitDialect;
import io.edap.data.jdbc.util.DialectFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class DialectFactoryTest {

	@Test
	public void testCreateLimitDialect() {
		LimitDialect dialect = DialectFactory.createLimitDialect(null);
		assertNotNull(dialect);

		DaoOption option = new DaoOption();
		dialect = DialectFactory.createLimitDialect(option);
		assertNotNull(dialect);

		option.setDatabaseType(DatabaseType.POSTGRESQL);
		dialect = DialectFactory.createLimitDialect(option);
		assertNotNull(dialect);
		assertEquals(dialect instanceof PostgresqlLimitDialect, true);
	}

	@Test
	public void testBuildTotalSql() {
		String sql = "";
		String destSql = DialectFactory.buildTotalSql(sql, "user_info", null);
		assertNotNull(destSql);

		sql = "where 1=1";
		destSql = DialectFactory.buildTotalSql(sql, "user_info", null);
		assertNotNull(destSql);
		assertEquals(destSql, "SELECT COUNT(*) FROM user_info " + sql);

		sql = "select * from user_info where 1=1";
		destSql = DialectFactory.buildTotalSql(sql, "user_info", null);
		assertNotNull(destSql);
		assertEquals(destSql, "SELECT COUNT(*) from user_info where 1=1");
	}

	@Test
	public void testIndexOfKeyword() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
		String sql = "";
		Method method = DialectFactory.class.getDeclaredMethod("indexOfKeyword", String.class, String.class);
		method.setAccessible(true);

		int index = (Integer)method.invoke(null, sql, "where");
		assertEquals(index, -1);

		sql = "select from ";
		index = (Integer)method.invoke(null, sql, "from");
		assertEquals(index, 7);

		sql = "select From ";
		index = (Integer)method.invoke(null, sql, "from");
		assertEquals(index, 7);

		sql = "select FRom ";
		index = (Integer)method.invoke(null, sql, "from");
		assertEquals(index, 7);

		sql = "select FROm ";
		index = (Integer)method.invoke(null, sql, "from");
		assertEquals(index, 7);

		sql = "select FROM ";
		index = (Integer)method.invoke(null, sql, "from");
		assertEquals(index, 7);

		sql = "select 1rom ";
		index = (Integer)method.invoke(null, sql, "from");
		assertEquals(index, -1);

		sql = "select F2om ";
		index = (Integer)method.invoke(null, sql, "from");
		assertEquals(index, -1);

		sql = "select FR3m ";
		index = (Integer)method.invoke(null, sql, "from");
		assertEquals(index, -1);

		sql = "select FRo4 ";
		index = (Integer)method.invoke(null, sql, "from");
		assertEquals(index, -1);

		sql = "select FROM5";
		index = (Integer)method.invoke(null, sql, "from");
		assertEquals(index, -1);
	}

	@Test
	public void testIsSelectStart() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
		String sql = "";
		Method method = DialectFactory.class.getDeclaredMethod("isSelectStart", String.class);
		method.setAccessible(true);

		boolean res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "1234567";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "S234567";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "s234567";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "SE34567";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "se34567";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "SEL4567";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "sel4567";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "SELE567";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "sele567";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "SELEC67";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "selec67";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "SELECT7";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "select7";
		res = (Boolean) method.invoke(null, sql);
		assertFalse(res);

		sql = "SELECT ";
		res = (Boolean) method.invoke(null, sql);
		assertTrue(res);

	}
}
