package dev.stockflow.warehouse.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bounded queue and consumer backpressure behavior under load (Phase 9, unit 9.7: topic 47).
 *
 * <p>Compares what happens when a producer submits work faster than consumers can finish:
 * <ol>
 *   <li>{@link ThreadPoolExecutor.CallerRunsPolicy} pushes back on the producer by running excess tasks on the
 *       submitting thread, keeping queue depth at or below capacity and dropping zero tasks.</li>
 *   <li>{@link ThreadPoolExecutor.AbortPolicy} sheds excess load immediately with {@link RejectedExecutionException}
 *       once the workers and bounded queue are full.</li>
 *   <li>A non-blocking {@link Semaphore} admission limiter caps concurrent in-flight work.</li>
 * </ol>
 */
class BackpressureTest {

    @Test
    @DisplayName("Bounded queue with CallerRunsPolicy throttles the producer to consumer speed, keeps queue depth bounded, and drops nothing")
    void callerRunsBackpressureThrottlesProducerAndDropsZeroItems() throws Exception {
        int workers = 2;
        int queueCapacity = 4;
        int totalTasks = 30;
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger ranOnProducerThread = new AtomicInteger();
        AtomicInteger maxObservedQueueDepth = new AtomicInteger();
        long producerThreadId = Thread.currentThread().threadId();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            for (int i = 0; i < totalTasks; i++) {
                int depth = executor.getQueue().size();
                maxObservedQueueDepth.accumulateAndGet(depth, Math::max);
                executor.execute(() -> {
                    if (Thread.currentThread().threadId() == producerThreadId) {
                        ranOnProducerThread.incrementAndGet();
                    }
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    completed.incrementAndGet();
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        System.out.println("CallerRunsPolicy: completed=" + completed.get()
                + ", ranOnProducerThread=" + ranOnProducerThread.get()
                + ", maxQueueDepth=" + maxObservedQueueDepth.get());
        assertThat(completed.get()).isEqualTo(totalTasks);
        assertThat(ranOnProducerThread.get()).as("producer was slowed by running tasks itself").isPositive();
        assertThat(maxObservedQueueDepth.get()).isLessThanOrEqualTo(queueCapacity);
    }

    @Test
    @DisplayName("Bounded queue with AbortPolicy accepts exactly workers + queueCapacity tasks and sheds the rest immediately")
    void abortPolicyShedsExcessLoadImmediatelyWhenQueueFills() throws Exception {
        int workers = 2;
        int queueCapacity = 5;
        int totalSubmitted = 20;
        CountDownLatch holdWorkers = new CountDownLatch(1);
        CountDownLatch workersBusy = new CountDownLatch(workers);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            for (int i = 0; i < totalSubmitted; i++) {
                try {
                    executor.execute(() -> {
                        workersBusy.countDown();
                        try {
                            holdWorkers.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    accepted.incrementAndGet();
                } catch (RejectedExecutionException ex) {
                    rejected.incrementAndGet();
                }
            }
            assertThat(workersBusy.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.getQueue().size()).isEqualTo(queueCapacity);
        } finally {
            holdWorkers.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(accepted.get()).isEqualTo(workers + queueCapacity);
        assertThat(rejected.get()).isEqualTo(totalSubmitted - (workers + queueCapacity));
    }

    @Test
    @DisplayName("Semaphore admission control caps concurrent in-flight consumer permits")
    void semaphoreAdmissionControlCapsInFlightWork() {
        Semaphore permits = new Semaphore(3);
        int acquired = 0;
        int shed = 0;
        for (int i = 0; i < 10; i++) {
            if (permits.tryAcquire()) {
                acquired++;
            } else {
                shed++;
            }
        }
        assertThat(acquired).isEqualTo(3);
        assertThat(shed).isEqualTo(7);
        permits.release(3);
        assertThat(permits.availablePermits()).isEqualTo(3);
    }
}
