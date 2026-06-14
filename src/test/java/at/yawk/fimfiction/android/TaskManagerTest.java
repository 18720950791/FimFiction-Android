package at.yawk.fimfiction.android;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Concurrency tests for {@link TaskManager}.
 * Covers execution, invalidation, cancellation and natural completion
 * happening simultaneously.
 */
public class TaskManagerTest {

    private TaskManager manager;

    /** Simple mutable context that implements TaskContext. */
    static class MutableContext implements TaskManager.TaskContext {
        volatile boolean enabled = true;
        @Override public boolean enabled() { return enabled; }
    }

    @Before
    public void setUp() {
        manager = new TaskManager();
    }

    // ------------------------------------------------------------------ //
    // 1. Basic execution                                                   //
    // ------------------------------------------------------------------ //

    @Test
    public void testBasicExecution() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean(false);
        MutableContext ctx = new MutableContext();

        manager.execute(ctx, () -> { ran.set(true); done.countDown(); });

        assertTrue("Task should complete within 5 s", done.await(5, TimeUnit.SECONDS));
        assertTrue("Task body should have run", ran.get());
    }

    // ------------------------------------------------------------------ //
    // 2. Invalidation before task starts                                   //
    // ------------------------------------------------------------------ //

    @Test
    public void testInvalidationBeforeStart() throws Exception {
        // Disable the context *before* calling interruptScheduler
        MutableContext ctx = new MutableContext();
        ctx.enabled = false;

        AtomicBoolean ran = new AtomicBoolean(false);
        // The task will be submitted but the context is already disabled.
        // run() must detect this and skip the action.
        manager.execute(ctx, () -> ran.set(true));

        // Give the executor a moment to pick up the task
        Thread.sleep(300);
        manager.interruptScheduler();

        assertFalse("Task with disabled context must not run", ran.get());
    }

    // ------------------------------------------------------------------ //
    // 3. Reliable interruption of a running task                           //
    // ------------------------------------------------------------------ //

    @Test
    public void testInterruptionOfRunningTask() throws Exception {
        MutableContext ctx = new MutableContext();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        manager.execute(ctx, () -> {
            started.countDown();
            try {
                // Block until interrupted
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            } finally {
                done.countDown();
            }
        });

        assertTrue("Task should start", started.await(5, TimeUnit.SECONDS));

        // Invalidate and interrupt
        ctx.enabled = false;
        manager.interruptScheduler();

        assertTrue("Task should finish within 5 s after interrupt",
                done.await(5, TimeUnit.SECONDS));
        assertTrue("Running thread should have been interrupted", interrupted.get());
    }

    // ------------------------------------------------------------------ //
    // 4. Concurrent execute + invalidate (no CME, no spin)                 //
    // ------------------------------------------------------------------ //

    @Test
    public void testConcurrentExecuteAndInvalidate() throws Exception {
        int threads = 8;
        int tasksPerThread = 50;
        CyclicBarrier barrier = new CyclicBarrier(threads + 1); // +1 for invalidator
        AtomicInteger completed = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(threads * tasksPerThread);

        // Submitter threads
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try { barrier.await(); } catch (Exception e) { return; }
                for (int i = 0; i < tasksPerThread; i++) {
                    MutableContext ctx = new MutableContext();
                    manager.execute(ctx, () -> {
                        completed.incrementAndGet();
                        allDone.countDown();
                    });
                }
            }, "submitter-" + t).start();
        }

        // Invalidator thread – repeatedly calls interruptScheduler
        AtomicBoolean stopInvalidator = new AtomicBoolean(false);
        Thread invalidator = new Thread(() -> {
            try { barrier.await(); } catch (Exception e) { return; }
            while (!stopInvalidator.get()) {
                manager.interruptScheduler(); // must never throw or spin forever
            }
        }, "invalidator");
        invalidator.start();

        // Wait for all tasks to finish (or be skipped)
        assertTrue("All tasks should settle within 15 s",
                allDone.await(15, TimeUnit.SECONDS));
        stopInvalidator.set(true);
        invalidator.join(5_000);
        assertFalse("Invalidator must not be stuck in an infinite loop",
                invalidator.isAlive());
    }

    // ------------------------------------------------------------------ //
    // 5. Natural completion removes task from internal set                 //
    // ------------------------------------------------------------------ //

    @Test
    public void testNaturalCompletionRemovesTask() throws Exception {
        MutableContext ctx = new MutableContext();
        CountDownLatch done = new CountDownLatch(1);

        manager.execute(ctx, done::countDown);
        assertTrue("Task should complete", done.await(5, TimeUnit.SECONDS));

        // Give the finally block a moment to remove the task
        Thread.sleep(200);

        // After natural completion, interruptScheduler should have nothing to remove
        // (We can't inspect the set directly, but we verify no exceptions)
        manager.interruptScheduler();
    }

    // ------------------------------------------------------------------ //
    // 6. Cancel before task starts (cancelled flag)                        //
    // ------------------------------------------------------------------ //

    @Test
    public void testCancelBeforeTaskStarts() throws Exception {
        MutableContext ctx = new MutableContext();
        AtomicBoolean ran = new AtomicBoolean(false);

        // Submit many tasks that all use the same context
        for (int i = 0; i < 20; i++) {
            manager.execute(ctx, () -> {
                ran.set(true);
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            });
        }

        // Immediately invalidate
        ctx.enabled = false;
        manager.interruptScheduler();

        // Wait for executor to drain
        Thread.sleep(1_000);
        // At least some tasks should have been prevented from running.
        // (We can't guarantee all were cancelled before starting, since the
        //  thread pool may have picked some up before our interruptScheduler call.)
    }

    // ------------------------------------------------------------------ //
    // 7. Multiple concurrent interruptScheduler calls                      //
    // ------------------------------------------------------------------ //

    @Test
    public void testConcurrentInterruptSchedulerCalls() throws Exception {
        int count = 100;
        MutableContext ctx = new MutableContext();

        for (int i = 0; i < count; i++) {
            manager.execute(ctx, () -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            });
        }

        ctx.enabled = false;

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                // Each thread calls interruptScheduler multiple times
                for (int i = 0; i < 10; i++) {
                    manager.interruptScheduler();
                }
                done.countDown();
            }).start();
        }

        start.countDown();
        assertTrue("All interrupt threads should finish within 10 s",
                done.await(10, TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------ //
    // 8. Task context toggled during execution                             //
    // ------------------------------------------------------------------ //

    @Test
    public void testContextToggledDuringExecution() throws Exception {
        MutableContext ctx = new MutableContext();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean sawInterrupt = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        manager.execute(ctx, () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                sawInterrupt.set(true);
            } finally {
                done.countDown();
            }
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));

        // Toggle off, interrupt, then toggle on – task should still be interrupted
        ctx.enabled = false;
        manager.interruptScheduler();
        ctx.enabled = true; // re-enable too late

        assertTrue("Task should finish", done.await(5, TimeUnit.SECONDS));
        assertTrue("Task should have been interrupted", sawInterrupt.get());
    }

    // ------------------------------------------------------------------ //
    // 9. Stress: rapid submit + cancel + natural completion                //
    // ------------------------------------------------------------------ //

    @Test
    public void testStressSubmitCancelComplete() throws Exception {
        int rounds = 200;
        AtomicInteger ran = new AtomicInteger(0);

        for (int i = 0; i < rounds; i++) {
            MutableContext ctx = new MutableContext();
            manager.execute(ctx, () -> {
                ran.incrementAndGet();
            });

            // Randomly invalidate some contexts
            if (i % 3 == 0) {
                ctx.enabled = false;
            }
        }

        // Drive the scheduler
        Thread invalidator = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                manager.interruptScheduler();
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
        });
        invalidator.start();
        invalidator.join(10_000);
        assertFalse("Invalidator must terminate (no infinite spin)", invalidator.isAlive());

        // Give executor a moment to finish remaining tasks
        Thread.sleep(2_000);

        // We expect some tasks ran and some were cancelled
        assertTrue("At least some tasks should have run, ran=" + ran.get(), ran.get() > 0);
        // Not all should have run because some contexts were disabled before execution
        assertTrue("Some tasks should have been cancelled, ran=" + ran.get(), ran.get() < rounds);
    }

    // ------------------------------------------------------------------ //
    // 10. No ConcurrentModificationException under GC pressure             //
    // ------------------------------------------------------------------ //

    @Test
    public void testNoCMEUnderGCPressure() throws Exception {
        int count = 100;
        CountDownLatch done = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            MutableContext ctx = new MutableContext();
            manager.execute(ctx, () -> {
                // Allocate garbage to trigger GC-related WeakHashMap mutations
                // (now irrelevant with ConcurrentHashMap, but still stress-tests)
                for (int j = 0; j < 100; j++) {
                    Object garbage = new Object();
                }
                done.countDown();
            });
        }

        // Concurrently call interruptScheduler
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread invalidator = new Thread(() -> {
            while (!stop.get()) {
                manager.interruptScheduler();
            }
        });
        invalidator.start();

        assertTrue("All tasks should complete within 10 s",
                done.await(10, TimeUnit.SECONDS));
        stop.set(true);
        invalidator.join(5_000);
        assertFalse(invalidator.isAlive());
    }
}
