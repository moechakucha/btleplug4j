// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j.util;

import java.lang.foreign.Arena;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class CallbackRegistry {
    public enum ExpectedReturnType {
        VOID,
        BYTES,
        ATTACHMENT
    }

    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, RegistryEntry<?>> REGISTRY =
            new ConcurrentHashMap<>(1024);
    private static final ScheduledExecutorService CLEANUP_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "FFI-Callback-Cleanup-Daemon");
                        t.setDaemon(true);
                        return t;
                    });
    private static final long TIMEOUT_MILLIS = 30_000;

    static {
        CLEANUP_SCHEDULER.scheduleAtFixedRate(
                CallbackRegistry::cleanupStaleEntries, 5, 5, TimeUnit.SECONDS);
    }

    public static class RegistryEntry<T> {
        public final CompletableFuture<T> future;
        public final long registerTimestamp;
        public final Object attachment;
        public final ExpectedReturnType returnType;
        public final Arena tiedArena;

        RegistryEntry(
                CompletableFuture<T> future,
                Object attachment,
                ExpectedReturnType returnType,
                Arena tiedArena) {
            this.future = future;
            this.registerTimestamp = System.currentTimeMillis();
            this.attachment = attachment;
            this.returnType = returnType;
            this.tiedArena = tiedArena;
        }
    }

    public static <T> long register(CompletableFuture<T> future, ExpectedReturnType returnType) {
        return register(future, null, returnType, null);
    }

    public static <T> long register(
            CompletableFuture<T> future, Object attachment, ExpectedReturnType returnType) {
        return register(future, attachment, returnType, null);
    }

    public static <T> long register(
            CompletableFuture<T> future,
            Object attachment,
            ExpectedReturnType returnType,
            Arena tiedArena) {
        long id = ID_GENERATOR.getAndIncrement();
        REGISTRY.put(id, new RegistryEntry<>(future, attachment, returnType, tiedArena));
        return id;
    }

    @SuppressWarnings("unchecked")
    public static <T> RegistryEntry<T> removeAndGet(long id) {
        return (RegistryEntry<T>) REGISTRY.remove(id);
    }

    public static Object getAttachment(long id) {
        RegistryEntry<?> entry = REGISTRY.get(id);
        return entry != null ? entry.attachment : null;
    }

    public static void remove(long id) {
        REGISTRY.remove(id);
    }

    private static void cleanupStaleEntries() {
        long now = System.currentTimeMillis();
        REGISTRY.forEach(
                (id, entry) -> {
                    if (now - entry.registerTimestamp > TIMEOUT_MILLIS) {
                        if (REGISTRY.remove(id, entry)) {
                            try {
                                entry.future.completeExceptionally(
                                        new RuntimeException(
                                                "Rust side failed to respond within "
                                                        + TIMEOUT_MILLIS
                                                        + "ms"));
                            } finally {
                                if (entry.tiedArena != null && entry.tiedArena.scope().isAlive()) {
                                    entry.tiedArena.close();
                                }
                            }
                        }
                    }
                });
    }

    public static void shutdown() {
        CLEANUP_SCHEDULER.shutdownNow();
    }
}
