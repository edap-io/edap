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

package io.edap.data.jdbc.dialect;

import io.edap.data.jdbc.LimitDialect;
import io.edap.data.jdbc.LimitQueryInfo;

public class PostgresqlLimitDialect implements LimitDialect {

	public LimitQueryInfo process(String sql, int offset, int limit) {
		LimitQueryInfo info =  new LimitQueryInfo();
		info.setSql(sql + " limit ? offset ?");
		info.setParams(new Object[]{limit, offset});
		return info;
	}
}
