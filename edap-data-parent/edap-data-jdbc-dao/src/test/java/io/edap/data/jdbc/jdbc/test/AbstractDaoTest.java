package io.edap.data.jdbc.jdbc.test;

import org.h2.tools.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public abstract class AbstractDaoTest {
    private static Server server;

    protected Connection openConnection() throws SQLException {
        return DriverManager.
                getConnection("jdbc:h2:~/test", "sa", "");
    }

    protected void close(Connection con) {
        if (con != null) {
            try {
                con.close();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @BeforeAll
    public static void init() throws SQLException {
        String[] args = new String[]{};
        server = Server.createTcpServer(args);
        server.start();
    }

    @AfterAll
    public static void teardown() {
        if (server != null) {
            server.stop();
        }
    }
}
