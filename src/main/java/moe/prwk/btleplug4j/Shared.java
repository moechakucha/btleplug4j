// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import moe.prwk.btleplug4j.util.CallbackRegistry;
import moe.prwk.btleplug4j.util.StreamRegistry;

final class Shared {
    public static final Arena GLOBAL_ARENA = Arena.ofAuto();

    public static final MemorySegment SHARED_RESULT_STUB;
    public static final MemorySegment SHARED_STREAM_RESULT_STUB;
    public static final MemorySegment SHARED_ADAPTER_CB_STUB;
    public static final MemorySegment SHARED_PERIPHERAL_CB_STUB;
    public static final MemorySegment SHARED_NOTIFY_STUB;

    static {
        try {
            var lookup = MethodHandles.lookup();

            SHARED_RESULT_STUB =
                    Linker.nativeLinker()
                            .upcallStub(
                                    lookup.findStatic(
                                            Shared.class,
                                            "genericResultCallback",
                                            MethodType.methodType(
                                                    void.class,
                                                    boolean.class,
                                                    MemorySegment.class,
                                                    long.class,
                                                    MemorySegment.class,
                                                    MemorySegment.class)),
                                    FunctionDescriptor.ofVoid(
                                            ValueLayout.JAVA_BOOLEAN,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.JAVA_LONG,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS),
                                    GLOBAL_ARENA);

            SHARED_STREAM_RESULT_STUB =
                    Linker.nativeLinker()
                            .upcallStub(
                                    lookup.findStatic(
                                            Shared.class,
                                            "streamResultCallback",
                                            MethodType.methodType(
                                                    void.class,
                                                    boolean.class,
                                                    MemorySegment.class,
                                                    long.class,
                                                    MemorySegment.class,
                                                    MemorySegment.class)),
                                    FunctionDescriptor.ofVoid(
                                            ValueLayout.JAVA_BOOLEAN,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.JAVA_LONG,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS),
                                    GLOBAL_ARENA);

            SHARED_ADAPTER_CB_STUB =
                    Linker.nativeLinker()
                            .upcallStub(
                                    lookup.findStatic(
                                            Shared.class,
                                            "adapterListCallback",
                                            MethodType.methodType(
                                                    void.class,
                                                    MemorySegment.class,
                                                    MemorySegment.class,
                                                    MemorySegment.class)),
                                    FunctionDescriptor.ofVoid(
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS),
                                    GLOBAL_ARENA);

            SHARED_PERIPHERAL_CB_STUB =
                    Linker.nativeLinker()
                            .upcallStub(
                                    lookup.findStatic(
                                            Shared.class,
                                            "peripheralListCallback",
                                            MethodType.methodType(
                                                    void.class,
                                                    MemorySegment.class,
                                                    MemorySegment.class,
                                                    MemorySegment.class,
                                                    MemorySegment.class)),
                                    FunctionDescriptor.ofVoid(
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS),
                                    GLOBAL_ARENA);

            SHARED_NOTIFY_STUB =
                    Linker.nativeLinker()
                            .upcallStub(
                                    lookup.findStatic(
                                            Shared.class,
                                            "notifyStreamCallback",
                                            MethodType.methodType(
                                                    void.class,
                                                    MemorySegment.class,
                                                    MemorySegment.class,
                                                    MemorySegment.class,
                                                    long.class,
                                                    MemorySegment.class)),
                                    FunctionDescriptor.ofVoid(
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.JAVA_LONG,
                                            ValueLayout.ADDRESS),
                                    GLOBAL_ARENA);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize FFI Upcall Stubs", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void genericResultCallback(
            boolean success,
            MemorySegment dataPtr,
            long dataLen,
            MemorySegment errPtr,
            MemorySegment userData) {
        long callbackId = userData.address();
        CallbackRegistry.RegistryEntry entry = CallbackRegistry.removeAndGet(callbackId);

        if (entry != null) {
            try {
                if (success) {
                    switch (entry.returnType) {
                        case BYTES -> {
                            byte[] data = new byte[0];
                            if (dataPtr.address() != 0 && dataLen > 0) {
                                data = dataPtr.reinterpret(dataLen).toArray(ValueLayout.JAVA_BYTE);
                            }
                            entry.future.complete(data);
                        }
                        case ATTACHMENT -> {
                            if (entry.attachment instanceof BleManager.AdapterAccumulator acc) {
                                entry.future.complete(acc.list());
                            } else if (entry.attachment
                                    instanceof Adapter.PeripheralAccumulator acc) {
                                entry.future.complete(acc.list());
                            } else {
                                entry.future.complete(entry.attachment);
                            }
                        }
                        case VOID -> entry.future.complete(null);
                    }
                } else {
                    String errMsg =
                            errPtr.address() == 0 ? "Unknown FFI Error" : errPtr.getString(0);
                    entry.future.completeExceptionally(
                            new RuntimeException("BLE Operation Failed: " + errMsg));
                }
            } finally {
                if (entry.tiedArena != null && entry.tiedArena.scope().isAlive()) {
                    entry.tiedArena.close();
                }
            }
        }
    }

    @SuppressWarnings("unused")
    public static void streamResultCallback(
            boolean success,
            MemorySegment dataPtr,
            long dataLen,
            MemorySegment errPtr,
            MemorySegment userData) {
        long callbackId = userData.address();
        StreamRegistry.StreamContext ctx = StreamRegistry.getContext(callbackId);
        if (ctx != null) {
            if (success) {
                ctx.handshakeFuture().complete(null);
            } else {
                String errMsg = errPtr.address() == 0 ? "Unknown FFI Error" : errPtr.getString(0);
                ctx.handshakeFuture()
                        .completeExceptionally(
                                new RuntimeException("Notification Subscribe Failed: " + errMsg));
                StreamRegistry.remove(callbackId);
            }
        }
    }

    @SuppressWarnings("unused")
    public static void adapterListCallback(
            MemorySegment adapterHandle, MemorySegment namePtr, MemorySegment userData) {
        long id = userData.address();
        if (CallbackRegistry.getAttachment(id)
                instanceof
                BleManager.AdapterAccumulator(MemorySegment ctxPtr, java.util.List<Adapter> list)) {
            list.add(new Adapter(ctxPtr, adapterHandle));
        }
    }

    @SuppressWarnings("unused")
    public static void peripheralListCallback(
            MemorySegment peripheralHandle,
            MemorySegment idPtr,
            MemorySegment namePtr,
            MemorySegment userData) {
        long id = userData.address();
        if (CallbackRegistry.getAttachment(id)
                instanceof
                Adapter.PeripheralAccumulator(
                        MemorySegment ctxPtr,
                        java.util.List<Peripheral> list)) {
            String devId = idPtr.address() == 0 ? "" : idPtr.getString(0);
            String devName = namePtr.address() == 0 ? "" : namePtr.getString(0);
            list.add(new Peripheral(ctxPtr, peripheralHandle, devId, devName));
        }
    }

    @SuppressWarnings("unused")
    public static void notifyStreamCallback(
            MemorySegment serviceUuidPtr,
            MemorySegment uuidPtr,
            MemorySegment dataPtr,
            long dataLen,
            MemorySegment userData) {
        long id = userData.address();
        StreamRegistry.StreamContext ctx = StreamRegistry.getContext(id);
        if (ctx != null && ctx.subscription() != null) {
            String serviceUuid = serviceUuidPtr.address() == 0 ? "" : serviceUuidPtr.getString(0);
            String uuid = uuidPtr.address() == 0 ? "" : uuidPtr.getString(0);
            byte[] data =
                    dataPtr.address() == 0
                            ? new byte[0]
                            : dataPtr.reinterpret(dataLen).toArray(ValueLayout.JAVA_BYTE);

            ctx.subscription().onData(serviceUuid, uuid, data);
        }
    }
}
