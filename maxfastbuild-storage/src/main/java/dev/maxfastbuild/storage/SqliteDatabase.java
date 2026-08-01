package dev.maxfastbuild.storage;

import java.nio.file.Path;
import java.sql.*;

public final class SqliteDatabase implements AutoCloseable {
    private final Connection connection;

    public SqliteDatabase(Path file) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=5000");
            }
        } catch (SQLException ex) { throw new StorageException("Unable to open SQLite database", ex); }
    }

    public synchronized <T> T transaction(SqlWork<T> work) {
        try {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally { connection.setAutoCommit(previous); }
        } catch (Exception ex) { throw ex instanceof StorageException storage ? storage : new StorageException("SQLite transaction failed", ex); }
    }

    @Override public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ex) {
            // Prefer soft close on plugin unload (PlugMan) so disable never aborts mid-cleanup.
            throw new StorageException("Unable to close SQLite database", ex);
        }
    }

    /** Close without throwing — for plugin disable / hot-reload paths. */
    public synchronized void closeQuietly() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) { }
    }

    @FunctionalInterface public interface SqlWork<T> { T run(Connection connection) throws Exception; }
}
