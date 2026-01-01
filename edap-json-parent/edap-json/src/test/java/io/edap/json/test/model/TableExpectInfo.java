package io.edap.json.test.model;

import java.io.Serializable;

public class TableExpectInfo implements Serializable {
    private String conditionColumn;
    private String tableName;

    private boolean isExists;
    //private List<Map<String, Object>> columnExpects;

    /**
     * 查询条件的列名
     */
    public String getConditionColumn() {
        return conditionColumn;
    }

    public void setConditionColumn(String conditionColumn) {
        this.conditionColumn = conditionColumn;
    }

    /**
     * 数据中的表名
     */
    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    /**
     * 记录是存在
     */
    public boolean isExists() {
        return isExists;
    }

    public void setExists(boolean exists) {
        isExists = exists;
    }

    /**
     * 每条记录各个字段的的对应的值描述
     */
//    public List<Map<String, Object>> getColumnExpects() {
//        return columnExpects;
//    }
//
//    public void setColumnExpects(List<Map<String, Object>> columnExpects) {
//        this.columnExpects = columnExpects;
//    }
}
