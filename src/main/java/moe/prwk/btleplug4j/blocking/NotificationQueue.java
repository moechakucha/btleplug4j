// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j.blocking;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import moe.prwk.btleplug4j.ValueNotification;
import org.jspecify.annotations.NonNull;

class NotificationQueue
        implements Flow.Subscriber<ValueNotification>,
                BlockingQueue<ValueNotification>,
                AutoCloseable {
    private final BlockingQueue<ValueNotification> queue;
    private final int capacity;
    private Flow.Subscription subscription;
    private volatile boolean closed = false;
    private Throwable error = null;

    public NotificationQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        this.subscription.request(capacity);
    }

    @Override
    public void onNext(ValueNotification item) {
        try {
            if (!closed) {
                queue.put(item);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onError(Throwable throwable) {
        this.error = throwable;
    }

    @Override
    public void onComplete() {
        this.closed = true;
    }

    @Override
    public boolean add(@NonNull ValueNotification valueNotification) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean offer(@NonNull ValueNotification valueNotification) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ValueNotification remove() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ValueNotification poll() {
        checkError();
        if (closed && queue.isEmpty()) {
            return null;
        }

        ValueNotification notification = queue.poll();

        if (subscription != null && !closed) {
            subscription.request(1);
        }

        return notification;
    }

    @Override
    public ValueNotification element() {
        return queue.element();
    }

    @Override
    public ValueNotification peek() {
        return queue.peek();
    }

    @Override
    public void put(@NonNull ValueNotification valueNotification) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean offer(ValueNotification valueNotification, long timeout, @NonNull TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NonNull ValueNotification take() throws InterruptedException {
        checkError();
        if (closed && queue.isEmpty()) {
            throw new IllegalStateException("Queue is closed and empty");
        }

        ValueNotification notification = queue.take();

        if (subscription != null && !closed) {
            subscription.request(1);
        }

        return notification;
    }

    public ValueNotification poll(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException {
        checkError();
        if (closed && queue.isEmpty()) {
            return null;
        }

        ValueNotification notification = queue.poll(timeout, unit);
        if (notification != null && subscription != null && !closed) {
            subscription.request(1);
        }
        return notification;
    }

    @Override
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(@NonNull Collection<?> c) {
        return queue.containsAll(c);
    }

    @Override
    public boolean addAll(@NonNull Collection<? extends ValueNotification> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(@NonNull Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(@NonNull Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return queue.contains(o);
    }

    @Override
    public @NonNull Iterator<ValueNotification> iterator() {
        return queue.iterator();
    }

    @Override
    public Object @NonNull [] toArray() {
        return queue.toArray();
    }

    @Override
    public <T> T @NonNull [] toArray(T @NonNull [] a) {
        return queue.toArray(a);
    }

    @Override
    public int drainTo(@NonNull Collection<? super ValueNotification> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int drainTo(@NonNull Collection<? super ValueNotification> c, int maxElements) {
        throw new UnsupportedOperationException();
    }

    private void checkError() {
        if (error != null) {
            throw new RuntimeException("Error occurred in underlying Stream", error);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (subscription != null) {
                subscription.cancel();
            }
            queue.clear();
        }
    }
}
