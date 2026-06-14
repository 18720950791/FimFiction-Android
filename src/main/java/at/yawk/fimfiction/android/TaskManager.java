package at.yawk.fimfiction.android;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j;

/**
 * Task Executor that supports invalidating tasks.
 *
 * @author Jonas Konrad (yawkat)
 */
@Log4j
public class TaskManager {
    private final Executor executor =
            new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
    /**
     * Registry of tasks that have not finished yet. A {@link CopyOnWriteArraySet} gives thread-safe,
     * snapshot-based iteration (so cancellation never throws {@link ConcurrentModificationException} and
     * never has to spin/retry) together with deterministic add/remove. Unlike the previous
     * {@code WeakHashMap}-backed set, entries here are held strongly, so every {@link Task} removes
     * itself from this registry once it reaches a terminal state.
     */
    private final Collection<Task> tasks = new CopyOnWriteArraySet<Task>();

    public void execute(TaskContext context, Runnable task) {
        Task taskElement = new Task(context, task, tasks);
        // Register before submitting so a concurrent interruptScheduler() can always observe the task.
        tasks.add(taskElement);
        executor.execute(taskElement);
    }

    public void interruptScheduler() {
        // Single bounded pass over a stable snapshot: no retry loop and no ConcurrentModificationException.
        for (Task task : tasks) {
            try {
                if (task.checkForInterrupt()) {
                    log.debug("Removing finished/invalid task " + task + " from task queue");
                    tasks.remove(task);
                }
            } catch (Throwable t) {
                log.error("Failed while interrupting scheduled task " + task, t);
            }
        }
    }

    public static interface TaskContext {
        boolean enabled();
    }
}

@Log4j
@RequiredArgsConstructor
class Task implements Runnable {
    private enum State { NEW, RUNNING, DONE }

    private final TaskManager.TaskContext owner;
    private final Runnable action;
    private final Collection<Task> registry;

    private State state = State.NEW;
    private Thread runningThread = null;

    private boolean isValid() { return owner.enabled(); }

    @Override
    public void run() {
        synchronized (this) {
            // Skip if the task was already cancelled/finished, or its context became invalid before it
            // started. This closes the start/cancel race: this gate and checkForInterrupt() share the
            // same lock, so a task can never both pass the gate and escape interruption.
            if (state != State.NEW || !isValid()) {
                state = State.DONE;
                runningThread = null;
                registry.remove(this);
                return;
            }
            state = State.RUNNING;
            runningThread = Thread.currentThread();
        }
        try {
            action.run();
        } catch (Throwable t) {
            log.error("Failed to execute task", t);
        } finally {
            synchronized (this) {
                state = State.DONE;
                runningThread = null;
            }
            // Clear any pending interrupt so the pooled thread is clean for its next task.
            Thread.interrupted();
            registry.remove(this);
        }
    }

    /**
     * Invoked by {@link TaskManager#interruptScheduler()} while holding only this task's monitor. If the
     * owning context is no longer valid, this prevents a not-yet-started task from running and interrupts
     * a currently running one. The monitor is never held while the wrapped action executes, so this call
     * can always interrupt promptly instead of blocking until the task finishes.
     *
     * @return {@code true} if the task has reached a terminal state and may be removed from the registry.
     */
    boolean checkForInterrupt() {
        synchronized (this) {
            if (isValid()) {
                return state == State.DONE;
            }
            switch (state) {
                case NEW:
                    // Force run() to observe a non-NEW state so it never executes the action.
                    state = State.DONE;
                    runningThread = null;
                    return true;
                case RUNNING:
                    if (runningThread != null) {
                        log.debug("Interrupting invalid running task " + this);
                        runningThread.interrupt();
                    }
                    return false;
                default: // DONE
                    return true;
            }
        }
    }
}
