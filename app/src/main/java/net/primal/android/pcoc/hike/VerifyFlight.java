package net.primal.android.pcoc.hike;

import okhttp3.Call;

/**
 * One verify at a time. Cancel interrupts that thread and aborts its index call.
 * The next run waits until the previous thread has left, so the terrain room is gone.
 */
public final class VerifyFlight {

    private final Object lock = new Object();
    private Thread worker;
    private Call network;

    public interface Body<T> {
        T run() throws HikeUnfinished, IndexUnreachable;
    }

    public <T> T call(Body<T> body) throws HikeUnfinished, IndexUnreachable {
        Thread self = Thread.currentThread();
        synchronized (lock) {
            while (worker != null) {
                try {
                    lock.wait();
                } catch (InterruptedException interrupted) {
                    throw new HikeUnfinished();
                }
            }
            if (self.isInterrupted()) {
                throw new HikeUnfinished();
            }
            worker = self;
        }
        try {
            return body.run();
        } finally {
            synchronized (lock) {
                if (worker == self) {
                    worker = null;
                }
                network = null;
                lock.notifyAll();
            }
            // A pooled thread must not stay interrupted into the next task.
            Thread.interrupted();
        }
    }

    public void bind(Call call) {
        synchronized (lock) {
            network = call;
            Thread current = worker;
            if (current != null && current.isInterrupted()) {
                call.cancel();
            }
        }
    }

    public void unbind(Call call) {
        synchronized (lock) {
            if (network == call) {
                network = null;
            }
        }
    }

    public void cancel() {
        Thread victim;
        Call call;
        synchronized (lock) {
            victim = worker;
            call = network;
        }
        if (call != null) {
            call.cancel();
        }
        if (victim != null) {
            victim.interrupt();
        }
    }
}
