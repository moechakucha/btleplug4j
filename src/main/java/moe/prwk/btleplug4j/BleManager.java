// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j;

import java.lang.foreign.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import moe.prwk.btleplug4j.ffi.BtleplugFfi;
import moe.prwk.btleplug4j.util.CallbackRegistry;
import moe.prwk.btleplug4j.util.NativeLoader;

/**
 * The entry point to the library, providing access to all the Bluetooth adapters on the system. You
 * can obtain an instance from {@link BleManager#BleManager()}.
 */
public class BleManager implements AutoCloseable {
    static {
        NativeLoader.load();
    }

    final MemorySegment ctxPtr;

    record AdapterAccumulator(MemorySegment ctxPtr, List<Adapter> list) {}

    /** Constructs a new {@link BleManager} instance. */
    public BleManager() {
        this.ctxPtr = BtleplugFfi.ble_ctx_new();
        if (this.ctxPtr.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Failed to initialize Rust BLE Context.");
        }
    }

    /**
     * Get a list of all Bluetooth adapters on the system.
     *
     * @return All Bluetooth adapters on the system
     */
    public CompletableFuture<List<Adapter>> getAdapters() {
        CompletableFuture<List<Adapter>> future = new CompletableFuture<>();
        long id =
                CallbackRegistry.register(
                        future,
                        new AdapterAccumulator(ctxPtr, new ArrayList<>()),
                        CallbackRegistry.ExpectedReturnType.ATTACHMENT);

        BtleplugFfi.ble_ctx_get_adapters(
                ctxPtr,
                Shared.SHARED_ADAPTER_CB_STUB,
                Shared.SHARED_RESULT_STUB,
                MemorySegment.ofAddress(id));

        return future;
    }

    /** Release the {@link BleManager}'s memory. */
    @Override
    public void close() {
        BtleplugFfi.ble_ctx_free(ctxPtr);
    }
}
