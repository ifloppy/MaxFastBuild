package dev.maxfastbuild.storage;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class EscrowStore {
    private final SqliteDatabase database;
    public EscrowStore(SqliteDatabase database) { this.database = database; }

    public void initialize() {
        database.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS inventory_escrow (
                      reservation_id TEXT NOT NULL, item_key TEXT NOT NULL,
                      item_blob BLOB NOT NULL, original_slot INTEGER NOT NULL,
                      reserved_count INTEGER NOT NULL, consumed_count INTEGER NOT NULL DEFAULT 0,
                      status TEXT NOT NULL CHECK(status IN ('RESERVED','RELEASING','RELEASED')),
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY(reservation_id,item_key,original_slot)
                    )
                    """);
            }
            return null;
        });
    }

    public void reserve(String reservationId, List<Item> items) {
        database.transaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement("INSERT INTO inventory_escrow VALUES(?,?,?,?,?,0,'RESERVED',?)")) {
                for (Item item : items) {
                    s.setString(1, reservationId); s.setString(2, item.itemKey()); s.setBytes(3, item.serialized());
                    s.setInt(4, item.originalSlot()); s.setLong(5, item.count()); s.setString(6, Instant.now().toString()); s.addBatch();
                }
                s.executeBatch();
            }
            return null;
        });
    }

    public void consume(String reservationId, String itemKey, long amount) {
        if (amount < 1) throw new IllegalArgumentException("amount must be positive");
        database.transaction(connection -> {
            long remaining = amount;
            try (PreparedStatement select = connection.prepareStatement("SELECT original_slot,reserved_count,consumed_count FROM inventory_escrow WHERE reservation_id=? AND item_key=? AND status='RESERVED' ORDER BY original_slot")) {
                select.setString(1, reservationId); select.setString(2, itemKey);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next() && remaining > 0) {
                        long available = rows.getLong("reserved_count") - rows.getLong("consumed_count");
                        long use = Math.min(available, remaining);
                        try (PreparedStatement update = connection.prepareStatement("UPDATE inventory_escrow SET consumed_count=consumed_count+?,updated_at=? WHERE reservation_id=? AND item_key=? AND original_slot=?")) {
                            update.setLong(1, use); update.setString(2, Instant.now().toString()); update.setString(3, reservationId); update.setString(4, itemKey); update.setInt(5, rows.getInt("original_slot")); update.executeUpdate();
                        }
                        remaining -= use;
                    }
                }
            }
            if (remaining != 0) throw new IllegalStateException("Escrow underflow for " + itemKey);
            return null;
        });
    }

    public List<Item> remaining(String reservationId) {
        return database.transaction(connection -> {
            List<Item> result = new ArrayList<>();
            try (PreparedStatement s = connection.prepareStatement("SELECT * FROM inventory_escrow WHERE reservation_id=? AND status!='RELEASED' AND reserved_count>consumed_count")) {
                s.setString(1, reservationId);
                try (ResultSet rows = s.executeQuery()) {
                    while (rows.next()) result.add(new Item(rows.getString("item_key"), rows.getBytes("item_blob"), rows.getInt("original_slot"), rows.getLong("reserved_count") - rows.getLong("consumed_count")));
                }
            }
            return result;
        });
    }

    public void released(String reservationId) {
        database.transaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement("UPDATE inventory_escrow SET status='RELEASED',updated_at=? WHERE reservation_id=?")) {
                s.setString(1, Instant.now().toString()); s.setString(2, reservationId); s.executeUpdate();
            }
            return null;
        });
    }

    public record Item(String itemKey, byte[] serialized, int originalSlot, long count) {
        public Item { serialized = serialized.clone(); }
        @Override public byte[] serialized() { return serialized.clone(); }
    }
}
