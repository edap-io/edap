//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package edao.io.edap.data.jdbc.jdbc.test.entity;

import io.edap.data.PageResult;
import io.edap.data.QueryParam;
import io.edap.data.jdbc.*;
import io.edap.data.jdbc.jdbc.test.entity.Demo;
import io.edap.data.jdbc.util.DialectFactory;
import io.edap.util.CollectionUtils;
import io.edap.util.Constants;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.data.jdbc.util.DialectFactory.isSelectStart;
import static io.edap.util.Constants.EMPTY_ARRAY;

public class DemoJdbcViewDao2 extends JdbcBaseViewDao implements JdbcViewDao<Demo> {
	static Map<String, JdbcFieldSetFunc<Demo>> FIELD_SET_FUNCS = new ConcurrentHashMap();

	private DaoOption daoOption;

	public DemoJdbcViewDao2() {
		this(null);
	}

	public DemoJdbcViewDao2(DaoOption daoOption) {
		super();
		this.daoOption    = daoOption;
		this.limitDialect = DialectFactory.createLimitDialect(daoOption);
	}

	private static String fillSqlField(String var0) {
		if (var0 == null) {
			var0 = "";
		} else {
			var0 = var0.trim();
		}
		if (!isSelectStart(var0)) {
			var0 = "SELECT id,create_time,local_date_time FROM demo " + var0;
		}

		return var0;
	}

	public List<Demo> query(String var1) throws Exception {
		return this.query(var1, EMPTY_ARRAY);
	}

	private JdbcFieldSetFunc getSqlFieldSetFunc(ResultSet var1, String var2) throws SQLException {
		ResultSetMetaData var3 = var1.getMetaData();
		int var4 = var3.getColumnCount();
		ArrayList var5 = new ArrayList();

		for(int var6 = 1; var6 <= var4; ++var6) {
			var5.add(var3.getColumnName(var6));
		}

		return JdbcDaoRegister.instance().getFieldSetFunc(Demo.class, var5, var2);
	}

	public List<Demo> query(String var1, QueryParam[] var2) throws Exception {
		List var5;
		try {
			String var3 = fillSqlField(var1);
			ResultSet var4 = this.execute(var3, var2);
			if (var4 != null) {
				String var12 = this.getFieldsSql(var3);
				JdbcFieldSetFunc var6 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var12);
				if (var6 == null) {
					var6 = this.getSqlFieldSetFunc(var4, var12);
					FIELD_SET_FUNCS.put(var12, var6);
				}

				ArrayList var7 = new ArrayList();

				while(var4.next()) {
					Demo var8 = new Demo();
					var6.set(var8, var4);
					var7.add(var8);
				}

				ArrayList var13 = var7;
				return var13;
			}

			var5 = Constants.EMPTY_LIST;
		} finally {
			this.closeStatmentSession();
		}

		return var5;
	}

	public List<Demo> query(String var1, Object... var2) throws Exception {
		List var5;
		try {
			String var3 = fillSqlField(var1);
			ResultSet var4 = this.execute(var3, var2);
			if (var4 != null) {
				String var12 = this.getFieldsSql(var3);
				JdbcFieldSetFunc var6 = (JdbcFieldSetFunc)FIELD_SET_FUNCS.get(var12);
				if (var6 == null) {
					var6 = this.getSqlFieldSetFunc(var4, var12);
					FIELD_SET_FUNCS.putIfAbsent(var12, var6);
				}

				ArrayList var7 = new ArrayList();

				while(var4.next()) {
					Demo var8 = new Demo();
					var6.set(var8, var4);
					var7.add(var8);
				}

				ArrayList var13 = var7;
				return var13;
			}

			var5 = Constants.EMPTY_LIST;
		} finally {
			this.closeStatmentSession();
		}

		return var5;
	}

	@Override
	public List<Demo> query(String sql, int start, int count) throws Exception {
		return query(sql, start, count, EMPTY_ARRAY);
	}

	@Override
	public List<Demo> query(String sql, int start, int count, QueryParam... params) throws Exception {
		if (start < 0) {
			start = 0;
		}
		if (count < 1) {
			count = 1;
		}
		sql = fillSqlField(sql);
		StatementSession session = getStatementSession();
		try {
			LimitQueryInfo limitInfo = limitDialect.process(sql, start, count);
			PreparedStatement pstmt = session.prepareStatement(limitInfo.getSql());
			setPreparedParams(pstmt, params);
			setPreparedParams(pstmt, params.length+1, limitInfo.getParams());
			ResultSet rs = pstmt.executeQuery();
			String fieldsKey = this.getFieldsSql(sql);
			JdbcFieldSetFunc func = FIELD_SET_FUNCS.get(fieldsKey);
			if (func == null) {
				func = this.getSqlFieldSetFunc(rs, fieldsKey);
				FIELD_SET_FUNCS.putIfAbsent(fieldsKey, func);
			}

			ArrayList list = new ArrayList();

			while(rs.next()) {
				Demo var8 = new Demo();
				func.set(var8, rs);
				list.add(var8);
			}

			return list;
		} finally {
			this.closeStatmentSession(session);
		}
	}

	@Override
	public List<Demo> query(String sql, int start, int count, Object... params) throws Exception {
		if (start < 0) {
			start = 0;
		}
		if (count < 1) {
			count = 1;
		}
		sql = fillSqlField(sql);
		StatementSession session = getStatementSession();
		try {
			LimitQueryInfo limitInfo = limitDialect.process(sql, start, count);
			PreparedStatement pstmt = session.prepareStatement(limitInfo.getSql());
			setPreparedParams(pstmt, params);
			setPreparedParams(pstmt, params.length+1, limitInfo.getParams());
			ResultSet rs = pstmt.executeQuery();
			String fieldsKey = this.getFieldsSql(sql);
			JdbcFieldSetFunc func = FIELD_SET_FUNCS.get(fieldsKey);
			if (func == null) {
				func = this.getSqlFieldSetFunc(rs, fieldsKey);
				FIELD_SET_FUNCS.putIfAbsent(fieldsKey, func);
			}

			ArrayList list = new ArrayList();

			while(rs.next()) {
				Demo var8 = new Demo();
				func.set(var8, rs);
				list.add(var8);
			}

			return list;
		} finally {
			this.closeStatmentSession(session);
		}
	}

	@Override
	public PageResult<Demo> queryPage(String sql, int pageNum, int pageSize) throws Exception {
		return queryPage(sql, pageNum,pageSize, EMPTY_ARRAY);
	}

	@Override
	public PageResult<Demo> queryPage(String sql, int pageNum, int pageSize, QueryParam... params) throws Exception {
		if (pageSize < 1) {
			pageSize = 1;
		}
		int start;
		if (pageNum < 1) {
			start = 0;
		} else {
			start = (pageNum-1)*pageSize;
		}

		PageResult<Demo> result = new PageResult<>();
		result.setPageSize(pageSize);
		sql = fillSqlField(sql);
		StatementSession session = getStatementSession();
		try {
			String totalSql = DialectFactory.buildTotalSql(sql, "all_type_table", daoOption);
			PreparedStatement pstmt = session.prepareStatement(totalSql);
			setPreparedParams(pstmt, params);
			ResultSet rs = pstmt.executeQuery();
			if (rs.next()) {
				result.setTotal(rs.getInt(1));
			}
			LimitQueryInfo limitInfo = limitDialect.process(sql, start, pageSize);
			pstmt = session.prepareStatement(limitInfo.getSql());
			setPreparedParams(pstmt, params);
			setPreparedParams(pstmt, params.length+1, limitInfo.getParams());
			rs = pstmt.executeQuery();
			String fieldsKey = this.getFieldsSql(sql);
			JdbcFieldSetFunc func = FIELD_SET_FUNCS.get(fieldsKey);
			if (func == null) {
				func = this.getSqlFieldSetFunc(rs, fieldsKey);
				FIELD_SET_FUNCS.putIfAbsent(fieldsKey, func);
			}

			ArrayList list = new ArrayList();

			while(rs.next()) {
				Demo var8 = new Demo();
				func.set(var8, rs);
				list.add(var8);
			}

			result.setDataList(list);
			return result;
		} finally {
			this.closeStatmentSession(session);
		}
	}

	@Override
	public PageResult<Demo> queryPage(String sql, int pageNum, int pageSize, Object... params) throws Exception {
		return null;
	}

	public Demo findOne(String var1) throws Exception {
		List var2 = this.query(var1);
		return CollectionUtils.isEmpty(var2) ? null : (Demo)var2.get(0);
	}

	public Demo findOne(String var1, QueryParam[] var2) throws Exception {
		List var3 = this.query(var1, var2);
		return CollectionUtils.isEmpty(var3) ? null : (Demo)var3.get(0);
	}

	public Demo findOne(String var1, Object[] var2) throws Exception {
		List var3 = this.query(var1, var2);
		return CollectionUtils.isEmpty(var3) ? null : (Demo)var3.get(0);
	}

	public Demo findById(Object var1) throws Exception {
		if (var1 == null) {
			return null;
		} else {
			StatementSession var2 = this.getStatementSession();

			try {
				PreparedStatement var3 = var2.prepareStatement("SELECT create_time,id,local_date_time FROM demo WHERE id=?");
				var3.setInt(1, (Integer)var1);
				ResultSet var4 = var3.executeQuery();
				if (var4.next()) {
					Demo var5 = new Demo();
					var5.setCreateTime(var4.getLong(1));
					var5.setId(var4.getInt(2));
					var5.setLocalDateTime(var4.getLong(3));
					return var5;
				}
			} finally {
				var2.close();
			}

			return null;
		}
	}
}
