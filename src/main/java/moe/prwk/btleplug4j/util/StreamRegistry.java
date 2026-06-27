// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j.util;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import moe.prwk.btleplug4j.Peripheral.NotificationSubscription;

public class StreamRegistry {
    private static final AtomicLong STREAM_ID_GENERATOR = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, StreamContext> REGISTRY =
            new ConcurrentHashMap<>(256);

    public static class StreamContext {
        public final BiConsumer<String, byte[]> listener;
        public final CompletableFuture<NotificationSubscription> handshakeFuture;
        public MemorySegment taskHandle;

        public StreamContext(
                BiConsumer<String, byte[]> listener,
                CompletableFuture<NotificationSubscription> handshakeFuture) {
            this.listener = listener;
            this.handshakeFuture = handshakeFuture;
        }
    }

    public static long register(
            BiConsumer<String, byte[]> listener,
            CompletableFuture<NotificationSubscription> handshakeFuture) {
        long id = STREAM_ID_GENERATOR.getAndIncrement();
        REGISTRY.put(id, new StreamContext(listener, handshakeFuture));
        return id;
    }

    public static void setTaskHandle(long id, MemorySegment taskHandle) {
        StreamContext ctx = REGISTRY.get(id);
        if (ctx != null) {
            ctx.taskHandle = taskHandle;
        }
    }

    public static StreamContext getContext(long id) {
        return REGISTRY.get(id);
    }

    public static void remove(long id) {
        REGISTRY.remove(id);
    }

    public static void shutdown() {
        REGISTRY.clear();
    }
}
