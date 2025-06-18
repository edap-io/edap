package com.easyea.dbtools.sqlbuilder;

import com.easyea.dbtools.columncontraits.ColDefaultConstraint;
import com.easyea.dbtools.columncontraits.ColNotNullConstraint;
import com.easyea.dbtools.columncontraits.ColPrimaryKeyConstraint;
import com.easyea.dbtools.columncontraits.ColUniqueConstraint;
import com.easyea.dbtools.enums.DataType;
import com.easyea.dbtools.model.*;

import java.util.ArrayList;
import java.util.List;

public abstract class CreateTblBuilder {

    public List<String> buildSqls(TableStructure tableStructure) {
        List<String> sqls = new ArrayList<>();
        CreateTable createTable = tableStructure.getCreateTable();
        StringBuilder createSql = new StringBuilder();
        createSql.append("CREATE TABLE ");
        if (enableIfNotExists()) {
            createSql.append("IF NOT EXISTS ");
        }
        createSql.append(createTable.getTableName()).append(" (\n");
        int columnCount = createTable.getColumns().size();
        for (int i=0;i<columnCount;i++) {
            ColumnDefine columnDefine = createTable.getColumns().get(i);
            createSql.append('\t').append(columnDefine.getName()).append(' ');
            DataType type = columnDefine.getDataType();
            if (type != null) {
                createSql.append(type.getType());
                if (type.getPrecision() > 0) {
                    createSql.append('(').append(type.getPrecision());
                    if (type.getScale() > 0) {
                        createSql.append(", ").append(type.getScale());
                    }
                    createSql.append(") ");
                }
            }
            List<ColumnConstraint> columnConstraints = columnDefine.getColumnConstraints();
            if (columnConstraints != null && !columnConstraints.isEmpty()) {
                int cdcCount = columnConstraints.size();
                for (int j=0;j<cdcCount;j++) {
                    ColumnConstraint cc = columnConstraints.get(j);
                    if (cc instanceof ColPrimaryKeyConstraint) {
                        createSql.append("PRIMARY KEY");
                    } else if (cc instanceof ColDefaultConstraint) {
                        createSql.append("DEFAULT ");
                        ColDefaultConstraint cdc = (ColDefaultConstraint)cc;
                        if (isNumber(type)) {
                            createSql.append(cdc.getValue());
                        } else {
                            createSql.append('\'').append(cdc.getValue()).append("'");
                        }
                    } else if (cc instanceof ColNotNullConstraint) {
                        createSql.append("NOT NULL");
                    } else if (cc instanceof ColUniqueConstraint) {
                        createSql.append("UNIQUE");
                    }
                    if (j != cdcCount - 1) {
                        createSql.append(' ');
                    }
                }
            }
            if (i != columnCount - 1) {
                createSql.append(",\n");
            }
        }
        createSql.append(")");
        sqls.add(createSql.toString());

        List<CreateIndex> createIndexs = tableStructure.getCreateIndexs();
        if (createIndexs != null && !createIndexs.isEmpty()) {
            for (CreateIndex createIndex : createIndexs) {
                StringBuilder sql = new StringBuilder();
                sql.append("CREATE ");
                if (createIndex.isUnique()) {
                    sql.append("UNIQUE ");
                }
                sql.append("INDEX ");
                if (createIndex.isIfNotExists()) {
                    sql.append("IF NOT EXISTS ");
                }
                sql.append(createIndex.getName()).append(' ');
                sql.append("ON ").append(createIndex.getTableName()).append(" (");
                List<IndexedColumn> indexedColumns = createIndex.getIndexColumns();
                int icCount = indexedColumns.size();
                for (int i=0;i<icCount;i++) {
                    IndexedColumn ic = indexedColumns.get(i);
                    sql.append(ic.getColumnName());
                    if (ic.getCollate() != null && ic.getCollate().trim().length() > 0) {
                        sql.append(" COLLATE ").append(ic.getCollate());
                    }
                    if (ic.getSort() != null && ic.getSort().trim().length() > 0) {
                        sql.append(' ').append(ic.getSort());
                    }
                    if (i != icCount - 1) {
                        sql.append(',');
                    }
                }
                sql.append(")");

                sqls.add(sql.toString());
            }
        }
        return sqls;
    }

    public boolean isNumber(DataType dataType) {
        return true;
    }

    public abstract boolean enableIfNotExists();
}
