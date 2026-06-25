package moe.prwk.btleplug4j.test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import moe.prwk.btleplug4j.*;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unused")
public class BleTest {
    private static final String TARGET_DEVICE_NAME = "test-device";
    private static final String CONTROL_SERVICE_UUID = "00000001-b5a3-f393-e0a9-e50e24dcca9e";

    @Test
    public void testAdapterAndPeripheralDiscovery() throws InterruptedException {
        CountDownLatch deviceFoundLatch = new CountDownLatch(1);
        AtomicBoolean hasTargetDevice = new AtomicBoolean(false);

        try (BleManager manager = new BleManager()) {
            List<Adapter> adapters = manager.getAdapters();
            assertFalse(
                    adapters.isEmpty(),
                    "vHCI adapter was not initialized correctly by the workflow script.");

            try (Adapter adapter = adapters.get(0)) {
                System.out.println("Successfully bound to Linux vHCI Adapter.");

                System.out.println("Starting filtered scan for UUID: " + CONTROL_SERVICE_UUID);
                List<String> filterUuids = List.of(CONTROL_SERVICE_UUID);
                assertTrue(
                        adapter.startScan(filterUuids),
                        "Failed to issue filtered scan command to vHCI kernel stack.");

                System.out.println("Polling vHCI ring buffer for peripherals...");

                for (int i = 0; i < 12; i++) {
                    List<Peripheral> peripherals = adapter.getPeripherals();
                    for (Peripheral p : peripherals) {
                        if (p.name != null && p.name.contains(TARGET_DEVICE_NAME)) {
                            System.out.println(
                                    "Match found! Discovered vHCI Virtual Peripheral: "
                                            + p.id
                                            + " ["
                                            + p.name
                                            + "]");
                            hasTargetDevice.set(true);
                            deviceFoundLatch.countDown();
                            break;
                        }
                    }
                    if (hasTargetDevice.get()) {
                        break;
                    }
                    Thread.sleep(1000);
                }

                boolean success = deviceFoundLatch.await(1, TimeUnit.SECONDS);
                assertTrue(
                        success, "Failed to discover the vHCI loopback peripheral within timeout.");
                assertTrue(
                        hasTargetDevice.get(),
                        "The discovered peripheral name did not match vHCI broadcast"
                                + " configurations.");

                System.out.println("vHCI Base FFI lifecycle test passed successfully.");
            }
        }
    }
}
