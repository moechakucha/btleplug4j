// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.List;
import moe.prwk.btleplug4j.ffi.BtleplugFfi;

/**
 * The “client” of BLE. It’s able to scan for and establish connections to peripherals. Can be
 * obtained from {@link BleManager#getAdapters()}.
 */
public class Adapter implements AutoCloseable {
    private final MemorySegment ctxPtr;
    private final MemorySegment adapterPtr;

    /** Not supposed to be called externally in a direct manner. */
    Adapter(MemorySegment ctxPtr, MemorySegment adapterPtr) {
        this.ctxPtr = ctxPtr;
        this.adapterPtr = adapterPtr;
    }

    /**
     * Start a scan for BLE devices with no filter specified.
     *
     * <p>See {@link Adapter#startScan(List)} for more details.
     *
     * @return Whether the scan is successful
     */
    public boolean startScan() {
        return BtleplugFfi.ble_adapter_start_scan(ctxPtr, adapterPtr);
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
     * @return Whether the scan is successful
     */
    public boolean startScan(List<String> targetUuids) {
        int count = targetUuids.size();
        if (count == 0) {
            return startScan();
        }

        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment pointersArray = tempArena.allocate(ValueLayout.ADDRESS, count);

            for (int i = 0; i < count; i++) {
                String uuid = targetUuids.get(i);

                MemorySegment cString = tempArena.allocateFrom(uuid);

                pointersArray.setAtIndex(ValueLayout.ADDRESS, i, cString);
            }

            return BtleplugFfi.ble_adapter_start_filtered_scan(
                    ctxPtr, adapterPtr, pointersArray, count);
        }
    }

    /**
     * Returns the list of {@link Peripheral}s that have been discovered so far.
     *
     * <p>Note that this list may contain peripherals that are no longer available.
     *
     * @return The list of {@link Peripheral}s that have been discovered so far
     */
    public List<Peripheral> getPeripherals() {
        List<Peripheral> peripherals = new ArrayList<>();
        try {
            MethodHandle handle =
                    MethodHandles.lookup()
                            .findVirtual(
                                    Adapter.class,
                                    "peripheralCallback",
                                    MethodType.methodType(
                                            void.class,
                                            List.class,
                                            MemorySegment.class,
                                            MemorySegment.class,
                                            MemorySegment.class,
                                            MemorySegment.class))
                            .bindTo(this)
                            .bindTo(peripherals);

            FunctionDescriptor desc =
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS);

            try (Arena tempArena = Arena.ofConfined()) {
                MemorySegment stub = Linker.nativeLinker().upcallStub(handle, desc, tempArena);
                BtleplugFfi.ble_adapter_poll_peripherals(
                        ctxPtr, adapterPtr, stub, MemorySegment.NULL);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return peripherals;
    }

    @SuppressWarnings("unused")
    private void peripheralCallback(
            List<Peripheral> list,
            MemorySegment peripheralPtr,
            MemorySegment idPtr,
            MemorySegment namePtr,
            MemorySegment userData) {
        String id =
                idPtr.equals(MemorySegment.NULL)
                        ? ""
                        : idPtr.reinterpret(Long.MAX_VALUE).getString(0);
        String name =
                namePtr.equals(MemorySegment.NULL)
                        ? "Unknown"
                        : namePtr.reinterpret(Long.MAX_VALUE).getString(0);
        list.add(new Peripheral(ctxPtr, peripheralPtr, id, name));
    }

    /** Release the {@link Adapter}'s memory. */
    @Override
    public void close() {
        BtleplugFfi.ble_adapter_free(adapterPtr);
    }
}
