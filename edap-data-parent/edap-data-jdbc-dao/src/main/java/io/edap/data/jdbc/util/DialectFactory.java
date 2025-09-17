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

package io.edap.data.jdbc.util;

import io.edap.data.jdbc.DaoOption;
import io.edap.data.jdbc.DatabaseType;
import io.edap.data.jdbc.LimitDialect;
import io.edap.data.jdbc.dialect.PostgresqlLimitDialect;
import io.edap.util.StringUtil;

import java.util.Locale;

import static io.edap.util.Constants.EMPTY_STRING;

public class DialectFactory {

	public static LimitDialect createLimitDialect(DaoOption daoOption) {
		DatabaseType type;
		if (daoOption != null) {
			type = daoOption.getDatabaseType();
			if (type == null) {
				type = DatabaseType.MYSQL;
			}
		} else {
			type = DatabaseType.MYSQL;
		}
		LimitDialect limitDialect;
		switch (type) {
			case POSTGRESQL:
				limitDialect = new PostgresqlLimitDialect();
				break;
			default:
				limitDialect = new LimitDialect() {};
		}

		return limitDialect;
	}

	public static String buildTotalSql(String sql, String tableName, DaoOption daoOption) {
		if (StringUtil.isEmpty(sql)) {
			return EMPTY_STRING;
		}
		sql = sql.trim();
		if (!isSelectStart(sql)) {
			sql = "SELECT COUNT(*) FROM " + tableName + " " + sql;
		} else {
			sql = "SELECT COUNT(*) " + sql.substring(indexOfKeyword(sql, "FROM"));
		}

		return sql;
	}

	private static int indexOfKeyword(String sql, String keyword) {
		if (sql.length() < keyword.length()) {
			return -1;
		}
		keyword = keyword.toUpperCase(Locale.ENGLISH);
		char c;
		int range = sql.length()-keyword.length()-1;
		for (int i=0;i<range;i++) {
			c = sql.charAt(i);
			if (c == ' ') {
				char key2 = keyword.charAt(0);
				char c2 = sql.charAt(i+1);
				if (c2 != key2 && Character.toUpperCase(c2) != key2) {
					continue;
				}
				if (isEquals(sql, keyword, i)) {
					return i+1;
				}
			}
		}
		return -1;
	}

	private static boolean isEquals(String sql, String keyword, int i) {
		char c2;
		int j=1;
		for (;j<keyword.length();j++) {
			char k2 = keyword.charAt(j);
			c2 = sql.charAt(i+j+1);
			if (c2 != k2 && Character.toUpperCase(c2) != k2) {
				return false;
			}
		}
		return sql.charAt(i+j+1) == ' ';
	}

	public static boolean isSelectStart(String sql) {
		if (sql.length() < 7) {
			return false;
		}
		char c = sql.charAt(0);
		if (c != 'S' && c != 's') {
			return false;
		}
		c = sql.charAt(1);
		if (c != 'E' && c != 'e') {
			return false;
		}
		c = sql.charAt(2);
		if (c != 'L' && c != 'l') {
			return false;
		}
		c = sql.charAt(3);
		if (c != 'E' && c != 'e') {
			return false;
		}
		c = sql.charAt(4);
		if (c != 'C' && c != 'c') {
			return false;
		}
		c = sql.charAt(5);
		if (c != 'T' && c != 't') {
			return false;
		}
		c = sql.charAt(6);
		if (c != ' ') {
			return false;
		}

		return true;
	}
}
