package moe.prwk.btleplug4j;

import moe.prwk.btleplug4j.ffi.BtleplugFfi;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class Peripheral implements AutoCloseable {
    private final MemorySegment ctxPtr;
    private final MemorySegment peripheralPtr;
    private final Arena sharedArena;
    public final String id;
    public final String name;

    Peripheral(MemorySegment ctxPtr, MemorySegment peripheralPtr, String id, String name) {
        this.ctxPtr = ctxPtr;
        this.peripheralPtr = peripheralPtr;
        this.id = id;
        this.name = name;
        this.sharedArena = Arena.ofShared();
    }

    public boolean connect() {
        return BtleplugFfi.ble_peripheral_connect(ctxPtr, peripheralPtr);
    }

    public boolean discoverServices() {
        return BtleplugFfi.ble_peripheral_discover_services(ctxPtr, peripheralPtr);
    }

    public List<Service> getServices() {
        List<Service> services = new ArrayList<>();
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    Peripheral.class, "serviceCallback",
                    MethodType.methodType(void.class, List.class, MemorySegment.class, MemorySegment.class, boolean.class, MemorySegment.class)
            ).bindTo(this).bindTo(services);

            FunctionDescriptor desc = FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS
            );

            try (Arena tempArena = Arena.ofConfined()) {
                MemorySegment stub = Linker.nativeLinker().upcallStub(handle, desc, tempArena);
                BtleplugFfi.ble_peripheral_get_services(ctxPtr, peripheralPtr, stub, MemorySegment.NULL);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return services;
    }

    @SuppressWarnings("unused")
    private void serviceCallback(List<Service> list, MemorySegment servicePtr, MemorySegment uuidPtr, boolean isPrimary, MemorySegment userData) {
        String uuid = uuidPtr.equals(MemorySegment.NULL) ? "" : uuidPtr.reinterpret(Long.MAX_VALUE).getString(0);
        list.add(new Service(servicePtr, uuid, isPrimary));
    }

    public byte[] readValue(Characteristic characteristic) {
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment buffer = tempArena.allocate(512);
            MemorySegment outLenPtr = tempArena.allocate(ValueLayout.JAVA_LONG);

            boolean success = BtleplugFfi.ble_peripheral_read(
                    ctxPtr, peripheralPtr, characteristic.charPtr, buffer, 512, outLenPtr
            );

            if (success) {
                long actualLen = outLenPtr.get(ValueLayout.JAVA_LONG, 0);
                return buffer.asSlice(0, actualLen).toArray(ValueLayout.JAVA_BYTE);
            }
            return null;
        }
    }

    public boolean writeValue(Characteristic characteristic, byte[] data, boolean withoutResponse) {
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment cData = tempArena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            return BtleplugFfi.ble_peripheral_write(
                    ctxPtr, peripheralPtr, characteristic.charPtr, cData, data.length, withoutResponse
            );
        }
    }

    public boolean subscribe(Characteristic characteristic, Consumer<byte[]> onDataReceived) {
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    Peripheral.class, "notifyCallback",
                    MethodType.methodType(void.class, Consumer.class, MemorySegment.class, MemorySegment.class, long.class, MemorySegment.class)
            ).bindTo(this).bindTo(onDataReceived);

            FunctionDescriptor desc = FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS
            );

            MemorySegment stub = Linker.nativeLinker().upcallStub(handle, desc, sharedArena);
            return BtleplugFfi.ble_peripheral_subscribe(ctxPtr, peripheralPtr, characteristic.charPtr, stub, MemorySegment.NULL);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unused")
    private void notifyCallback(Consumer<byte[]> consumer, MemorySegment uuidPtr, MemorySegment dataPtr, long len, MemorySegment userData) {
        byte[] payload = new byte[0];
        if (!dataPtr.equals(MemorySegment.NULL) && len > 0) {
            payload = dataPtr.reinterpret(len).asSlice(0, len).toArray(ValueLayout.JAVA_BYTE);
        }
        consumer.accept(payload);
    }

    public byte[] readDescriptor(Descriptor descriptor) {
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment buffer = tempArena.allocate(512);
            MemorySegment outLenPtr = tempArena.allocate(ValueLayout.JAVA_LONG);

            boolean success = BtleplugFfi.ble_peripheral_read_descriptor(
                    ctxPtr, peripheralPtr, descriptor.descPtr, buffer, 512, outLenPtr
            );

            if (success) {
                long actualLen = outLenPtr.get(ValueLayout.JAVA_LONG, 0);
                return buffer.asSlice(0, actualLen).toArray(ValueLayout.JAVA_BYTE);
            }
            return null;
        }
    }

    public boolean writeDescriptor(Descriptor descriptor, byte[] data) {
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment cData = tempArena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            return BtleplugFfi.ble_peripheral_write_descriptor(
                    ctxPtr, peripheralPtr, descriptor.descPtr, cData, data.length
            );
        }
    }

    @Override
    public void close() {
        sharedArena.close();
        BtleplugFfi.ble_peripheral_free(peripheralPtr);
    }
}
