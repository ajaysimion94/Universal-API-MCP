package com.mcpserver.learning;

import com.mcpserver.learning.LearningModel.Feedback;
import com.mcpserver.learning.LearningModel.Impression;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The single thread through which every learning database write passes.
 * <p>
 * Modelled on {@code connectors/EventQueueWorker} (daemon thread, {@code volatile running},
 * {@code @PostConstruct}/{@code @PreDestroy}) but queue-driven rather than DB-polled, because the
 * work arrives from request threads rather than from a table.
 * <p>
 * Two reasons this exists rather than writing inline. First, latency: a search must not wait on a
 * disk write to record what it just served. Second, and more important, {@code DatasourceConfig}
 * hands out a single shared {@code SingleConnectionDataSource} — sqlite-vec is connection-scoped, so
 * there is no pool — and funnelling all learning writes onto one thread keeps the number of threads
 * contending for that connection at exactly the one that already exists ({@code EventQueueWorker}),
 * rather than adding one per concurrent request.
 * <p>
 * The queue is bounded and drops on overflow. Losing a telemetry row under sustained load is
 * strictly better than blocking a user's search on it, and {@link #droppedWrites()} surfaces the
 * loss in the Learning panel rather than hiding it.
 */
@Component
public class LearningWriter {

    private static final Logger log = LoggerFactory.getLogger(LearningWriter.class);
    private static final int QUEUE_CAPACITY = 512;
    private static final int BATCH_SIZE = 64;

    /** A unit of deferred work. Records rather than an interface hierarchy — there are only three. */
    sealed interface Task permits ImpressionTask, FeedbackTask, DeferredTask {
    }

    record ImpressionTask(Impression impression) implements Task {
    }

    record FeedbackTask(List<Feedback> events) implements Task {
    }

    /**
     * Anything else that must run on the writer thread — reward settling, bandit updates, memory
     * updates, resets, pruning. Keeping these as an opaque callable is what lets components that
     * are constructed *after* the writer (and would otherwise form a dependency cycle) still write.
     */
    record DeferredTask(String name, Runnable action) implements Task {
    }

    private final ImpressionRepository impressions;
    private final FeedbackRepository feedback;

    private final ArrayBlockingQueue<Task> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();

    private volatile boolean running = true;
    private Thread workerThread;

    public LearningWriter(ImpressionRepository impressions, FeedbackRepository feedback) {
        this.impressions = impressions;
        this.feedback = feedback;
    }

    @PostConstruct
    void start() {
        workerThread = new Thread(this::loop, "learning-writer");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (workerThread != null) workerThread.interrupt();
    }

    /** Called from request threads. Never blocks, never throws. */
    public void recordImpression(Impression impression) {
        submit(new ImpressionTask(impression));
    }

    /** Called from the feedback endpoint's request thread. */
    public void recordFeedback(List<Feedback> events) {
        if (events.isEmpty()) return;
        submit(new FeedbackTask(events));
    }

    /** Runs {@code action} on the writer thread, so it shares the same serialized DB access. */
    public void submitDeferred(String name, Runnable action) {
        submit(new DeferredTask(name, action));
    }

    public long droppedWrites() {
        return dropped.get();
    }

    public long processedTasks() {
        return processed.get();
    }

    /** Test seam: block until the queue has drained, so assertions don't race the writer. */
    public boolean awaitDrain(long timeoutMs) {
        long target = accepted.get();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (completed.get() >= target) return true;
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return completed.get() >= target;
    }

    private void submit(Task task) {
        if (queue.offer(task)) {
            accepted.incrementAndGet();
        } else {
            long total = dropped.incrementAndGet();
            // Only the first drop and then every 100th, so a sustained burst cannot flood the log.
            if (total == 1 || total % 100 == 0) {
                log.warn("Learning write queue full — dropped {} task(s). Telemetry is lossy under load "
                        + "by design; search latency is not.", total);
            }
        }
    }

    private void loop() {
        List<Task> batch = new ArrayList<>(BATCH_SIZE);
        while (running) {
            try {
                Task first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                try {
                    drain(batch);
                } finally {
                    completed.addAndGet(batch.size());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                log.warn("learning-writer loop error: {}", exception.getMessage());
            }
        }
    }

    private void drain(List<Task> batch) {
        // Feedback rows are coalesced across the whole batch into one batchUpdate; impressions and
        // deferred work run in arrival order so a settle never overtakes the row it settles.
        List<Feedback> pendingFeedback = new ArrayList<>();
        for (Task task : batch) {
            if (task instanceof FeedbackTask feedbackTask) {
                pendingFeedback.addAll(feedbackTask.events());
            } else {
                flush(pendingFeedback);
                if (task instanceof ImpressionTask impressionTask) {
                    guard("impression", () -> impressions.save(impressionTask.impression()));
                } else if (task instanceof DeferredTask deferred) {
                    guard(deferred.name(), deferred.action());
                }
            }
        }
        flush(pendingFeedback);
    }

    private void flush(List<Feedback> pending) {
        if (pending.isEmpty()) return;
        List<Feedback> copy = List.copyOf(pending);
        pending.clear();
        guard("feedback", () -> feedback.saveAll(copy));
    }

    /** One failing task must never take down the writer thread or the tasks queued behind it. */
    private void guard(String name, Runnable action) {
        try {
            action.run();
            processed.incrementAndGet();
        } catch (Exception exception) {
            log.warn("learning-writer task '{}' failed: {}", name, exception.getMessage());
        }
    }

    /** Convenience for callers that want the deferred form without importing Consumer plumbing. */
    <T> void submitDeferred(String name, T value, Consumer<T> action) {
        submitDeferred(name, () -> action.accept(value));
    }
}
