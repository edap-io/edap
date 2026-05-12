package io.edap.data.jdbc.util;

import io.edap.data.jdbc.DatabaseType;

import java.util.List;

public class SqlUtils {


    public static void appendLimit(StringBuilder sql, int offset, int pageSize, List<Object> params, DatabaseType databaseType) {
        if (databaseType == DatabaseType.POSTGRESQL) {
            sql.append(" limit ? offset ?");
            params.add(pageSize);
            params.add(offset);
        } else if (databaseType == DatabaseType.MYSQL) {
            sql.append(" limit ?,?");
            params.add(offset);
            params.add(pageSize);
        }
    }
}
