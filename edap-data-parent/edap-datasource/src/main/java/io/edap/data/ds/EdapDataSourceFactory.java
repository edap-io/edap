package io.edap.data.ds;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * edap 数据源工厂。
 *
 * <p>TCCL 由 JarLauncher 在启动时全局设为 EdapContainerClassLoader,本类无需
 * 局部 swap。HikariCP 内部连接池线程继承 creator 线程的 TCCL = appCL,后续
 * {@code DriverManager.getConnection} 的 {@code isDriverAllowed} 校验能
 * {@code Class.forName} 到 driver 类,自注册型 SQL driver 链路透明工作。
 */
public class EdapDataSourceFactory {

    public EdapDataSource createDataSource(String dataSourceId) {
        String jdbcUrl = "jdbc:postgresql://192.168.64.3:5432/estylr";
        String username = "estylr";
        String password = "estylr@Pass";
        int maxPoolSize = 100;
        int minIdle = 10;

        if (minIdle < 2) {
            minIdle = 2;
        }
        if (maxPoolSize < minIdle) {
            maxPoolSize = minIdle;
        }
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setMinimumIdle(minIdle);
        hikariConfig.setConnectionTimeout(5000);
        HikariDataSource hikariCP = new HikariDataSource(hikariConfig);
        return new EdapDataSource(hikariCP);
    }
}
