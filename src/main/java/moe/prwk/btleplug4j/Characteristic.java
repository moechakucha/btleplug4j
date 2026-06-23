package moe.prwk.btleplug4j;

import moe.prwk.btleplug4j.ffi.BtleplugFfi;

import java.lang.foreign.MemorySegment;

public class Characteristic implements AutoCloseable {
    final MemorySegment charPtr;
    public final String uuid;
    public final byte properties;

    Characteristic(MemorySegment charPtr, String uuid, byte properties) {
        this.charPtr = charPtr;
        this.uuid = uuid;
        this.properties = properties;
    }

    @Override
    public void close() {
        BtleplugFfi.ble_characteristic_free(charPtr);
    }
}
