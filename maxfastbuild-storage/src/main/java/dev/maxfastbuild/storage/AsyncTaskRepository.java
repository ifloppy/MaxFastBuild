package dev.maxfastbuild.storage;

import dev.maxfastbuild.core.task.*;
import java.util.*;
import java.util.concurrent.*;

public final class AsyncTaskRepository implements TaskRepository {
    private final TaskRepository delegate;
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(10_000);
    private final Thread worker;
    private final int batchSize;
    private final long maxDelayMs;
    private volatile boolean running;

    public AsyncTaskRepository(TaskRepository delegate, int batchSize, long maxDelayMs) {
        this.delegate = delegate;
        this.batchSize = Math.max(1, batchSize);
        this.maxDelayMs = Math.max(1, maxDelayMs);
        this.running = true;
        this.worker = new Thread(this::drainLoop, "mfb-async-save");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    private void drainLoop() {
        while (running) {
            try {
                Runnable task = queue.poll(maxDelayMs, TimeUnit.MILLISECONDS);
                if (task != null) {
                    List<Runnable> batch = new ArrayList<>();
                    batch.add(task);
                    queue.drainTo(batch, batchSize - 1);
                    for (Runnable r : batch) {
                        r.run();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Drain remaining items after thread stops.
        flush();
    }

    @Override public void initialize() {
        delegate.initialize();
    }

    @Override public void save(BuildTask task) {
        BuildTask copy = task;
        queue.offer(() -> delegate.save(copy));
    }

    @Override public void saveProgress(BuildTask task) {
        BuildTask copy = task;
        queue.offer(() -> delegate.saveProgress(copy));
    }

    @Override public void flush() {
        List<Runnable> pending = new ArrayList<>();
        queue.drainTo(pending);
        for (Runnable r : pending) {
            r.run();
        }
    }

    @Override public Optional<BuildTask> find(UUID id) {
        return delegate.find(id);
    }

    @Override public List<BuildTask> recoverable() {
        return delegate.recoverable();
    }

    @Override public int activeCount(UUID playerId) {
        return delegate.activeCount(playerId);
    }

    @Override public void close() {
        running = false;
        worker.interrupt();
        try { worker.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // drainLoop's post-loop flush already ran; flush any late arrivals.
        flush();
        delegate.close();
    }
}