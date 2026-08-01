package dev.maxfastbuild.storage;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class EconomyLedger {
    private final SqliteDatabase database;
    private final LinkedBlockingQueue<Runnable> queue;
    private final Thread worker;
    private final boolean async;
    private volatile boolean running;

    public EconomyLedger(SqliteDatabase database) {
        this(database, false);
    }

    public EconomyLedger(SqliteDatabase database, boolean async) {
        this.database = database;
        this.async = async;
        if (async) {
            this.running = true;
            this.queue = new LinkedBlockingQueue<>();
            this.worker = new Thread(this::drainLoop, "mfb-ledger-save");
            this.worker.setDaemon(true);
            this.worker.start();
        } else {
            this.queue = null;
            this.worker = null;
        }
    }

    public void initialize() {
        database.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS economy_ledger (
                      transaction_id TEXT PRIMARY KEY,
                      task_id TEXT NOT NULL,
                      player_id TEXT NOT NULL,
                      kind TEXT NOT NULL CHECK(kind IN ('WITHDRAW','REFUND')),
                      amount TEXT NOT NULL,
                      status TEXT NOT NULL CHECK(status IN ('INTENT','SUCCEEDED','FAILED')),
                      error TEXT,
                      updated_at TEXT NOT NULL
                    )
                    """);
            }
            return null;
        });
    }

    public void intent(String transactionId, UUID taskId, UUID playerId, Kind kind, BigDecimal amount) {
        if (async) {
            queue.offer(() -> update(transactionId, taskId, playerId, kind, amount, Status.INTENT, null, Instant.now()));
        } else {
            update(transactionId, taskId, playerId, kind, amount, Status.INTENT, null, Instant.now());
        }
    }

    public void complete(String transactionId, UUID taskId, UUID playerId, Kind kind, BigDecimal amount, boolean success, String error) {
        if (async) {
            queue.offer(() -> update(transactionId, taskId, playerId, kind, amount, success ? Status.SUCCEEDED : Status.FAILED, error, Instant.now()));
        } else {
            update(transactionId, taskId, playerId, kind, amount, success ? Status.SUCCEEDED : Status.FAILED, error, Instant.now());
        }
    }

    private void drainLoop() {
        while (running) {
            try {
                Runnable task = queue.poll(200, TimeUnit.MILLISECONDS);
                if (task != null) {
                    List<Runnable> batch = new ArrayList<>();
                    batch.add(task);
                    queue.drainTo(batch, 50);
                    for (Runnable r : batch) {
                        r.run();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        flush();
    }

    public void flush() {
        if (queue == null) return;
        List<Runnable> pending = new ArrayList<>();
        queue.drainTo(pending);
        for (Runnable r : pending) {
            r.run();
        }
    }

    public void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try { worker.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        flush();
    }

    public List<Entry> pending() {
        return database.transaction(connection -> {
            List<Entry> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM economy_ledger WHERE status='INTENT' ORDER BY updated_at" ); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(read(rows));
            }
            return result;
        });
    }

    private void update(String id, UUID task, UUID player, Kind kind, BigDecimal amount, Status status, String error, Instant now) {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economy_ledger(transaction_id,task_id,player_id,kind,amount,status,error,updated_at)
                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(transaction_id) DO UPDATE SET status=excluded.status,error=excluded.error,updated_at=excluded.updated_at
                """)) {
                statement.setString(1, id); statement.setString(2, task.toString()); statement.setString(3, player.toString());
                statement.setString(4, kind.name()); statement.setString(5, amount.toPlainString()); statement.setString(6, status.name());
                statement.setString(7, error); statement.setString(8, now.toString()); statement.executeUpdate();
            }
            return null;
        });
    }

    private static Entry read(ResultSet rows) throws SQLException {
        return new Entry(rows.getString("transaction_id"), UUID.fromString(rows.getString("task_id")), UUID.fromString(rows.getString("player_id")), Kind.valueOf(rows.getString("kind")), new BigDecimal(rows.getString("amount")), Status.valueOf(rows.getString("status")), rows.getString("error"), Instant.parse(rows.getString("updated_at")));
    }

    public enum Kind { WITHDRAW, REFUND }
    public enum Status { INTENT, SUCCEEDED, FAILED }
    public record Entry(String transactionId, UUID taskId, UUID playerId, Kind kind, BigDecimal amount, Status status, String error, Instant updatedAt) {}
}
