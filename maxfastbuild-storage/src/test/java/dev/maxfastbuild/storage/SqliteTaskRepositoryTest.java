package dev.maxfastbuild.storage;

import dev.maxfastbuild.api.*;
import dev.maxfastbuild.core.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SqliteTaskRepositoryTest {
    @TempDir Path directory;

    @Test void persistsAndRecoversTaskCursor() {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("test.db"));
        SqliteTaskRepository repository = new SqliteTaskRepository(database);
        repository.initialize();
        UUID id = UUID.randomUUID(), player = UUID.randomUUID();
        BuildPlan plan = new BuildPlan("world", OperationKind.PLACE, new Bounds(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)), List.of(
                new BlockMutation(new BlockPos(0, 0, 0), "minecraft:air", "minecraft:stone"),
                new BlockMutation(new BlockPos(1, 0, 0), "minecraft:air", "minecraft:stone")));
        Instant now = Instant.now();
        BuildTask task = new BuildTask(id, player, "Builder", plan, TaskStatus.RUNNING, 1, "escrow", BigDecimal.TEN, BigDecimal.ZERO, now, now, null);

        repository.save(task);

        assertThat(repository.find(id)).contains(task);
        assertThat(repository.recoverable()).extracting(BuildTask::id).containsExactly(id);
        repository.close();
    }
}
