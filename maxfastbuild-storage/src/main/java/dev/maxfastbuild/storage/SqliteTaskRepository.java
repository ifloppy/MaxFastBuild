package dev.maxfastbuild.storage;

import com.google.gson.*;
import dev.maxfastbuild.api.*;
import dev.maxfastbuild.core.task.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class SqliteTaskRepository implements TaskRepository {
    private static final Gson GSON = new Gson();
    private final SqliteDatabase database;

    public SqliteTaskRepository(SqliteDatabase database) { this.database = database; }

    @Override public void initialize() {
        database.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS build_tasks (
                      id TEXT PRIMARY KEY, player_id TEXT NOT NULL, player_name TEXT NOT NULL,
                      world TEXT NOT NULL, operation TEXT NOT NULL, bounds_json TEXT NOT NULL,
                      mutations_json TEXT NOT NULL, status TEXT NOT NULL, cursor INTEGER NOT NULL,
                      applied_count INTEGER NOT NULL DEFAULT 0,
                      escrow_id TEXT, charged TEXT NOT NULL, refunded TEXT NOT NULL,
                      created_at TEXT NOT NULL, updated_at TEXT NOT NULL, failure TEXT
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_tasks_player_status ON build_tasks(player_id,status)");
                ensureAppliedColumn(connection);
            }
            return null;
        });
    }

    private static void ensureAppliedColumn(Connection connection) throws SQLException {
        boolean hasApplied = false;
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, "build_tasks", "applied_count")) {
            hasApplied = columns.next();
        }
        if (!hasApplied) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE build_tasks ADD COLUMN applied_count INTEGER NOT NULL DEFAULT 0");
            }
        }
    }

    @Override public void save(BuildTask task) {
        database.transaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement("""
                INSERT INTO build_tasks(id,player_id,player_name,world,operation,bounds_json,mutations_json,status,cursor,applied_count,escrow_id,charged,refunded,created_at,updated_at,failure)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET status=excluded.status,cursor=excluded.cursor,applied_count=excluded.applied_count,escrow_id=excluded.escrow_id,
                  charged=excluded.charged,refunded=excluded.refunded,updated_at=excluded.updated_at,failure=excluded.failure
                """)) {
                int i = 1;
                s.setString(i++, task.id().toString()); s.setString(i++, task.playerId().toString()); s.setString(i++, task.playerName());
                s.setString(i++, task.plan().world()); s.setString(i++, task.plan().operation().name());
                s.setString(i++, GSON.toJson(task.plan().bounds())); s.setString(i++, GSON.toJson(task.plan().mutations()));
                s.setString(i++, task.status().name()); s.setInt(i++, task.cursor()); s.setInt(i++, task.appliedCount());
                s.setString(i++, task.escrowId());
                s.setString(i++, task.charged().toPlainString()); s.setString(i++, task.refunded().toPlainString());
                s.setString(i++, task.createdAt().toString()); s.setString(i++, task.updatedAt().toString()); s.setString(i, task.failure());
                s.executeUpdate();
            }
            return null;
        });
    }

    @Override public void saveProgress(BuildTask task) {
        database.transaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement("""
                UPDATE build_tasks SET status=?,cursor=?,applied_count=?,escrow_id=?,charged=?,refunded=?,updated_at=?,failure=? WHERE id=?
                """)) {
                int i = 1;
                s.setString(i++, task.status().name()); s.setInt(i++, task.cursor()); s.setInt(i++, task.appliedCount());
                s.setString(i++, task.escrowId());
                s.setString(i++, task.charged().toPlainString()); s.setString(i++, task.refunded().toPlainString());
                s.setString(i++, task.updatedAt().toString()); s.setString(i, task.failure());
                s.setString(i, task.id().toString());
                s.executeUpdate();
            }
            return null;
        });
    }

    @Override public void flush() {
        // Synchronous repository — nothing to flush.
    }

    @Override public Optional<BuildTask> find(UUID id) {
        return database.transaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement("SELECT * FROM build_tasks WHERE id=?")) {
                s.setString(1, id.toString());
                try (ResultSet rows = s.executeQuery()) { return rows.next() ? Optional.of(read(rows)) : Optional.empty(); }
            }
        });
    }

    @Override public List<BuildTask> recoverable() {
        return database.transaction(connection -> {
            List<BuildTask> tasks = new ArrayList<>();
            try (PreparedStatement s = connection.prepareStatement("SELECT * FROM build_tasks WHERE status IN ('QUEUED','RUNNING','PAUSED_OFFLINE','PAUSED_SHUTDOWN','CANCELLING','REFUND_PENDING') ORDER BY created_at"); ResultSet rows = s.executeQuery()) {
                while (rows.next()) tasks.add(read(rows));
            }
            return tasks;
        });
    }

    @Override public int activeCount(UUID playerId) {
        return database.transaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement("SELECT count(*) FROM build_tasks WHERE player_id=? AND status NOT IN ('COMPLETED','FAILED','CANCELLED')")) {
                s.setString(1, playerId.toString());
                try (ResultSet rows = s.executeQuery()) { return rows.getInt(1); }
            }
        });
    }

    private static BuildTask read(ResultSet r) throws SQLException {
        Bounds bounds = GSON.fromJson(r.getString("bounds_json"), Bounds.class);
        BlockMutation[] mutations = GSON.fromJson(r.getString("mutations_json"), BlockMutation[].class);
        BuildPlan plan = new BuildPlan(r.getString("world"), OperationKind.valueOf(r.getString("operation")), bounds, List.of(mutations));
        int applied = 0;
        try { applied = r.getInt("applied_count"); } catch (SQLException ignored) { applied = 0; }
        return new BuildTask(UUID.fromString(r.getString("id")), UUID.fromString(r.getString("player_id")), r.getString("player_name"), plan,
                TaskStatus.valueOf(r.getString("status")), r.getInt("cursor"), applied, r.getString("escrow_id"), new BigDecimal(r.getString("charged")),
                new BigDecimal(r.getString("refunded")), Instant.parse(r.getString("created_at")), Instant.parse(r.getString("updated_at")), r.getString("failure"));
    }

    @Override public void close() { database.close(); }

    public void closeQuietly() { database.closeQuietly(); }
}
