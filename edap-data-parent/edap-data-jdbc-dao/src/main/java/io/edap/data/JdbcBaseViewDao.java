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

package io.edap.data;

import javax.sql.DataSource;
import java.sql.Connection;

public abstract class JdbcBaseViewDao {

    DataSource dataSource;

    protected String databaseType;

    Connection con;

    static final ThreadLocal<StatementSession> STMT_SESSION_LOCAL =
            new ThreadLocal<StatementSession>() {
                @Override
                protected StatementSession initialValue() {
                    StatementSession statementSession = new SingleStatementSession();
                    return statementSession;
                }
            };

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setConnection(Connection con) {
        this.con = con;
    }

    public String getDatabaseType() {
        return databaseType;
    }

    public StatementSession getStatementSession() {
        StatementSession session = STMT_SESSION_LOCAL.get();
        if (session.getDataSource() == null) {
            session.setDataSource(dataSource);
        }
        if (con != null && session.getDataSource() == null) {
            session.setConnection(con);
        }
        return session;
    }


}