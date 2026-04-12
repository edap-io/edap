package io.edap.concurrent;

import java.io.Serializable;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class DisruptorBlockingQueue<E> extends MultithreadConcurrentQueue<E>
        implements Serializable, Iterable<E>, Collection<E>, BlockingQueue<E>, Queue<E> {

    private final Condition queueNotFullCondition;
    private final Condition queueNotEmptyCondition;

    public DisruptorBlockingQueue(final int capacity) {
        // waiting locking gives substantial performance improvements
        // but makes disruptor aggressive with cpu utilization
        this(capacity, SpinPolicy.WAITING);
    }

    public DisruptorBlockingQueue(final int capacity, final SpinPolicy spinPolicy) {
        super(capacity);

        switch(spinPolicy) {
            case BLOCKING:
                queueNotFullCondition = new QueueNotFull();
                queueNotEmptyCondition = new QueueNotEmpty();
                break;
            case SPINNING:
                queueNotFullCondition = new SpinningQueueNotFull();
                queueNotEmptyCondition = new SpinningQueueNotEmpty();
                break;
            case WAITING:
            default:
                queueNotFullCondition = new WaitingQueueNotFull();
                queueNotEmptyCondition = new WaitingQueueNotEmpty();
        }
    }

    public DisruptorBlockingQueue(final int capacity, Collection<? extends E> c) {
        this(capacity);
        for (final E e : c) {
            offer(e);
        }
    }

    @Override
    public final boolean offer(E e) {
        try {
            return super.offer(e);
        } finally {
            queueNotEmptyCondition.signal();
        }
    }

    @Override
    public final E poll() {
        final E e = super.poll();
        // not full now
        queueNotFullCondition.signal();
        return e;
    }

    @Override
    public E remove() {
        return poll();
    }

    @Override
    public int remove(final E[] e) {
        final int n = super.remove(e);
        // queue can not be full
        queueNotFullCondition.signal();
        return n;
    }

    @Override
    public E element() {
        final E val = peek();
        if (val != null)
            return val;
        throw new NoSuchElementException("No element found.");
    }

    @Override
    public void put(E e) throws InterruptedException {
        // add object, wait for space to become available
        while (offer(e) == false) {
            if(Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            queueNotFullCondition.await();
        }
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        for (;;) {
            if (offer(e)) {
                return true;
            } else {

                // wait for available capacity and try again
                if (!Condition.waitStatus(timeout, unit, queueNotFullCondition)) return false;
            }
        }
    }

    @Override
    public E take() throws InterruptedException {
        for (;;) {
            E pollObj = poll();
            if (pollObj != null) {
                return pollObj;
            }
            if(Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            queueNotEmptyCondition.await();
        }
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        for(;;) {
            E pollObj = poll();
            if(pollObj != null) {
                return pollObj;
            } else {
                // wait for the queue to have at least one element or time out
                if(!Condition.waitStatus(timeout, unit, queueNotEmptyCondition)) return null;
            }
        }
    }

    @Override
    public void clear() {
        super.clear();
        queueNotFullCondition.signal();
    }

    @Override
    public int remainingCapacity() {
        return size - size();
    }

    @Override
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, size());
    }

    @Override
    // drain the whole queue at once
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (this == c) {
            throw new IllegalArgumentException("Can not drain to self.");
        }

        final E[] pollObj = (E[])new Object[Math.min(size(), maxElements)];
        final int nEle = remove(pollObj);
        int nRead = 0;

        for (int i=0;i<nEle;i++) {
            if (c.add((E) pollObj[i])) {
                nRead++;
            }
        }

        return nRead;
    }

    @Override
    public Object[] toArray() {
        final E[] e = (E[]) new Object[size()];
        toArray(e);

        return e;
    }

    @Override
    public <T> T[] toArray(T[] a) {

        remove((E[])a);

        return a;
    }

    @Override
    public boolean add(E e) {
        if (offer(e)) {
            return true;
        }
        throw new IllegalStateException("queue is full");
    }

    @Override
    public boolean remove(Object o) {
        for (;;) {
            final long headSeq = head.sum();
            if (headCursor.compareAndSet(headSeq, headSeq + 1)) {
                for (;;) {
                    final long tailSeq = tail.sum();
                    if (tailCursor.compareAndSet(tailSeq, tailSeq + 1)) {
                        int n = 0;

                        for (int i = 0; i < size(); i++) {
                            final int slot = (int) ((head.sum() + i) & mask);
                            if (buffer[slot] != null && buffer[slot].equals(o)) {
                                n++;

                                for (int j = i; j > 0; j--) {
                                    final int cSlot = (int) ((head.sum() + j - 1) & mask);
                                    final int nextSlot = (int) ((head.sum() + j) & mask);
                                    buffer[nextSlot] = buffer[cSlot];
                                }
                            }
                        }

                        if (n > 0) {
                            headCursor.set(headSeq + n);
                            tailCursor.set(tailSeq);
                            head.add(n);

                            queueNotFullCondition.signal();

                            return true;
                        } else {
                            tailCursor.set(tailSeq);
                            headCursor.set(headSeq);

                            return false;
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (final Object o : c) {
            if (!contains(o)) return false;
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean rc = false;
        for (final E e : c) {
            if (offer(e)) {
                rc = true;
            }
        }
        return rc;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean isChanged = false;
        for (final Object o : c) {
            if (remove(o)) {
                isChanged = true;
            }
        }
        return isChanged;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean isChanged = false;

        for (int i = 0; i < size(); i++) {
            final int headSlot = (int) ((head.sum() + i) & mask);
            if (buffer[headSlot] != null && !c.contains(buffer[headSlot])) {
                if (remove(buffer[headSlot])) {
                    // backtrack one step, we just backed values up at this point
                    i--;
                    isChanged = true;
                }

            }
        }

        return isChanged;
    }

    @Override
    public Iterator<E> iterator() {
        return new RingIter();
    }

    private boolean isFull() {
        final long queueStart = tail.sum() - size;
        return head.sum() == queueStart;
    }

    private final class RingIter implements Iterator<E> {
        int dx = 0;

        E lastObj = null;

        private RingIter() {

        }

        @Override
        public boolean hasNext() {
            return dx < size();
        }

        @Override
        public E next() {
            final long pollPos = head.sum();
            final int slot = (int) ((pollPos + dx++) & mask);
            lastObj = buffer[slot];
            return lastObj;
        }

        @Override
        public void remove() {
            DisruptorBlockingQueue.this.remove(lastObj);
        }
    }


    private final class QueueNotFull extends AbstractCondition {

        @Override
        // @return boolean - true if the queue is full
        public final boolean test() {
            return isFull();
        }
    }

    private final class QueueNotEmpty extends AbstractCondition {
        @Override
        // @return boolean - true if the queue is empty
        public final boolean test() {
            return isEmpty();
        }
    }

    private final class WaitingQueueNotFull extends AbstractWaitingCondition {

        @Override
        // @return boolean - true if the queue is full
        public final boolean test() {
            return isFull();
        }
    }

    private final class WaitingQueueNotEmpty extends AbstractWaitingCondition {
        @Override
        // @return boolean - true if the queue is empty
        public final boolean test() {
            return isEmpty();
        }
    }

    private final class SpinningQueueNotFull extends AbstractSpinningCondition {

        @Override
        // @return boolean - true if the queue is full
        public final boolean test() {
            return isFull();
        }
    }

    private final class SpinningQueueNotEmpty extends AbstractSpinningCondition {
        @Override
        // @return boolean - true if the queue is empty
        public final boolean test() {
            return isEmpty();
        }
    }
}
