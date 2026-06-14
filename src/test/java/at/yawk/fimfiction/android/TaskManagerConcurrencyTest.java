package at.yawk.fimfiction.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Concurrency tests for {@link TaskManager} covering execution, context invalidation, cancellation and
 * natural completion happening at the same time. These exercise the bugs that were fixed:
 * <ul>
 *     <li>a task invalidated before it starts must never run its body,</li>
 *     <li>a running task whose context becomes invalid must be reliably interrupted,</li>
 *     <li>finished tasks must be removed from the registry (no leak), and</li>
 *     <li>cancelling concurrently with execution/GC must not spin or throw
 *     {@link java.util.ConcurrentModificationException}.</li>
 * </ul>
 *
 * <p>The test lives in the same package as {@link TaskManager} and reads the private {@code tasks}
 * registry via reflection so it can assert deterministic removal without changing production code.
 */
public class TaskManagerConcurrencyTest {

    /** A {@link TaskManager.TaskContext} whose validity can be flipped from another thread. */
    static final class FlagContext implements TaskManager.TaskContext {
        volatile boolean enabled = true;

        @Override
        public boolean enabled() {
            return enabled;
        }
    }

    private static int activeCount(TaskManager tm) throws Exception {
        Field field = TaskManager.class.getDeclaredField("tasks");
        field.setAccessible(true);
        return ((Collection<?>) field.get(tm)).size();
    }

    private static void awaitDrained(TaskManager tm, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (activeCount(tm) != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals("task registry should drain to 0 once tasks are finished/cancelled", 0, activeCount(tm));
    }

    @Test(timeout = 5000)
    public void invalidatedBeforeStart_doesNotRunBody() throws Exception {
        TaskManager tm = new TaskManager();
        FlagContext ctx = new FlagContext();
        ctx.enabled = false; // already invalid before the task is ever submitted

        final AtomicBoolean ran = new AtomicBoolean(false);
        tm.execute(ctx, new Runnable() {
            @Override
            public void run() {
                ran.set(true);
            }
        });
        tm.interruptScheduler();

        awaitDrained(tm, 2000); // the pool thread picked the task up and skipped it
        assertFalse("task body must not run when its context is invalid before start", ran.get());
    }

    @Test(timeout = 10000)
    public void runningTask_isInterrupted() throws Exception {
        TaskManager tm = new TaskManager();
        final FlagContext ctx = new FlagContext();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean(false);

        tm.execute(ctx, new Runnable() {
            @Override
            public void run() {
                started.countDown();
                try {
                    Thread.sleep(60000); // block until interrupted
                } catch (InterruptedException e) {
                    interrupted.set(true);
                } finally {
                    done.countDown();
                }
            }
        });

        assertTrue("task should have started running", started.await(3, TimeUnit.SECONDS));
        ctx.enabled = false;        // invalidate while running
        tm.interruptScheduler();    // must interrupt promptly, not block until the task ends

        assertTrue("running task should be interrupted promptly", done.await(3, TimeUnit.SECONDS));
        assertTrue("task should observe the interruption", interrupted.get());
        awaitDrained(tm, 2000);
    }

    @Test(timeout = 10000)
    public void naturalCompletion_drainsRegistry() throws Exception {
        TaskManager tm = new TaskManager();
        FlagContext ctx = new FlagContext();
        final int n = 50;
        final AtomicInteger ran = new AtomicInteger(0);

        for (int i = 0; i < n; i++) {
            tm.execute(ctx, new Runnable() {
                @Override
                public void run() {
                    ran.incrementAndGet();
                }
            });
        }

        long deadline = System.currentTimeMillis() + 5000;
        while (ran.get() < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals("all valid tasks should run to completion", n, ran.get());
        awaitDrained(tm, 3000); // each finished task removes itself from the registry
    }

    @Test(timeout = 30000)
    public void concurrentStress_noExceptionsNoSpin() throws Exception {
        final TaskManager tm = new TaskManager();
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
        final List<FlagContext> contexts = Collections.synchronizedList(new ArrayList<FlagContext>());

        final int producers = 6;
        final int cancellers = 3;
        final int iterations = 200;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(producers + cancellers);

        for (int p = 0; p < producers; p++) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            final FlagContext ctx = new FlagContext();
                            contexts.add(ctx);
                            // A short blocking task (exercises interruption) ...
                            tm.execute(ctx, new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        Thread.sleep(2);
                                    } catch (InterruptedException ignored) {
                                        // expected when the context is invalidated mid-run
                                    }
                                }
                            });
                            // ... and an instant one (exercises natural completion / self-removal).
                            tm.execute(ctx, new Runnable() {
                                @Override
                                public void run() {
                                    // returns immediately
                                }
                            });
                            if ((i & 7) == 0) {
                                ctx.enabled = false;     // invalidate
                                tm.interruptScheduler();  // cancel concurrently with other producers
                            }
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        finished.countDown();
                    }
                }
            }, "producer-" + p);
            thread.start();
        }

        for (int c = 0; c < cancellers; c++) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < iterations * 2; i++) {
                            List<FlagContext> snapshot = new ArrayList<FlagContext>(contexts);
                            if (!snapshot.isEmpty()) {
                                snapshot.get(i % snapshot.size()).enabled = false;
                            }
                            tm.interruptScheduler(); // sweep concurrently with producers and each other
                            Thread.yield();
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        finished.countDown();
                    }
                }
            }, "canceller-" + c);
            thread.start();
        }

        start.countDown();
        assertTrue("stress workers should finish without spinning", finished.await(25, TimeUnit.SECONDS));

        // Quiesce: invalidate everything and sweep once more; the registry must then drain deterministically.
        for (FlagContext ctx : new ArrayList<FlagContext>(contexts)) {
            ctx.enabled = false;
        }
        tm.interruptScheduler();
        awaitDrained(tm, 5000);

        assertTrue("no exceptions (incl. ConcurrentModificationException) during concurrent cancel: " + errors,
                errors.isEmpty());
    }
}
