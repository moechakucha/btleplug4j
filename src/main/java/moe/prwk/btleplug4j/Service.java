package moe.prwk.btleplug4j;

import moe.prwk.btleplug4j.ffi.BtleplugFfi;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A GATT service. {@link Service}s are groups of
 * {@link Characteristic}s, which may be standard or
 * device-specific.
 */
public class Service implements AutoCloseable {
    final MemorySegment servicePtr;
    public final String uuid;
    public final boolean isPrimary;

    /**
     * Not supposed to be called externally in a direct manner.
     */
    Service(MemorySegment servicePtr, String uuid, boolean isPrimary) {
        this.servicePtr = servicePtr;
        this.uuid = uuid;
        this.isPrimary = isPrimary;
    }

    /**
     * Get the {@link Characteristic}s of this {@link Service}.
     *
     * @return the {@link Characteristic}s of this
     * {@link Service}
     */
    public List<Characteristic> getCharacteristics() {
        List<Characteristic> chars = new ArrayList<>();
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    Service.class, "charCallback",
                    MethodType.methodType(void.class, List.class, MemorySegment.class, MemorySegment.class, byte.class, MemorySegment.class)
            ).bindTo(this).bindTo(chars);

            FunctionDescriptor desc = FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS
            );

            try (Arena tempArena = Arena.ofConfined()) {
                MemorySegment stub = Linker.nativeLinker().upcallStub(handle, desc, tempArena);
                BtleplugFfi.ble_service_get_characteristics(servicePtr, stub, MemorySegment.NULL);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return chars;
    }

    @SuppressWarnings("unused")
    private void charCallback(List<Characteristic> list, MemorySegment charPtr, MemorySegment uuidPtr, byte props, MemorySegment userData) {
        String uuid = uuidPtr.equals(MemorySegment.NULL) ? "" : uuidPtr.reinterpret(Long.MAX_VALUE).getString(0);
        list.add(new Characteristic(charPtr, uuid, props));
    }

    /**
     * Release the {@link Service}'s memory.
     */
    @Override
    public void close() {
        BtleplugFfi.ble_service_free(servicePtr);
    }
}
