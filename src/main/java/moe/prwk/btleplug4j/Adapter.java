package moe.prwk.btleplug4j;

import moe.prwk.btleplug4j.ffi.BtleplugFfi;
import org.jetbrains.annotations.NotNull;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.List;

public class Adapter implements AutoCloseable {
    private final MemorySegment ctxPtr;
    private final MemorySegment adapterPtr;

    Adapter(MemorySegment ctxPtr, MemorySegment adapterPtr) {
        this.ctxPtr = ctxPtr;
        this.adapterPtr = adapterPtr;
    }

    public boolean startScan() {
        return BtleplugFfi.ble_adapter_start_scan(ctxPtr, adapterPtr);
    }

    public boolean startScan(@NotNull List<String> targetUuids) {
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

            return BtleplugFfi.ble_adapter_start_filtered_scan(ctxPtr, adapterPtr, pointersArray, count);
        }
    }

    public @NotNull List<Peripheral> getPeripherals() {
        List<Peripheral> peripherals = new ArrayList<>();
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    Adapter.class, "peripheralCallback",
                    MethodType.methodType(void.class, List.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)
            ).bindTo(this).bindTo(peripherals);

            FunctionDescriptor desc = FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS
            );

            try (Arena tempArena = Arena.ofConfined()) {
                MemorySegment stub = Linker.nativeLinker().upcallStub(handle, desc, tempArena);
                BtleplugFfi.ble_adapter_poll_peripherals(ctxPtr, adapterPtr, stub, MemorySegment.NULL);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return peripherals;
    }

    @SuppressWarnings("unused")
    private void peripheralCallback(List<Peripheral> list, MemorySegment peripheralPtr, MemorySegment idPtr, MemorySegment namePtr, MemorySegment userData) {
        String id = idPtr.equals(MemorySegment.NULL) ?
                "" :
                idPtr.reinterpret(Long.MAX_VALUE).getString(0);
        String name = namePtr.equals(MemorySegment.NULL) ?
                "Unknown" :
                namePtr.reinterpret(Long.MAX_VALUE).getString(0);
        list.add(new Peripheral(ctxPtr, peripheralPtr, id, name));
    }

    @Override
    public void close() {
        BtleplugFfi.ble_adapter_free(adapterPtr);
    }
}
