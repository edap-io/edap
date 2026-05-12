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

package io.edap.data.jdbc;

import io.edap.util.StringUtil;

public interface LimitDialect {

	default LimitQueryInfo process(String sql, int offset, int limit) {
		return process(sql, offset, limit, "");
	}

	default LimitQueryInfo process(String sql, int offset, int limit, String orderBy) {
		LimitQueryInfo info = new LimitQueryInfo();
		if (!StringUtil.isEmpty(orderBy)) {
			sql += " " + orderBy;
		}
		info.setSql(sql + " limit ?,?");
		info.setParams(new Object[]{offset, limit});

		return info;
	}
}
