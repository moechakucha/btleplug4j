// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import moe.prwk.btleplug4j.ValueNotification;

public class StreamRegistry {
    private static final AtomicLong STREAM_ID_GENERATOR = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, StreamContext> REGISTRY =
            new ConcurrentHashMap<>(256);

    public record StreamContext(
            ValueNotification.Subscription subscription, CompletableFuture<Void> handshakeFuture) {}

    public static long register(
            ValueNotification.Subscription subscription, CompletableFuture<Void> handshakeFuture) {
        long id = STREAM_ID_GENERATOR.getAndIncrement();
        REGISTRY.put(id, new StreamContext(subscription, handshakeFuture));
        return id;
    }

    public static long getCurrentId() {
        return STREAM_ID_GENERATOR.get();
    }

    public static StreamContext getContext(long id) {
        return REGISTRY.get(id);
    }

    public static void remove(long id) {
        REGISTRY.remove(id);
    }
}
