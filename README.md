# btleplug4j

Java bindings for the Rust library [`btleplug`](https://github.com/deviceplug/btleplug).

## Examples

Initialize the manager, obtain an adapter, and use a 128-bit Service UUID to filter and scan for specific BLE peripherals in the air:

```java
try (BleManager manager = new BleManager()) {
    List<Adapter> adapters = manager.getAdapters();
    if (adapters.isEmpty()) return;

    try (Adapter adapter = adapters.get(0)) {
        List<String> filterUuids = List.of("00000001-b5a3-f393-e0a9-e50e24dcca9e");
        adapter.startScan(filterUuids);

        System.out.println("Scanning for filtered BLE peripherals...");
        for (int i = 0; i < 5; i++) {
            List<Peripheral> peripherals = adapter.getPeripherals();
            for (Peripheral p : peripherals) {
                System.out.printf("Found Device -> ID: %s, Name: %s%n", p.id, p.name);
            }
            Thread.sleep(1000);
        }
    }
}
```


Establish a physical connection and progressively uncover the services and characteristics exposed by the device, as well as the descriptors at the endpoints:

```java
try (Peripheral peripheral = targetPeripheral) {
    if (peripheral.connect() && peripheral.discoverServices()) {
        System.out.println("Connected! Exploring GATT tree...");
        
        List<Service> services = peripheral.getServices();
        for (Service service : services) {
            System.out.println("Service: " + service.uuid);
            
            for (Characteristic c : service.getCharacteristics()) {
                System.out.println("  └─ Characteristic: " + c.uuid + " [Props: " + c.properties + "]");
                
                for (Descriptor d : c.getDescriptors()) {
                    System.out.println("      └─ Descriptor: " + d.uuid);
                }
                c.close();
            }
            service.close();
        }
    }
}
```

Write instruction bytes to a characteristic asynchronously or synchronously, and read the characteristic's return data synchronously.

```java
byte[] rxData = peripheral.readValue(targetChar);
if (rxData != null) {
    System.out.println("Read hex bytes: " + java.util.Arrays.toString(rxData));
}

byte[] txData = new byte[]{0x01, 0x02, 0x03};
boolean writeSuccess = peripheral.writeValue(targetChar, txData, false);
System.out.println("Write command status: " + writeSuccess);
```

Cross-thread-safe callbacks to enable persistent, high-concurrency Bluetooth data stream subscriptions:

```java
boolean subSuccess = peripheral.subscribe(notifyChar, payload -> {
    if (payload != null && payload.length > 0) {
        int sensorValue = payload[0] & 0xFF;
        System.out.println("Data Notification Received: " + sensorValue);
    }
});

if (subSuccess) {
    System.out.println("Successfully subscribed. Listening to streaming data...");
    Thread.sleep(10000);
}
```

Directly manipulate descriptors (such as manually overwriting the notification switch for the CCCD control feature):

```java
byte[] descValue = peripheral.readDescriptor(targetDesc);
System.out.println("Descriptor config bytes: " + java.util.Arrays.toString(descValue));

byte[] enableNotification = new byte[]{0x01, 0x00};
boolean success = peripheral.writeDescriptor(targetDesc, enableNotification);
System.out.println("Descriptor configuration update: " + success);
```
