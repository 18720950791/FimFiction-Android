package at.yawk.fimfiction.android;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    private final Set<Task> tasks = Collections.newSetFromMap(new ConcurrentHashMap<Task, Boolean>());

    public void execute(TaskContext context, Runnable task) {
        Task taskElement = new Task(context, task, tasks);
        // Register before submitting so interruptScheduler() can see it immediately
        tasks.add(taskElement);
        executor.execute(taskElement);
    }

    public void interruptScheduler() {
        try {
            for (Iterator<Task> itr = tasks.iterator(); itr.hasNext(); ) {
                Task task = itr.next();
                if (!task.isValid()) {
                    task.cancel();
                    log.debug("Removing invalid task " + task + " from task queue (" + tasks.size() + " remaining)");
                    itr.remove();
                }
            }
        } catch (Exception e) {
            log.error("Failed while interrupting scheduled tasks", e);
        }
    }

    public static interface TaskContext {
        boolean enabled();
    }
}

@Log4j
class Task implements Runnable {
    private final TaskManager.TaskContext owner;
    private final Runnable action;
    private final Set<Task> container;
    private volatile Thread runningThread = null;
    private volatile boolean cancelled = false;

    Task(TaskManager.TaskContext owner, Runnable action, Set<Task> container) {
        this.owner = owner;
        this.action = action;
        this.container = container;
    }

    public boolean isValid() {
        return !cancelled && owner.enabled();
    }

    /**
     * Mark this task as cancelled and interrupt it if currently running.
     */
    public void cancel() {
        cancelled = true;
        Thread t = runningThread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void run() {
        // Fast-path: if already cancelled/invalid before we start, skip entirely
        if (!isValid()) {
            container.remove(this);
            return;
        }
        // Publish our thread reference (volatile write)
        runningThread = Thread.currentThread();
        try {
            // Double-check after publishing: cancel() may have fired between
            // the first check and the volatile write above
            if (isValid()) {
                action.run();
            }
        } catch (Throwable t) {
            if (isValid()) {
                log.error("Failed to execute task", t);
            }
            // else: exception caused by cancellation interrupt - expected
        } finally {
            Thread.interrupted(); // clear interrupt flag
            runningThread = null;
            container.remove(this);
        }
    }

    public void checkForInterrupt() {
        if (!isValid()) {
            Thread t = runningThread;
            if (t != null) {
                t.interrupt();
            }
        }
    }
}
