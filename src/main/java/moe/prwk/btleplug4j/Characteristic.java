// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import moe.prwk.btleplug4j.ffi.BtleplugFfi;

/**
 * A Bluetooth characteristic. Characteristics are the main way you will interact with other
 * bluetooth devices. Characteristics are identified by a UUID which may be standardized (like
 * 0x2803, which identifies a characteristic for reading heart rate measurements) but more often are
 * specific to a particular device. The standard set of characteristics can be found <a
 * href="https://www.bluetooth.com/specifications/gatt/characteristics">here</a>.
 *
 * <p>A characteristic may be interacted with in various ways depending on its properties. You may
 * be able to write to it, read from it, set its notify or indicate status, or send a command to it.
 */
public class Characteristic implements AutoCloseable {
    final MemorySegment charPtr;
    public final String uuid;
    private final Set<Property> properties;

    /** Not supposed to be called externally in a direct manner. */
    Characteristic(MemorySegment charPtr, String uuid, byte rawProps) {
        this.charPtr = charPtr;
        this.uuid = uuid;
        this.properties = Property.parse(rawProps);
    }

    /**
     * Get the set of properties for this characteristic, which indicate what functionality it
     * supports. If you attempt an operation that is not supported by the characteristics (for
     * example setting notify on one without the {@link Property#NOTIFY} flag), that operation will
     * fail.
     *
     * @return The set of properties for this characteristic
     */
    public Set<Property> properties() {
        return properties;
    }

    /**
     * Check whether the characteristic has the specified property.
     *
     * @param property the property expected
     * @return Whether this characteristic has this property
     */
    public boolean hasProperty(Property property) {
        return properties.contains(property);
    }

    /**
     * Get the {@link Descriptor}s of this {@link Characteristic}.
     *
     * @return The {@link Descriptor}s of this {@link Characteristic}.
     */
    public List<Descriptor> getDescriptors() {
        List<Descriptor> descriptors = new ArrayList<>();
        try {
            MethodHandle handle =
                    MethodHandles.lookup()
                            .findVirtual(
                                    Characteristic.class,
                                    "descriptorCallback",
                                    MethodType.methodType(
                                            void.class,
                                            List.class,
                                            MemorySegment.class,
                                            MemorySegment.class,
                                            MemorySegment.class))
                            .bindTo(this)
                            .bindTo(descriptors);

            FunctionDescriptor desc =
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

            try (Arena tempArena = Arena.ofConfined()) {
                MemorySegment stub = Linker.nativeLinker().upcallStub(handle, desc, tempArena);
                BtleplugFfi.ble_characteristic_get_descriptors(charPtr, stub, MemorySegment.NULL);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return descriptors;
    }

    @SuppressWarnings("unused")
    private void descriptorCallback(
            List<Descriptor> list,
            MemorySegment descPtr,
            MemorySegment uuidPtr,
            MemorySegment userData) {
        String uuid =
                uuidPtr.equals(MemorySegment.NULL)
                        ? ""
                        : uuidPtr.reinterpret(Long.MAX_VALUE).getString(0);
        list.add(new Descriptor(descPtr, uuid));
    }

    /** Release the {@link Characteristic}'s memory. */
    @Override
    public void close() {
        BtleplugFfi.ble_characteristic_free(charPtr);
    }

    /** Properties that indicate what operations are supported by a {@link Characteristic}. */
    public enum Property {
        BROADCAST(0x01),
        READ(0x02),
        WRITE_WITHOUT_RESPONSE(0x04),
        WRITE(0x08),
        NOTIFY(0x10),
        INDICATE(0x20),
        AUTHENTICATED_SIGNED_WRITES(0x40),
        EXTENDED_PROPERTIES(0x80);

        private final int mask;

        Property(int mask) {
            this.mask = mask;
        }

        public int mask() {
            return mask;
        }

        public static Set<Property> parse(int propertiesMask) {
            Set<Property> props = EnumSet.noneOf(Property.class);
            for (Property prop : values()) {
                if ((propertiesMask & prop.mask) != 0) {
                    props.add(prop);
                }
            }
            return props;
        }
    }
}
