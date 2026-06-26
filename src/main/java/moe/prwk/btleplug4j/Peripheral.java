// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import moe.prwk.btleplug4j.ffi.BtleplugFfi;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The device that you would like to communicate with (the “server” of BLE). Contains both the
 * current state of the device (its properties, {@link Characteristic}s, etc.) and functions for
 * communication.
 */
public class Peripheral implements AutoCloseable {
    private final MemorySegment ctxPtr;
    private final MemorySegment peripheralPtr;
    private final Arena sharedArena;
    private final Set<Characteristic> subbedChars;
    public final String id;
    public final String name;

    /** Not supposed to be called externally in a direct manner. */
    Peripheral(MemorySegment ctxPtr, MemorySegment peripheralPtr, String id, String name) {
        this.ctxPtr = ctxPtr;
        this.peripheralPtr = peripheralPtr;
        this.subbedChars = new HashSet<>();
        this.id = id;
        this.name = name;
        this.sharedArena = Arena.ofShared();
    }

    public boolean isConnected() {
        return BtleplugFfi.ble_peripheral_is_connected(ctxPtr, peripheralPtr);
    }

    /**
     * Creates a connection to the device. If this method returns {@code true} there has been
     * successful connection.
     *
     * <p>Note that {@link Peripheral}s allow only one connection at a time. Operations that attempt
     * to communicate with a device will fail until it is connected.
     *
     * @return Whether there has been successful connection
     */
    public boolean connect() {
        return BtleplugFfi.ble_peripheral_connect(ctxPtr, peripheralPtr);
    }

    /**
     * Terminates a connection to the device.
     *
     * @return Whether the connection has been successfully terminated
     */
    public boolean disconnect() {
        return BtleplugFfi.ble_peripheral_disconnect(ctxPtr, peripheralPtr);
    }

    /**
     * Discovers all {@link Service}s for the device, including their {@link Characteristic}s.
     *
     * @return Whether the discovery is successful
     */
    public boolean discoverServices() {
        return BtleplugFfi.ble_peripheral_discover_services(ctxPtr, peripheralPtr);
    }

    /**
     * Get the set of {@link Service}s we’ve discovered for this device. This will be empty until
     * {@link Peripheral#discoverServices()} is called.
     *
     * @return The list of {@link Service}s discovered
     */
    public List<Service> getServices() {
        List<Service> services = new ArrayList<>();
        try {
            MethodHandle handle =
                    MethodHandles.lookup()
                            .findVirtual(
                                    Peripheral.class,
                                    "serviceCallback",
                                    MethodType.methodType(
                                            void.class,
                                            List.class,
                                            MemorySegment.class,
                                            MemorySegment.class,
                                            boolean.class,
                                            MemorySegment.class))
                            .bindTo(this)
                            .bindTo(services);

            FunctionDescriptor desc =
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_BOOLEAN,
                            ValueLayout.ADDRESS);

            try (Arena tempArena = Arena.ofConfined()) {
                MemorySegment stub = Linker.nativeLinker().upcallStub(handle, desc, tempArena);
                BtleplugFfi.ble_peripheral_get_services(
                        ctxPtr, peripheralPtr, stub, MemorySegment.NULL);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return services;
    }

    @SuppressWarnings("unused")
    private void serviceCallback(
            List<Service> list,
            MemorySegment servicePtr,
            MemorySegment uuidPtr,
            boolean isPrimary,
            MemorySegment userData) {
        String uuid =
                uuidPtr.equals(MemorySegment.NULL)
                        ? ""
                        : uuidPtr.reinterpret(Long.MAX_VALUE).getString(0);
        list.add(new Service(servicePtr, uuid, isPrimary));
    }

    /**
     * Sends a read request to the device. Returns either {@code null} if the request was not
     * accepted or the response from the device.
     *
     * @param characteristic the {@link Characteristic} to read data from
     * @return The response from the device, or {@code null} if the request is not accepted
     */
    public byte @Nullable [] readValue(@NonNull Characteristic characteristic) {
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment buffer = tempArena.allocate(512);
            MemorySegment outLenPtr = tempArena.allocate(ValueLayout.JAVA_LONG);

            boolean success =
                    BtleplugFfi.ble_peripheral_read(
                            ctxPtr, peripheralPtr, characteristic.charPtr, buffer, 512, outLenPtr);

            if (success) {
                long actualLen = outLenPtr.get(ValueLayout.JAVA_LONG, 0);
                return buffer.asSlice(0, actualLen).toArray(ValueLayout.JAVA_BYTE);
            }
            return null;
        }
    }

    /**
     * Write some data to the {@link Characteristic}. Returns {@code false} if the write couldn’t be
     * sent or (in the case of a write-with-response) if the device returns an error.
     *
     * @param characteristic the {@link Characteristic} to write data to
     * @param data the data to be written
     * @param withoutResponse whether the device should respond
     * @return Whether the write is successful
     */
    public boolean writeValue(
            @NonNull Characteristic characteristic, byte[] data, boolean withoutResponse) {
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment cData = tempArena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            return BtleplugFfi.ble_peripheral_write(
                    ctxPtr,
                    peripheralPtr,
                    characteristic.charPtr,
                    cData,
                    data.length,
                    withoutResponse);
        }
    }

    /**
     * Enables either notify or indicate (depending on support) for the specified {@link
     * Characteristic}. A same {@link Characteristic} cannot be subscribed more than once.
     *
     * @param characteristic the {@link Characteristic} to subscribe
     * @param onDataReceived how the data should be handled
     * @return Whether the subscription is successful
     */
    public boolean subscribe(
            @NonNull Characteristic characteristic, Consumer<byte[]> onDataReceived) {
        if (subbedChars.contains(characteristic)) return false;
        try {
            MethodHandle handle =
                    MethodHandles.lookup()
                            .findVirtual(
                                    Peripheral.class,
                                    "notifyCallback",
                                    MethodType.methodType(
                                            void.class,
                                            Consumer.class,
                                            MemorySegment.class,
                                            MemorySegment.class,
                                            long.class,
                                            MemorySegment.class))
                            .bindTo(this)
                            .bindTo(onDataReceived);

            FunctionDescriptor desc =
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS);

            MemorySegment stub = Linker.nativeLinker().upcallStub(handle, desc, sharedArena);
            boolean result =
                    BtleplugFfi.ble_peripheral_subscribe(
                            ctxPtr,
                            peripheralPtr,
                            characteristic.charPtr,
                            stub,
                            MemorySegment.NULL);
            if (result) subbedChars.add(characteristic);

            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Disables either notify or indicate (depending on support) for the specified {@link
     * Characteristic}.
     *
     * @param characteristic the {@link Characteristic} to unsubscribe
     * @return Whether the unsubscription is successful
     */
    public boolean unsubscribe(@NonNull Characteristic characteristic) {
        boolean result =
                BtleplugFfi.ble_peripheral_unsubscribe(
                        ctxPtr, peripheralPtr, characteristic.charPtr);
        if (result) subbedChars.remove(characteristic);
        return result;
    }

    @SuppressWarnings("unused")
    private void notifyCallback(
            Consumer<byte[]> consumer,
            MemorySegment uuidPtr,
            MemorySegment dataPtr,
            long len,
            MemorySegment userData) {
        byte[] payload = new byte[0];
        if (!dataPtr.equals(MemorySegment.NULL) && len > 0) {
            payload = dataPtr.reinterpret(len).asSlice(0, len).toArray(ValueLayout.JAVA_BYTE);
        }
        consumer.accept(payload);
    }

    /**
     * Sends a read descriptor request to the device. Returns either {@code null} if the request was
     * not accepted or the response from the device.
     *
     * @param descriptor the {@link Descriptor} to read data from
     * @return The response from the device, or {@code null} if the request is not accepted
     */
    public byte @Nullable [] readDescriptor(@NonNull Descriptor descriptor) {
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment buffer = tempArena.allocate(512);
            MemorySegment outLenPtr = tempArena.allocate(ValueLayout.JAVA_LONG);

            boolean success =
                    BtleplugFfi.ble_peripheral_read_descriptor(
                            ctxPtr, peripheralPtr, descriptor.descPtr, buffer, 512, outLenPtr);

            if (success) {
                long actualLen = outLenPtr.get(ValueLayout.JAVA_LONG, 0);
                return buffer.asSlice(0, actualLen).toArray(ValueLayout.JAVA_BYTE);
            }
            return null;
        }
    }

    /**
     * Write some data to the {@link Descriptor}. Returns an error if the write couldn’t be sent or
     * (in the case of a write-with-response) if the device returns an error.
     *
     * @param descriptor the {@link Descriptor} to write data to
     * @param data the data to be written
     * @return Whether the write is successful
     */
    public boolean writeDescriptor(@NonNull Descriptor descriptor, byte[] data) {
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment cData = tempArena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            return BtleplugFfi.ble_peripheral_write_descriptor(
                    ctxPtr, peripheralPtr, descriptor.descPtr, cData, data.length);
        }
    }

    /**
     * Release the {@link Peripheral}'s memory. Also unsubscribes any subscribed {@link
     * Characteristic}s and closes the connection if already connected.
     */
    @Override
    public void close() {
        for (Characteristic c : subbedChars) unsubscribe(c);
        if (isConnected()) disconnect();

        sharedArena.close();
        BtleplugFfi.ble_peripheral_free(peripheralPtr);
    }
}
