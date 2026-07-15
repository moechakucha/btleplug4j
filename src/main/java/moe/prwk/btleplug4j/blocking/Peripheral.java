// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j.blocking;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import moe.prwk.btleplug4j.Characteristic;
import moe.prwk.btleplug4j.Descriptor;
import moe.prwk.btleplug4j.Service;
import moe.prwk.btleplug4j.ValueNotification;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Blocking variant of {@link moe.prwk.btleplug4j.Peripheral}.
 *
 * <p>See {@link moe.prwk.btleplug4j.Peripheral} for more info.
 */
public class Peripheral implements AutoCloseable {
    private moe.prwk.btleplug4j.Peripheral inner;

    Peripheral(moe.prwk.btleplug4j.Peripheral inner) {
        this.inner = inner;
    }

    public String uuid() {
        return inner.uuid();
    }

    public String name() {
        return inner.name();
    }

    /**
     * Returns {@code true} iff we are currently connected to the device.
     *
     * @return whether we are currently connected to the device
     */
    public boolean isConnected() {
        return inner.isConnected().join();
    }

    /**
     * Creates a connection to the device.
     *
     * <p>Note that {@link Peripheral}s allow only one connection at a time. Operations that attempt
     * to communicate with a device will fail until it is connected.
     */
    public void connect() {
        inner.connect().join();
    }

    /** Terminates a connection to the device. */
    public void disconnect() {
        inner.disconnect().join();
    }

    /** Discovers all {@link Service}s for the device, including their {@link Characteristic}s. */
    public void discoverServices() {
        inner.discoverServices().join();
    }

    /**
     * Get the set of {@link Service}s we’ve discovered for this device. This will be empty until
     * {@link Peripheral#discoverServices()} is called.
     *
     * @return The list of {@link Service}s discovered
     */
    public List<Service> getServices() {
        return inner.getServices();
    }

    /**
     * Sends a read request to the device. Returns either {@code null} if the request was not
     * accepted or the response from the device.
     *
     * @param characteristic the {@link Characteristic} to read data from
     * @return The response from the device, or {@code null} if the request is not accepted
     */
    public byte @Nullable [] readValue(@NonNull Characteristic characteristic) {
        return inner.readValue(characteristic).join();
    }

    /**
     * Write some data to the {@link Characteristic}. Errors out if the write couldn’t be sent or
     * (in the case of a write-with-response) if the device returns an error.
     *
     * @param characteristic the {@link Characteristic} to write data to
     * @param data the data to be written
     * @param withoutResponse whether the device should respond
     */
    public void writeValue(
            @NonNull Characteristic characteristic, byte[] data, boolean withoutResponse) {
        inner.writeValue(characteristic, data, withoutResponse).join();
    }

    /**
     * Sends a read descriptor request to the device. Returns either {@code null} if the request was
     * not accepted or the response from the device.
     *
     * @param descriptor the {@link Descriptor} to read data from
     * @return The response from the device, or {@code null} if the request is not accepted
     */
    public byte @Nullable [] readDescriptor(@NonNull Descriptor descriptor) {
        return inner.readDescriptor(descriptor).join();
    }

    /**
     * Write some data to the {@link Descriptor}. Errors out if the write couldn’t be sent or (in
     * the case of a write-with-response) if the device returns an error.
     *
     * @param descriptor the {@link Descriptor} to write data to
     * @param data the data to be written
     */
    public void writeDescriptor(@NonNull Descriptor descriptor, byte[] data) {
        inner.writeDescriptor(descriptor, data).join();
    }

    /**
     * Enables either notify or indicate (depending on support) for the specified {@link
     * Characteristic}. A same {@link Characteristic} cannot be subscribed more than once.
     *
     * @param characteristic the {@link Characteristic} to subscribe
     */
    public void subscribe(@NonNull Characteristic characteristic) {
        inner.subscribe(characteristic).join();
    }

    /**
     * Disables either notify or indicate (depending on support) for the specified {@link
     * Characteristic}.
     *
     * @param characteristic the {@link Characteristic} to unsubscribe
     */
    public void unsubscribe(@NonNull Characteristic characteristic) {
        inner.unsubscribe(characteristic).join();
    }

    /**
     * Returns a queue of notifications for characteristic value updates. The queue will receive a
     * notification when a value notification or indication is received from the device. The queue
     * will remain valid across connections and can be queried before any connection is made.
     *
     * @param queueCapacity the capacity of the queue
     * @return A queue of {@link ValueNotification}s
     */
    public BlockingQueue<ValueNotification> notifications(int queueCapacity) {
        NotificationQueue queue = new NotificationQueue(queueCapacity);
        inner.notifications().subscribe(queue);
        return queue;
    }

    /**
     * Get the inner asynchronous implementation.
     *
     * @return The inner asynchronous implementation
     */
    public moe.prwk.btleplug4j.Peripheral intoInner() {
        return inner;
    }

    /** Release the {@link Peripheral}'s memory. Also closes the connection if already connected. */
    @Override
    public void close() {
        inner.close();
    }
}
