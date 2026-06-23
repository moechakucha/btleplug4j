package moe.prwk.btleplug4j;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.List;
import moe.prwk.btleplug4j.ffi.BtleplugFfi;

public class Characteristic implements AutoCloseable {
    final MemorySegment charPtr;
    public final String uuid;
    public final byte properties;

    Characteristic(MemorySegment charPtr, String uuid, byte properties) {
        this.charPtr = charPtr;
        this.uuid = uuid;
        this.properties = properties;
    }

    public List<Descriptor> getDescriptors() {
        List<Descriptor> descriptors = new ArrayList<>();
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    Characteristic.class, "descriptorCallback",
                    MethodType.methodType(void.class, List.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)
            ).bindTo(this).bindTo(descriptors);

            FunctionDescriptor desc = FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS
            );

            try (Arena tempArena = Arena.ofConfined()) {
                MemorySegment stub = Linker.nativeLinker().upcallStub(handle, desc, tempArena);
                BtleplugFfi.ble_characteristic_get_descriptors(charPtr, stub, MemorySegment.NULL);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return descriptors;
    }

    @SuppressWarnings("unused")
    private void descriptorCallback(List<Descriptor> list, MemorySegment descPtr, MemorySegment uuidPtr, MemorySegment userData) {
        String uuid = uuidPtr.equals(MemorySegment.NULL) ? "" : uuidPtr.reinterpret(Long.MAX_VALUE).getString(0);
        list.add(new Descriptor(descPtr, uuid));
    }

    @Override
    public void close() {
        BtleplugFfi.ble_characteristic_free(charPtr);
    }
}
