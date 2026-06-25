package moe.prwk.btleplug4j;

import moe.prwk.btleplug4j.ffi.BtleplugFfi;

import java.lang.foreign.MemorySegment;

public class Descriptor implements AutoCloseable {
    final MemorySegment descPtr;
    public final String uuid;

    /**
     * Not supposed to be called externally in a direct manner.
     */
    Descriptor(MemorySegment descPtr, String uuid) {
        this.descPtr = descPtr;
        this.uuid = uuid;
    }

    /**
     * Release the {@link Descriptor}'s memory.
     */
    @Override
    public void close() {
        BtleplugFfi.ble_descriptor_free(descPtr);
    }
}
