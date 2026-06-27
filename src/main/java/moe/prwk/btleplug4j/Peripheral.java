// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import moe.prwk.btleplug4j.ffi.BtleplugFfi;
import moe.prwk.btleplug4j.util.CallbackRegistry;
import moe.prwk.btleplug4j.util.StreamRegistry;
import org.jspecify.annotations.NonNull;

/**
 * The device that you would like to communicate with (the “server” of BLE). Contains both the
 * current state of the device (its properties, {@link Characteristic}s, etc.) and functions for
 * communication.
 */
public class Peripheral implements AutoCloseable {
    private final MemorySegment ctxPtr;
    private final MemorySegment peripheralPtr;
    private final Set<Characteristic> subbedChars;
    public final String id;
    public final String name;

    public static class NotificationSubscription implements AutoCloseable {
        private final MemorySegment taskHandle;
        private final long streamId;

        NotificationSubscription(MemorySegment taskHandle, long streamId) {
            this.taskHandle = taskHandle;
            this.streamId = streamId;
        }

        @Override
        public void close() {
            BtleplugFfi.ble_peripheral_abort_notifications(taskHandle);
            StreamRegistry.remove(streamId);
        }
    }

    /** Not supposed to be called externally in a direct manner. */
    Peripheral(MemorySegment ctxPtr, MemorySegment peripheralPtr, String id, String name) {
        this.ctxPtr = ctxPtr;
        this.peripheralPtr = peripheralPtr;
        this.subbedChars = new HashSet<>();
        this.id = id;
        this.name = name;
    }

    /**
     * Returns {@code true} iff we are currently connected to the device.
     *
     * @return whether we are currently connected to the device
     */
    public CompletableFuture<Boolean> isConnected() {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        long id = CallbackRegistry.register(future, CallbackRegistry.ExpectedReturnType.BYTES);
        BtleplugFfi.ble_peripheral_is_connected(
                ctxPtr, peripheralPtr, Shared.SHARED_RESULT_STUB, MemorySegment.ofAddress(id));
        return future.thenApply(b -> b.length > 0 && b[0] != 0);
    }

    /**
     * Creates a connection to the device.
     *
     * <p>Note that {@link Peripheral}s allow only one connection at a time. Operations that attempt
     * to communicate with a device will fail until it is connected.
     */
    public CompletableFuture<Void> connect() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long id = CallbackRegistry.register(future, CallbackRegistry.ExpectedReturnType.VOID);
        BtleplugFfi.ble_peripheral_connect(
                ctxPtr, peripheralPtr, Shared.SHARED_RESULT_STUB, MemorySegment.ofAddress(id));
        return future;
    }

    /** Terminates a connection to the device. */
    public CompletableFuture<Void> disconnect() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long id = CallbackRegistry.register(future, CallbackRegistry.ExpectedReturnType.VOID);
        BtleplugFfi.ble_peripheral_disconnect(
                ctxPtr, peripheralPtr, Shared.SHARED_RESULT_STUB, MemorySegment.ofAddress(id));
        return future;
    }

    /** Discovers all {@link Service}s for the device, including their {@link Characteristic}s. */
    public CompletableFuture<Void> discoverServices() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long id = CallbackRegistry.register(future, CallbackRegistry.ExpectedReturnType.VOID);
        BtleplugFfi.ble_peripheral_discover_services(
                ctxPtr, peripheralPtr, Shared.SHARED_RESULT_STUB, MemorySegment.ofAddress(id));
        return future;
    }

    /**
     * Get the set of {@link Service}s we’ve discovered for this device. This will be empty until
     * {@link Peripheral#discoverServices()} is called.
     *
     * @return The list of {@link Service}s discovered
     */
    public List<Service> getServices() {
        List<Service> services = new ArrayList<>();
        try (Arena tempArena = Arena.ofConfined()) {
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
            MemorySegment stub = Linker.nativeLinker().upcallStub(handle, desc, tempArena);

            BtleplugFfi.ble_peripheral_get_services(
                    ctxPtr, peripheralPtr, stub, MemorySegment.NULL);
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
    public CompletableFuture<byte[]> readValue(@NonNull Characteristic characteristic) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        long id = CallbackRegistry.register(future, CallbackRegistry.ExpectedReturnType.BYTES);
        BtleplugFfi.ble_peripheral_read(
                ctxPtr,
                peripheralPtr,
                characteristic.charPtr,
                Shared.SHARED_RESULT_STUB,
                MemorySegment.ofAddress(id));
        return future;
    }

    /**
     * Write some data to the {@link Characteristic}. Errors out if the write couldn’t be sent or
     * (in the case of a write-with-response) if the device returns an error.
     *
     * @param characteristic the {@link Characteristic} to write data to
     * @param data the data to be written
     * @param withoutResponse whether the device should respond
     */
    public CompletableFuture<Void> writeValue(
            @NonNull Characteristic characteristic, byte[] data, boolean withoutResponse) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Arena tempArena = Arena.ofShared();
        MemorySegment cData = tempArena.allocateFrom(ValueLayout.JAVA_BYTE, data);

        long id =
                CallbackRegistry.register(
                        future, null, CallbackRegistry.ExpectedReturnType.VOID, tempArena);
        BtleplugFfi.ble_peripheral_write(
                ctxPtr,
                peripheralPtr,
                characteristic.charPtr,
                Shared.SHARED_RESULT_STUB,
                cData,
                data.length,
                withoutResponse,
                MemorySegment.ofAddress(id));
        return future;
    }

    /**
     * Enables either notify or indicate (depending on support) for the specified {@link
     * Characteristic}. A same {@link Characteristic} cannot be subscribed more than once.
     *
     * @param characteristic the {@link Characteristic} to subscribe
     */
    public CompletableFuture<Void> subscribe(@NonNull Characteristic characteristic) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long id = CallbackRegistry.register(future, CallbackRegistry.ExpectedReturnType.VOID);
        BtleplugFfi.ble_peripheral_subscribe(
                ctxPtr,
                peripheralPtr,
                characteristic.charPtr,
                Shared.SHARED_RESULT_STUB,
                MemorySegment.ofAddress(id));
        return future.thenRun(() -> subbedChars.add(characteristic));
    }

    /**
     * Disables either notify or indicate (depending on support) for the specified {@link
     * Characteristic}.
     *
     * @param characteristic the {@link Characteristic} to unsubscribe
     */
    public CompletableFuture<Void> unsubscribe(@NonNull Characteristic characteristic) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long id = CallbackRegistry.register(future, CallbackRegistry.ExpectedReturnType.VOID);
        BtleplugFfi.ble_peripheral_unsubscribe(
                ctxPtr,
                peripheralPtr,
                characteristic.charPtr,
                Shared.SHARED_RESULT_STUB,
                MemorySegment.ofAddress(id));
        return future.thenRun(() -> subbedChars.remove(characteristic));
    }

    /**
     * Returns a stream of notifications for characteristic value updates. The stream will receive a
     * notification when a value notification or indication is received from the device. The stream
     * will remain valid across connections and can be queried before any connection is made.
     *
     * @return A stream of {@link ValueNotification}s
     */
    public Flow.Publisher<ValueNotification> notifications() {
        return subscriber -> {
            CompletableFuture<Void> handshakeFuture = new CompletableFuture<>();

            ValueNotification.Subscription sub =
                    new ValueNotification.Subscription(
                            subscriber, StreamRegistry.STREAM_ID_GENERATOR.get());
            long id = StreamRegistry.register(sub, handshakeFuture);

            MemorySegment taskHandle =
                    BtleplugFfi.ble_peripheral_notifications(
                            ctxPtr,
                            peripheralPtr,
                            Shared.SHARED_NOTIFY_STUB,
                            Shared.SHARED_STREAM_RESULT_STUB,
                            MemorySegment.ofAddress(id));

            sub.setTaskHandle(taskHandle);
            subscriber.onSubscribe(sub);

            handshakeFuture.exceptionally(
                    ex -> {
                        sub.onError(ex);
                        return null;
                    });
        };
    }

    /**
     * Sends a read descriptor request to the device. Returns either {@code null} if the request was
     * not accepted or the response from the device.
     *
     * @param descriptor the {@link Descriptor} to read data from
     * @return The response from the device, or {@code null} if the request is not accepted
     */
    public CompletableFuture<byte[]> readDescriptor(@NonNull Descriptor descriptor) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        long id = CallbackRegistry.register(future, CallbackRegistry.ExpectedReturnType.BYTES);
        BtleplugFfi.ble_peripheral_read_descriptor(
                ctxPtr,
                peripheralPtr,
                descriptor.descPtr,
                Shared.SHARED_RESULT_STUB,
                MemorySegment.ofAddress(id));
        return future;
    }

    /**
     * Write some data to the {@link Descriptor}. Errors out if the write couldn’t be sent or (in
     * the case of a write-with-response) if the device returns an error.
     *
     * @param descriptor the {@link Descriptor} to write data to
     * @param data the data to be written
     */
    public CompletableFuture<Void> writeDescriptor(@NonNull Descriptor descriptor, byte[] data) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Arena tempArena = Arena.ofShared();
        MemorySegment cData = tempArena.allocateFrom(ValueLayout.JAVA_BYTE, data);

        long id =
                CallbackRegistry.register(
                        future, null, CallbackRegistry.ExpectedReturnType.VOID, tempArena);
        BtleplugFfi.ble_peripheral_write_descriptor(
                ctxPtr,
                peripheralPtr,
                descriptor.descPtr,
                Shared.SHARED_RESULT_STUB,
                cData,
                data.length,
                MemorySegment.ofAddress(id));
        return future;
    }

    /** Release the {@link Peripheral}'s memory. Also closes the connection if already connected. */
    @Override
    public void close() {
        for (Characteristic c : new HashSet<>(subbedChars)) {
            unsubscribe(c).join();
        }
        if (isConnected().join()) {
            disconnect().join();
        }
        BtleplugFfi.ble_peripheral_free(peripheralPtr);
    }
}
