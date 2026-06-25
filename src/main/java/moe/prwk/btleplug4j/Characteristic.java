package moe.prwk.btleplug4j;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.List;
import moe.prwk.btleplug4j.ffi.BtleplugFfi;

/**
 * A Bluetooth characteristic. Characteristics are the main way
 * you will interact with other bluetooth devices.
 * Characteristics are identified by a UUID which may be
 * standardized (like 0x2803, which identifies a characteristic
 * for reading heart rate measurements) but more often are
 * specific to a particular device. The standard set of
 * characteristics can be found
 * <a href="https://www.bluetooth.com/specifications/gatt/characteristics">here</a>.
 *
 * <p>A characteristic may be interacted with in various ways
 * depending on its properties. You may be able to write to it,
 * read from it, set its notify or indicate status, or send a
 * command to it.
 */
public class Characteristic implements AutoCloseable {
    final MemorySegment charPtr;
    public final String uuid;
    public final byte properties;

    /**
     * Not supposed to be called externally in a direct manner.
     */
    Characteristic(MemorySegment charPtr, String uuid, byte properties) {
        this.charPtr = charPtr;
        this.uuid = uuid;
        this.properties = properties;
    }

    /**
     * Get the {@link Descriptor}s of this
     * {@link Characteristic}.
     *
     * @return The {@link Descriptor}s of this
     * {@link Characteristic}.
     */
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

    /**
     * Release the {@link Characteristic}'s memory.
     */
    @Override
    public void close() {
        BtleplugFfi.ble_characteristic_free(charPtr);
    }
}
