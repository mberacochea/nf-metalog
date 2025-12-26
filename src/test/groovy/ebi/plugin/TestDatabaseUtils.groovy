package ebi.plugin

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

/**
 * Utility for db operations
 */
class TestDatabaseUtils {

    static Connection createConnection(Path dbFile) {
        DriverManager.getConnection("jdbc:sqlite:${dbFile}")
    }

    static ResultSet executeQuery(Connection conn, String sql) {
        conn.createStatement().executeQuery(sql)
    }

    static int getSingleIntResult(Connection conn, String sql) {
        def stmt = conn.createStatement()
        try {
            def rs = stmt.executeQuery(sql)
            try {
                return rs.next() ? rs.getInt(1) : 0
            } finally {
                rs.close()
            }
        } finally {
            stmt.close()
        }
    }

    static String getSingleStringResult(Connection conn, String sql) {
        def stmt = conn.createStatement()
        try {
            def rs = stmt.executeQuery(sql)
            try {
                return rs.next() ? rs.getString(1) : null
            } finally {
                rs.close()
            }
        } finally {
            stmt.close()
        }
    }

    static List<String> getColumnValues(Connection conn, String sql, String columnName) {
        def results = []
        def stmt = conn.createStatement()
        try {
            def rs = stmt.executeQuery(sql)
            try {
                while (rs.next()) {
                    results << rs.getString(columnName)
                }
                return results
            } finally {
                rs.close()
            }
        } finally {
            stmt.close()
        }
    }

    static boolean tableExists(Connection conn, String tableName) {
        def stmt = conn.createStatement()
        try {
            def rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='${tableName}'")
            try {
                return rs.next()
            } finally {
                rs.close()
            }
        } finally {
            stmt.close()
        }
    }

    static int getRowCount(Connection conn, String tableName) {
        getSingleIntResult(conn, "SELECT COUNT(*) FROM ${tableName}")
    }

    static void closeQuietly(AutoCloseable... resources) {
        resources.each { resource ->
            try {
                resource?.close()
            } catch (Exception e) {
                // Ignore close exceptions
            }
        }
    }

    static <T> T withConnection(Path dbFile, Closure<T> closure) {
        def conn = null
        try {
            conn = createConnection(dbFile)
            return closure.call(conn)
        } finally {
            closeQuietly(conn)
        }
    }
}