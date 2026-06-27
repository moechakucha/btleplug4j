// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j;

import java.lang.foreign.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import moe.prwk.btleplug4j.ffi.BtleplugFfi;
import moe.prwk.btleplug4j.util.CallbackRegistry;

/**
 * The “client” of BLE. It’s able to scan for and establish connections to peripherals. Can be
 * obtained from {@link BleManager#getAdapters()}.
 */
public class Adapter implements AutoCloseable {
    private final MemorySegment ctxPtr;
    private final MemorySegment adapterPtr;

    record PeripheralAccumulator(MemorySegment ctxPtr, List<Peripheral> list) {}

    /** Not supposed to be called externally in a direct manner. */
    Adapter(MemorySegment ctxPtr, MemorySegment adapterPtr) {
        this.ctxPtr = ctxPtr;
        this.adapterPtr = adapterPtr;
    }

    /**
     * Start a scan for BLE devices with no filter specified.
     *
     * <p>See {@link Adapter#startScan(List)} for more details.
     */
    public CompletableFuture<Void> startScan() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long id = CallbackRegistry.register(future, CallbackRegistry.ExpectedReturnType.VOID);

        BtleplugFfi.ble_adapter_start_scan(
                ctxPtr, adapterPtr, Shared.SHARED_RESULT_STUB, MemorySegment.ofAddress(id));
        return future;
    }

    /**
     * Starts a scan for BLE devices.
     *
     * <p>This scan will generally continue until explicitly stopped, although this may depend on
     * your Bluetooth adapter. Discovered devices will be announced to subscribers of events and
     * will be available via {@link Adapter#getPeripherals()}. The filter can be used to scan only
     * for specific devices.
     *
     * <p>While some implementations might ignore (parts of) the filter and make additional devices
     * available, other implementations might require at least one filter for security reasons.
     * Cross-platform code should provide a filter, but must be able to handle devices, which do not
     * fit into the filter.
     *
     * @param targetUuids The UUIDs to be filtered into the result
     */
    public CompletableFuture<Void> startScan(List<String> targetUuids) {
        int count = targetUuids.size();
        if (count == 0) {
            return startScan();
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        Arena tempArena = Arena.ofShared();
        MemorySegment pointersArray = tempArena.allocate(ValueLayout.ADDRESS, count);

        for (int i = 0; i < count; i++) {
            pointersArray.setAtIndex(
                    ValueLayout.ADDRESS, i, tempArena.allocateFrom(targetUuids.get(i)));
        }

        long id =
                CallbackRegistry.register(
                        future, null, CallbackRegistry.ExpectedReturnType.VOID, tempArena);

        BtleplugFfi.ble_adapter_start_filtered_scan(
                ctxPtr,
                adapterPtr,
                Shared.SHARED_RESULT_STUB,
                pointersArray,
                count,
                MemorySegment.ofAddress(id));
        return future;
    }

    /**
     * Returns the list of {@link Peripheral}s that have been discovered so far.
     *
     * <p>Note that this list may contain peripherals that are no longer available.
     *
     * @return The list of {@link Peripheral}s that have been discovered so far
     */
    public CompletableFuture<List<Peripheral>> getPeripherals() {
        CompletableFuture<List<Peripheral>> future = new CompletableFuture<>();
        long id =
                CallbackRegistry.register(
                        future,
                        new PeripheralAccumulator(ctxPtr, new ArrayList<>()),
                        CallbackRegistry.ExpectedReturnType.ATTACHMENT);

        BtleplugFfi.ble_adapter_poll_peripherals(
                ctxPtr,
                adapterPtr,
                Shared.SHARED_PERIPHERAL_CB_STUB,
                Shared.SHARED_RESULT_STUB,
                MemorySegment.ofAddress(id));

        return future;
    }

    /** Release the {@link Adapter}'s memory. */
    @Override
    public void close() {
        BtleplugFfi.ble_adapter_free(adapterPtr);
    }
}
