package dev.maxfastbuild.core.task;

import java.util.*;

public interface TaskRepository extends AutoCloseable {
    void initialize();
    void save(BuildTask task);
    Optional<BuildTask> find(UUID id);
    List<BuildTask> recoverable();
    int activeCount(UUID playerId);
    @Override void close();
}
