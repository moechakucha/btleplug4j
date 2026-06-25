package moe.prwk.btleplug4j;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.List;
import moe.prwk.btleplug4j.ffi.BtleplugFfi;
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
    private final Arena sharedArena;

    /** Constructs a new {@link BleManager} instance. */
    public BleManager() {
        this.sharedArena = Arena.ofShared();
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
    public List<Adapter> getAdapters() {
        List<Adapter> adapters = new ArrayList<>();
        try {
            MethodHandle handle =
                    MethodHandles.lookup()
                            .findVirtual(
                                    BleManager.class,
                                    "adapterCallback",
                                    MethodType.methodType(
                                            void.class,
                                            List.class,
                                            MemorySegment.class,
                                            MemorySegment.class,
                                            MemorySegment.class))
                            .bindTo(this)
                            .bindTo(adapters);

            FunctionDescriptor desc =
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

            try (Arena tempArena = Arena.ofConfined()) {
                MemorySegment stub = Linker.nativeLinker().upcallStub(handle, desc, tempArena);
                BtleplugFfi.ble_ctx_get_adapters(ctxPtr, stub, MemorySegment.NULL);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get adapters", e);
        }
        return adapters;
    }

    @SuppressWarnings("unused")
    private void adapterCallback(
            List<Adapter> list,
            MemorySegment adapterPtr,
            MemorySegment namePtr,
            MemorySegment userData) {
        list.add(new Adapter(this.ctxPtr, adapterPtr));
    }

    /** Release the {@link BleManager}'s memory. */
    @Override
    public void close() {
        BtleplugFfi.ble_ctx_free(ctxPtr);
        sharedArena.close();
    }
}
