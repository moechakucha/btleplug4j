package moe.prwk.btleplug4j.test;

import moe.prwk.btleplug4j.Adapter;
import moe.prwk.btleplug4j.BleManager;
import moe.prwk.btleplug4j.Characteristic;
import moe.prwk.btleplug4j.Peripheral;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class BleTest {
    @Test
    public void testFullBleWorkflowWithMock() throws InterruptedException {
        CountDownLatch dataReceivedLatch = new CountDownLatch(3);
        AtomicInteger lastHeartRate = new AtomicInteger(0);

        try (BleManager manager = new BleManager()) {
            List<Adapter> adapters = manager.getAdapters();
            assertFalse(adapters.isEmpty(), "Should return mock adapter");

            try (Adapter adapter = adapters.getFirst()) {
                assertTrue(adapter.startScan());

                List<Peripheral> peripherals = adapter.getPeripherals();
                assertFalse(peripherals.isEmpty(), "Should return mock peripheral");

                Peripheral mockHardware = peripherals.getFirst();
                assertEquals("AA:BB:CC:DD:EE:FF", mockHardware.id);

                assertTrue(mockHardware.discoverServices());

                List<Characteristic> chars = mockHardware.getCharacteristics();
                assertEquals(2, chars.size(), "Mock should provide exactly 2 characteristics");

                for (Characteristic c : chars) {
                    if (c.uuid.startsWith("00002a29")) {
                        byte[] readData = mockHardware.readValue(c);
                        assertNotNull(readData);
                        assertEquals("Mock-Hardware", new String(readData));
                    }
                    if (c.uuid.startsWith("00002a37")) {
                        mockHardware.subscribe(c, (byte[] payload) -> {
                            if (payload.length == 2 && payload[0] == 0x00) {
                                int hr = payload[1] & 0xFF;
                                System.out.println("Received mock HR: " + hr);
                                lastHeartRate.set(hr);
                                dataReceivedLatch.countDown();
                            }
                        });
                    }
                }

                boolean success = dataReceivedLatch.await(5, TimeUnit.SECONDS);

                assertTrue(success, "Did not receive all expected async notifications from Rust layer");
                assertTrue(lastHeartRate.get() > 60, "Heart rate should be incrementing in mock");
            }
        }
    }
}
