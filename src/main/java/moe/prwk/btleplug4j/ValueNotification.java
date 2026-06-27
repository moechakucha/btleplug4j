// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import moe.prwk.btleplug4j.ffi.BtleplugFfi;
import moe.prwk.btleplug4j.util.StreamRegistry;

/**
 * A notification sent from a peripheral due to a change in a value.
 *
 * @param serviceUuid the uuid of the corresponding {@link Service}
 * @param uuid the uuid of the {@link Characteristic}
 * @param data the data received
 */
public record ValueNotification(String serviceUuid, String uuid, byte[] data) {
    public static class Subscription implements Flow.Subscription {
        private final Flow.Subscriber<? super ValueNotification> subscriber;
        private final long streamId;

        private final ConcurrentLinkedQueue<ValueNotification> buffer =
                new ConcurrentLinkedQueue<>();
        private final AtomicLong requested = new AtomicLong(0);

        private volatile boolean cancelled = false;
        private MemorySegment taskHandle;

        public Subscription(Flow.Subscriber<? super ValueNotification> subscriber, long streamId) {
            this.subscriber = subscriber;
            this.streamId = streamId;
        }

        public void setTaskHandle(MemorySegment taskHandle) {
            this.taskHandle = taskHandle;
        }

        public void onData(String serviceUuid, String uuid, byte[] data) {
            if (cancelled) return;

            ValueNotification notification = new ValueNotification(serviceUuid, uuid, data);

            if (requested.get() > 0) {
                long current;
                do {
                    current = requested.get();
                    if (current <= 0) {
                        buffer.offer(notification);
                        return;
                    }
                } while (!requested.compareAndSet(current, current - 1));

                try {
                    subscriber.onNext(notification);
                } catch (Throwable t) {
                    onError(t);
                }
            } else {
                buffer.offer(notification);
            }
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("Request must be > 0"));
                return;
            }
            if (cancelled) return;

            long current;
            do {
                current = requested.get();
                if (current == Long.MAX_VALUE) break;
            } while (!requested.compareAndSet(
                    current, current + n == Long.MAX_VALUE ? Long.MAX_VALUE : current + n));

            drainBuffer();
        }

        private void drainBuffer() {
            while (!buffer.isEmpty() && requested.get() > 0) {
                long current = requested.get();
                if (current <= 0) break;

                if (requested.compareAndSet(current, current - 1)) {
                    ValueNotification notification = buffer.poll();
                    if (notification != null) {
                        try {
                            subscriber.onNext(notification);
                        } catch (Throwable t) {
                            onError(t);
                        }
                    }
                }
            }
        }

        @Override
        public void cancel() {
            if (cancelled) return;
            cancelled = true;

            if (taskHandle != null && taskHandle.address() != 0) {
                BtleplugFfi.ble_peripheral_abort_notifications(taskHandle);
            }
            StreamRegistry.remove(streamId);
            buffer.clear();
        }

        public void onError(Throwable t) {
            if (cancelled) return;
            cancel();
            subscriber.onError(t);
        }
    }
}
