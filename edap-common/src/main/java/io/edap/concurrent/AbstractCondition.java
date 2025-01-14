package io.edap.concurrent;

import java.util.concurrent.locks.ReentrantLock;

public abstract class AbstractCondition implements Condition {

    private final ReentrantLock queueLock = new ReentrantLock();

    private final java.util.concurrent.locks.Condition condition = queueLock.newCondition();

    // wake me when the condition is satisfied, or timeout
    @Override
    public void awaitNanos(final long timeout) throws InterruptedException {
        long remaining = timeout;
        queueLock.lock();
        try {
            while(test() && remaining > 0) {
                remaining = condition.awaitNanos(remaining);
            }
        }
        finally {
            queueLock.unlock();
        }
    }

    // wake if signal is called, or wait indefinitely
    @Override
    public void await() throws InterruptedException {
        queueLock.lock();
        try {
            while(test()) {
                condition.await();
            }
        }
        finally {
            queueLock.unlock();
        }
    }

    // tell threads waiting on condition to wake up
    @Override
    public void signal() {
        queueLock.lock();
        try {
            condition.signalAll();
        }
        finally {
            queueLock.unlock();
        }

    }

}

