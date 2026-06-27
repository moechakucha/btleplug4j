// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j.blocking;

import java.util.List;

/**
 * Blocking variant of {@link moe.prwk.btleplug4j.BleManager}.
 *
 * <p>See {@link moe.prwk.btleplug4j.BleManager} for more info.
 */
public class BleManager implements AutoCloseable {
    private final moe.prwk.btleplug4j.BleManager inner;

    /** Constructs a new {@link BleManager} instance. */
    public BleManager() {
        inner = new moe.prwk.btleplug4j.BleManager();
    }

    /**
     * Get a list of all Bluetooth adapters on the system.
     *
     * @return All Bluetooth adapters on the system
     */
    public List<Adapter> getAdapters() {
        return inner.getAdapters().join().stream().map(Adapter::new).toList();
    }

    /**
     * Get the inner asynchronous implementation.
     *
     * @return The inner asynchronous implementation
     */
    public moe.prwk.btleplug4j.BleManager intoInner() {
        return inner;
    }

    @Override
    public void close() {
        inner.close();
    }
}
