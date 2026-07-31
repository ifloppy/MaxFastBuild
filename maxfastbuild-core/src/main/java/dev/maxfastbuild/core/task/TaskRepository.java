package dev.maxfastbuild.core.task;

import java.util.*;

public interface TaskRepository extends AutoCloseable {
    void initialize();
    void save(BuildTask task);
    void saveProgress(BuildTask task);
    Optional<BuildTask> find(UUID id);
    List<BuildTask> recoverable();
    int activeCount(UUID playerId);
    void flush();
    @Override void close();
    default void closeQuietly() {
        try { close(); } catch (RuntimeException ignored) { }
    }
}
