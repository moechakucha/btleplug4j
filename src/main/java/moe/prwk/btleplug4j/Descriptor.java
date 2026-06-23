package moe.prwk.btleplug4j;

import moe.prwk.btleplug4j.ffi.BtleplugFfi;

import java.lang.foreign.MemorySegment;

public class Descriptor implements AutoCloseable {
    final MemorySegment descPtr;
    public final String uuid;

    Descriptor(MemorySegment descPtr, String uuid) {
        this.descPtr = descPtr;
        this.uuid = uuid;
    }

    @Override
    public void close() {
        BtleplugFfi.ble_descriptor_free(descPtr);
    }
}
