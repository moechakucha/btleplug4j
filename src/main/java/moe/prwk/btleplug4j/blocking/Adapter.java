// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j.blocking;

import java.util.List;

/**
 * Blocking variant of {@link moe.prwk.btleplug4j.Adapter}.
 *
 * <p>See {@link moe.prwk.btleplug4j.Adapter} for more info.
 */
public class Adapter implements AutoCloseable {
    private final moe.prwk.btleplug4j.Adapter inner;

    Adapter(moe.prwk.btleplug4j.Adapter inner) {
        this.inner = inner;
    }

    /**
     * Start a scan for BLE devices with no filter specified.
     *
     * <p>See {@link Adapter#startScan(List)} for more details.
     */
    public void startScan() {
        inner.startScan().join();
    }

    /**
     * Starts a scan for BLE devices.
     *
     * <p>This scan will generally continue until explicitly stopped, although this may depend on
     * your Bluetooth adapter. Discovered devices will be announced to subscribers of events and
     * will be available via {@link Adapter#getPeripherals()}. The filter can be used to scan only
     * for specific devices.
     *
     * <p>While some implementations might ignore (parts of) the filter and make additional devices
     * available, other implementations might require at least one filter for security reasons.
     * Cross-platform code should provide a filter, but must be able to handle devices, which do not
     * fit into the filter.
     *
     * @param targetUuids The UUIDs to be filtered into the result
     */
    public void startScan(List<String> targetUuids) {
        inner.startScan(targetUuids).join();
    }

    /**
     * Returns the list of {@link Peripheral}s that have been discovered so far.
     *
     * <p>Note that this list may contain peripherals that are no longer available.
     *
     * @return The list of {@link Peripheral}s that have been discovered so far
     */
    public List<Peripheral> getPeripherals() {
        return inner.getPeripherals().join().stream().map(Peripheral::new).toList();
    }

    /**
     * Get the inner asynchronous implementation.
     *
     * @return The inner asynchronous implementation
     */
    public moe.prwk.btleplug4j.Adapter intoInner() {
        return inner;
    }

    @Override
    public void close() {
        inner.close();
    }
}
