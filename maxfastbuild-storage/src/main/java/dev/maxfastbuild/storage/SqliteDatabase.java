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
        final boolean previous;
        try {
            previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
        } catch (SQLException ex) {
            throw new StorageException("SQLite transaction failed", ex);
        }

        T result = null;
        Throwable failure = null;
        try {
            result = work.run(connection);
            connection.commit();
        } catch (Throwable ex) {
            failure = ex;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                ex.addSuppressed(rollbackFailure);
            }
        }
        try {
            connection.setAutoCommit(previous);
        } catch (SQLException restoreFailure) {
            if (failure == null) failure = restoreFailure;
            else failure.addSuppressed(restoreFailure);
        }

        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) {
            throw exception instanceof StorageException storage
                    ? storage : new StorageException("SQLite transaction failed", exception);
        }
        return result;
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
