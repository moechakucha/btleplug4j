use btleplug::api::{Central, Characteristic, Descriptor, Manager as _, Peripheral as _, ScanFilter, Service, WriteType};
use btleplug::platform::{Adapter, Manager, Peripheral};
use std::ffi::{c_void, CStr, CString};
use std::os::raw::c_char;
use std::slice;
use futures::StreamExt;
use tokio::runtime::Runtime;
use uuid::Uuid;

pub struct BleContext {
    rt: Runtime,
    manager: Manager,
}

pub struct BleAdapterHandle(pub Adapter);
pub struct BlePeripheralHandle(pub Peripheral);
pub struct BleServiceHandle(pub Service);
pub struct BleCharacteristicHandle(pub Characteristic);
pub struct BleDescriptorHandle(pub Descriptor);

#[unsafe(no_mangle)]
pub extern "C" fn ble_ctx_new() -> *mut BleContext {
    let rt = Runtime::new().unwrap();
    let manager = rt.block_on(async { Manager::new().await.unwrap() });
    Box::into_raw(Box::new(BleContext {
        rt,
        manager,
    }))
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_ctx_free(ctx: *mut BleContext) {
    if !ctx.is_null() { unsafe { let _ = Box::from_raw(ctx); } }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_ctx_get_adapters(
    ctx: *mut BleContext,
    callback: extern "C" fn(*mut BleAdapterHandle, *const c_char, *mut c_void),
    user_data: *mut c_void,
) {
    let ctx = unsafe {
        if !ctx.is_null() {
            &mut *ctx
        } else {
            return;
        }
    };
    let adapters = ctx.rt.block_on(async { ctx.manager.adapters().await.unwrap_or_default() });

    for adapter in adapters {
        let handle_ptr = Box::into_raw(Box::new(BleAdapterHandle(adapter)));
        let name = CString::new("Bluetooth Adapter").unwrap();
        callback(handle_ptr, name.as_ptr(), user_data);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_adapter_free(adapter: *mut BleAdapterHandle) {
    if !adapter.is_null() { unsafe { let _ = Box::from_raw(adapter); } }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_adapter_start_scan(ctx: *mut BleContext, adapter: *mut BleAdapterHandle) -> bool {
    let (ctx, adapter) = unsafe {
        if !ctx.is_null() && !adapter.is_null() {
            (&mut *ctx, &mut *adapter)
        } else {
            return false;
        }
    };
    ctx.rt.block_on(async {
        adapter.0.start_scan(ScanFilter::default()).await.is_ok()
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_adapter_start_filtered_scan(
    ctx: *mut BleContext,
    adapter: *mut BleAdapterHandle,
    uuids: *const *const c_char,
    uuid_count: usize,
) -> bool {
    let ctx = unsafe {
        if ctx.is_null() { return false; }
        &mut *ctx
    };

    let mut services = Vec::new();

    if !uuids.is_null() && uuid_count > 0 {
        let uuids_slice = unsafe { slice::from_raw_parts(uuids, uuid_count) };

        for &c_uuid_ptr in uuids_slice {
            if !c_uuid_ptr.is_null() {
                if let Ok(c_str) = unsafe { CStr::from_ptr(c_uuid_ptr) }.to_str() {
                    if let Ok(uuid) = Uuid::parse_str(c_str) {
                        services.push(uuid);
                    }
                }
            }
        }
    }

    let filter = ScanFilter { services };

    let adapter = unsafe {
        if adapter.is_null() {
            return false;
        } else {
            &mut *adapter
        }
    };

    ctx.rt.block_on(async {
        adapter.0.start_scan(filter).await.is_ok()
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_adapter_poll_peripherals(
    ctx: *mut BleContext,
    adapter: *mut BleAdapterHandle,
    callback: extern "C" fn(*mut BlePeripheralHandle, *const c_char, *const c_char, *mut c_void),
    user_data: *mut c_void,
) {
    let (ctx, adapter) = unsafe {
        if !ctx.is_null() && !adapter.is_null() {
            (&mut *ctx, &mut *adapter)
        } else {
            return;
        }
    };
    let peripherals = ctx.rt.block_on(async { adapter.0.peripherals().await.unwrap_or_default() });

    for peripheral in peripherals {
        ctx.rt.block_on(async {
            let props = peripheral.properties().await.unwrap_or_default();
            if let Some(p) = props {
                let id = CString::new(peripheral.id().to_string()).unwrap();
                let name = CString::new(p.local_name.unwrap_or_default()).unwrap();

                let handle_ptr = Box::into_raw(Box::new(BlePeripheralHandle(peripheral)));
                callback(handle_ptr, id.as_ptr(), name.as_ptr(), user_data);
            }
        });
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_free(peripheral: *mut BlePeripheralHandle) {
    if !peripheral.is_null() { unsafe { let _ = Box::from_raw(peripheral); } }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_connect(ctx: *mut BleContext, peripheral: *mut BlePeripheralHandle) -> bool {
    let (ctx, peripheral) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() {
            (&mut *ctx, &mut *peripheral)
        } else {
            return false;
        }
    };
    ctx.rt.block_on(async { peripheral.0.connect().await.is_ok() })
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_discover_services(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
) -> bool {
    let (ctx, peripheral) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() {
            (&mut *ctx, &mut *peripheral)
        } else {
            return false;
        }
    };
    ctx.rt.block_on(async { peripheral.0.discover_services().await.is_ok() })
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_get_services(
    _ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    callback: extern "C" fn(*mut BleServiceHandle, *const c_char, bool, *mut c_void),
    user_data: *mut c_void,
) {
    let peripheral = unsafe {
        if peripheral.is_null() {
            return;
        } else {
            &mut *peripheral
        }
    };
    let services = peripheral.0.services();

    for s in services {
        let uuid_str = CString::new(s.uuid.to_string()).unwrap();
        let handle_ptr = Box::into_raw(Box::new(BleServiceHandle(s.clone())));
        callback(handle_ptr, uuid_str.as_ptr(), s.primary, user_data);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_service_free(service_handle: *mut BleServiceHandle) {
    if !service_handle.is_null() { unsafe { let _ = Box::from_raw(service_handle); } }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_service_get_characteristics(
    service: *mut BleServiceHandle,
    callback: extern "C" fn(*mut BleCharacteristicHandle, *const c_char, u8, *mut c_void),
    user_data: *mut c_void,
) {
    let service = unsafe {
        if service.is_null() {
            return;
        } else {
            &mut *service
        }
    };

    for c in &service.0.characteristics {
        let uuid_str = CString::new(c.uuid.to_string()).unwrap();
        let props_bits = c.properties.bits();

        let handle_ptr = Box::into_raw(Box::new(BleCharacteristicHandle(c.clone())));
        callback(handle_ptr, uuid_str.as_ptr(), props_bits, user_data);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_characteristic_free(char_handle: *mut BleCharacteristicHandle) {
    if !char_handle.is_null() { unsafe { let _ = Box::from_raw(char_handle); } }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_characteristic_get_descriptors(
    char_handle: *mut BleCharacteristicHandle,
    callback: extern "C" fn(*mut BleDescriptorHandle, *const c_char, *mut c_void),
    user_data: *mut c_void,
) {
    let char_handle = unsafe {
        if char_handle.is_null() {
            return;
        } else {
            &mut *char_handle
        }
    };

    for d in &char_handle.0.descriptors {
        let uuid_str = CString::new(d.uuid.to_string()).unwrap();
        let handle_ptr = Box::into_raw(Box::new(BleDescriptorHandle(d.clone())));
        callback(handle_ptr, uuid_str.as_ptr(), user_data);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_descriptor_free(desc_handle: *mut BleDescriptorHandle) {
    if !desc_handle.is_null() { unsafe { let _ = Box::from_raw(desc_handle); } }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_read(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    char_handle: *mut BleCharacteristicHandle,
    out_buf: *mut u8,
    buf_cap: usize,
    out_len: *mut usize,
) -> bool {
    let (ctx, peripheral, char_handle)
        = unsafe { (&mut *ctx, &mut *peripheral, &mut *char_handle) };

    if let Ok(data) = ctx.rt.block_on(async { peripheral.0.read(&char_handle.0).await }) {
        let len = data.len().min(buf_cap);
        unsafe {
            std::ptr::copy_nonoverlapping(data.as_ptr(), out_buf, len);
            *out_len = len;
        }
        true
    } else {
        false
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_write(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    char_handle: *mut BleCharacteristicHandle,
    data: *const u8,
    data_len: usize,
    without_response: bool,
) -> bool {
    let (ctx, peripheral, char_handle)
        = unsafe { (&mut *ctx, &mut *peripheral, &mut *char_handle) };
    let slice = unsafe { slice::from_raw_parts(data, data_len) };
    let write_type = if without_response { WriteType::WithoutResponse } else { WriteType::WithResponse };

    ctx.rt.block_on(async {
        peripheral.0.write(&char_handle.0, slice, write_type).await.is_ok()
    })
}

pub type NotifyCallback = extern "C" fn(*const c_char, *const u8, usize, *mut c_void);

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_subscribe(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    char_handle: *mut BleCharacteristicHandle,
    callback: NotifyCallback,
    user_data: *mut c_void,
) -> bool {
    let (ctx, peripheral, char_handle)
        = unsafe { (&mut *ctx, &mut *peripheral, &mut *char_handle) };

    if ctx.rt.block_on(async { peripheral.0.subscribe(&char_handle.0).await }).is_err() {
        return false;
    }

    if let Ok(mut stream) = ctx.rt.block_on(async { peripheral.0.notifications().await }) {
        let cb_addr = callback as usize;
        let ud_addr = user_data as usize;

        ctx.rt.spawn(async move {
            while let Some(data) = stream.next().await {
                let cb: NotifyCallback = unsafe { std::mem::transmute(cb_addr) };
                let ud_ptr = ud_addr as *mut c_void;

                if let Ok(uuid_str) = CString::new(data.uuid.to_string()) {
                    let payload = data.value;
                    cb(uuid_str.as_ptr(), payload.as_ptr(), payload.len(), ud_ptr);
                }
            }
        });
        true
    } else {
        false
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_read_descriptor(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    desc_handle: *mut BleDescriptorHandle,
    out_buf: *mut u8,
    buf_cap: usize,
    out_len: *mut usize,
) -> bool {
    let (ctx, peripheral, desc_handle) = unsafe { (&mut *ctx, &mut *peripheral, &mut *desc_handle) };

    if let Ok(data) = ctx.rt.block_on(async { peripheral.0.read_descriptor(&desc_handle.0).await }) {
        let len = data.len().min(buf_cap);
        unsafe {
            std::ptr::copy_nonoverlapping(data.as_ptr(), out_buf, len);
            *out_len = len;
        }
        true
    } else {
        false
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_write_descriptor(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    desc_handle: *mut BleDescriptorHandle,
    data: *const u8,
    data_len: usize,
) -> bool {
    let (ctx, peripheral, desc_handle) = unsafe { (&mut *ctx, &mut *peripheral, &mut *desc_handle) };
    let slice = unsafe { slice::from_raw_parts(data, data_len) };

    ctx.rt.block_on(async {
        peripheral.0.write_descriptor(&desc_handle.0, slice).await.is_ok()
    })
}
