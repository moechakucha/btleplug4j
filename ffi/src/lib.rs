use btleplug::api::{
    Central, Characteristic, Descriptor, Manager as _, Peripheral as _, ScanFilter, Service,
    WriteType,
};
use btleplug::platform::{Adapter, Manager, Peripheral};
use std::ffi::{CStr, CString, c_void};
use std::os::raw::c_char;
use std::ptr::{null, null_mut};
use std::slice;
use std::sync::Arc;
use tokio::runtime::Runtime;
use tokio::task::JoinHandle;
use uuid::Uuid;

#[derive(Clone)]
pub struct BleContext {
    rt: Arc<Runtime>,
    manager: Manager,
}

pub struct BleAdapterHandle(pub Adapter);
pub struct BlePeripheralHandle(pub Peripheral);
pub struct BleServiceHandle(pub Service);
pub struct BleCharacteristicHandle(pub Characteristic);
pub struct BleDescriptorHandle(pub Descriptor);

pub struct TaskHandle(pub JoinHandle<()>);

pub type ResultCallback = extern "C" fn(
    success: bool,
    data: *const u8,
    data_len: usize,
    err_msg: *const c_char,
    user_data: *mut c_void,
);

#[unsafe(no_mangle)]
pub extern "C" fn ble_ctx_new() -> *mut BleContext {
    let rt = Runtime::new().unwrap();
    let manager = rt.block_on(async { Manager::new().await.unwrap() });
    Box::into_raw(Box::new(BleContext {
        rt: Arc::new(rt),
        manager,
    }))
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_ctx_free(ctx: *mut BleContext) {
    if !ctx.is_null() {
        unsafe {
            let _ = Box::from_raw(ctx);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_ctx_get_adapters(
    ctx: *mut BleContext,
    callback: extern "C" fn(*mut BleAdapterHandle, *const c_char, *mut c_void),
    result_cb: ResultCallback,
    user_data: *mut c_void,
) {
    let ctx = unsafe {
        if !ctx.is_null() {
            &mut *ctx
        } else {
            return;
        }
    }
    .clone();

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match ctx.manager.adapters().await {
            Ok(adapters) => {
                for adapter in adapters {
                    let name = {
                        let name = adapter.adapter_info().await;

                        match name {
                            Ok(name) => name,
                            Err(err) => {
                                let err_msg = CString::new(err.to_string()).unwrap();
                                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
                                return;
                            }
                        }
                    };

                    let handle_ptr = Box::into_raw(Box::new(BleAdapterHandle(adapter))) as usize;
                    let name = CString::new(name).unwrap();
                    callback(handle_ptr as _, name.as_ptr(), ud_addr as _);
                }
                result_cb(true, null(), 0, null(), ud_addr as _);
            }
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_adapter_free(adapter: *mut BleAdapterHandle) {
    if !adapter.is_null() {
        unsafe {
            let _ = Box::from_raw(adapter);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_adapter_start_scan(
    ctx: *mut BleContext,
    adapter: *mut BleAdapterHandle,
    result_cb: ResultCallback,
    user_data: *mut c_void,
) {
    let (ctx, adapter) = unsafe {
        if !ctx.is_null() && !adapter.is_null() {
            (&mut *ctx.clone(), &mut *adapter.clone())
        } else {
            return;
        }
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match adapter.0.start_scan(ScanFilter::default()).await {
            Ok(_) => result_cb(true, null(), 0, null(), ud_addr as _),
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_adapter_start_filtered_scan(
    ctx: *mut BleContext,
    adapter: *mut BleAdapterHandle,
    result_cb: ResultCallback,
    uuids: *const *const c_char,
    uuid_count: usize,
    user_data: *mut c_void,
) {
    let ctx = unsafe {
        if ctx.is_null() {
            return;
        }
        &mut *ctx.clone()
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
            return;
        } else {
            &mut *adapter.clone()
        }
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match adapter.0.start_scan(filter).await {
            Ok(_) => result_cb(true, null(), 0, null(), ud_addr as _),
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_adapter_poll_peripherals(
    ctx: *mut BleContext,
    adapter: *mut BleAdapterHandle,
    callback: extern "C" fn(*mut BlePeripheralHandle, *const c_char, *const c_char, *mut c_void),
    result_cb: ResultCallback,
    user_data: *mut c_void,
) {
    let (ctx, adapter) = unsafe {
        if !ctx.is_null() && !adapter.is_null() {
            (&mut *ctx.clone(), &mut *adapter.clone())
        } else {
            return;
        }
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match adapter.0.peripherals().await {
            Ok(peripherals) => {
                for peripheral in peripherals {
                    let props = peripheral.properties().await.unwrap_or_default();
                    if let Some(p) = props {
                        let id = CString::new(peripheral.id().to_string()).unwrap();
                        let name = CString::new(p.local_name.unwrap_or_default()).unwrap();

                        let handle_ptr = Box::into_raw(Box::new(BlePeripheralHandle(peripheral)));
                        callback(handle_ptr, id.as_ptr(), name.as_ptr(), ud_addr as _);
                        result_cb(true, null(), 0, null(), ud_addr as _);
                    }
                }
            }
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_free(peripheral: *mut BlePeripheralHandle) {
    if !peripheral.is_null() {
        unsafe {
            let _ = Box::from_raw(peripheral);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_is_connected(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    result_cb: ResultCallback,
    user_data: *mut c_void,
) {
    let (ctx, peripheral) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() {
            (&mut *ctx.clone(), &mut *peripheral.clone())
        } else {
            return;
        }
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match peripheral.0.is_connected().await {
            Ok(success) => {
                let success_ptr = &(success as u8) as *const u8;
                result_cb(true, success_ptr, 1, null(), ud_addr as _);
            }
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_connect(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    result_cb: ResultCallback,
    user_data: *mut c_void,
) {
    let (ctx, peripheral) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() {
            (&mut *ctx.clone(), &mut *peripheral.clone())
        } else {
            return;
        }
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match peripheral.0.connect().await {
            Ok(_) => result_cb(true, null(), 0, null(), ud_addr as _),
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_disconnect(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    result_cb: ResultCallback,
    user_data: *mut c_void,
) {
    let (ctx, peripheral) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() {
            (&mut *ctx.clone(), &mut *peripheral.clone())
        } else {
            return;
        }
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match peripheral.0.disconnect().await {
            Ok(_) => result_cb(true, null(), 0, null(), ud_addr as _),
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_discover_services(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    result_cb: ResultCallback,
    user_data: *mut c_void,
) {
    let (ctx, peripheral) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() {
            (&mut *ctx.clone(), &mut *peripheral.clone())
        } else {
            return;
        }
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match peripheral.0.discover_services().await {
            Ok(_) => result_cb(true, null(), 0, null(), ud_addr as _),
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
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
    if !service_handle.is_null() {
        unsafe {
            let _ = Box::from_raw(service_handle);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_service_get_characteristics(
    service: *mut BleServiceHandle,
    callback: extern "C" fn(
        *mut BleCharacteristicHandle,
        *const c_char,
        *const c_char,
        u8,
        *mut c_void,
    ),
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
        let service_uuid_str = CString::new(service.0.uuid.to_string()).unwrap();
        let uuid_str = CString::new(c.uuid.to_string()).unwrap();
        let props_bits = c.properties.bits();

        let handle_ptr = Box::into_raw(Box::new(BleCharacteristicHandle(c.clone())));
        callback(
            handle_ptr,
            service_uuid_str.as_ptr(),
            uuid_str.as_ptr(),
            props_bits,
            user_data,
        );
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_characteristic_free(char_handle: *mut BleCharacteristicHandle) {
    if !char_handle.is_null() {
        unsafe {
            let _ = Box::from_raw(char_handle);
        }
    }
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
    if !desc_handle.is_null() {
        unsafe {
            let _ = Box::from_raw(desc_handle);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_read(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    char_handle: *mut BleCharacteristicHandle,
    result_cb: ResultCallback,
    user_data: *mut c_void,
) {
    let (ctx, peripheral, char_handle) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() && !char_handle.is_null() {
            (
                &mut *ctx.clone(),
                &mut *peripheral.clone(),
                &mut *char_handle.clone(),
            )
        } else {
            return;
        }
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match peripheral.0.read(&char_handle.0).await {
            Ok(data) => {
                let len = data.len();
                result_cb(true, data.as_ptr(), len, null(), ud_addr as _);
            }
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_write(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    char_handle: *mut BleCharacteristicHandle,
    result_cb: ResultCallback,
    data: *const u8,
    data_len: usize,
    without_response: bool,
    user_data: *mut c_void,
) {
    let (ctx, peripheral, char_handle) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() && !char_handle.is_null() {
            (
                &mut *ctx.clone(),
                &mut *peripheral.clone(),
                &mut *char_handle.clone(),
            )
        } else {
            return;
        }
    };

    let slice = unsafe { slice::from_raw_parts(data, data_len) };
    let write_type = if without_response {
        WriteType::WithoutResponse
    } else {
        WriteType::WithResponse
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match peripheral.0.write(&char_handle.0, slice, write_type).await {
            Ok(_) => result_cb(true, null(), 0, null(), ud_addr as _),
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_notifications(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    callback: extern "C" fn(*const c_char, *const c_char, *const u8, usize, *mut c_void),
    result_cb: ResultCallback,
    user_data: *mut c_void,
) -> *mut TaskHandle {
    let (ctx, peripheral) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() {
            (&mut *ctx.clone(), &mut *peripheral.clone())
        } else {
            return null_mut();
        }
    };

    let ud_addr = user_data as usize;
    let handle = ctx.rt.spawn(async move {
        match peripheral.0.notifications().await {
            Ok(mut notifications) => {
                result_cb(true, null(), 0, null(), ud_addr as _);

                use futures::stream::StreamExt;

                while let Some(notification) = notifications.next().await {
                    let service_uuid_str = notification.service_uuid.to_string();
                    let c_service_uuid = CString::new(service_uuid_str).unwrap();

                    let uuid_str = notification.uuid.to_string();
                    let c_uuid = CString::new(uuid_str).unwrap_or_default();

                    callback(
                        c_service_uuid.as_ptr(),
                        c_uuid.as_ptr(),
                        notification.value.as_ptr(),
                        notification.value.len(),
                        ud_addr as _,
                    );
                }
            }
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap_or_default();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });

    Box::into_raw(Box::new(TaskHandle(handle)))
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_abort_notifications(handle: *mut TaskHandle) {
    unsafe {
        if !handle.is_null() {
            let handle = Box::from_raw(handle);
            handle.0.abort();
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_subscribe(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    char_handle: *mut BleCharacteristicHandle,
    result_cb: ResultCallback,
    user_data: *mut c_void,
) {
    let (ctx, peripheral, char_handle) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() && !char_handle.is_null() {
            (
                &mut *ctx.clone(),
                &mut *peripheral.clone(),
                &mut *char_handle.clone(),
            )
        } else {
            return;
        }
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match peripheral.0.subscribe(&char_handle.0).await {
            Ok(_) => result_cb(true, null(), 0, null(), ud_addr as _),
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_unsubscribe(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    char_handle: *mut BleCharacteristicHandle,
    result_cb: ResultCallback,
    user_data: *mut c_void,
) {
    let (ctx, peripheral, char_handle) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() && !char_handle.is_null() {
            (
                &mut *ctx.clone(),
                &mut *peripheral.clone(),
                &mut *char_handle.clone(),
            )
        } else {
            return;
        }
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match peripheral.0.unsubscribe(&char_handle.0).await {
            Ok(_) => result_cb(true, null(), 0, null(), ud_addr as _),
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_read_descriptor(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    desc: *mut BleDescriptorHandle,
    result_cb: ResultCallback,
    user_data: *mut c_void,
) {
    let (ctx, peripheral, desc) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() && !desc.is_null() {
            (
                &mut *ctx.clone(),
                &mut *peripheral.clone(),
                &mut *desc.clone(),
            )
        } else {
            return;
        }
    };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match peripheral.0.read_descriptor(&desc.0).await {
            Ok(data) => {
                let len = data.len();
                result_cb(true, data.as_ptr(), len, null(), ud_addr as _);
            }
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn ble_peripheral_write_descriptor(
    ctx: *mut BleContext,
    peripheral: *mut BlePeripheralHandle,
    desc: *mut BleDescriptorHandle,
    result_cb: ResultCallback,
    data: *const u8,
    data_len: usize,
    user_data: *mut c_void,
) {
    let (ctx, peripheral, desc) = unsafe {
        if !ctx.is_null() && !peripheral.is_null() && !desc.is_null() {
            (
                &mut *ctx.clone(),
                &mut *peripheral.clone(),
                &mut *desc.clone(),
            )
        } else {
            return;
        }
    };
    let slice = unsafe { slice::from_raw_parts(data, data_len) };

    let ud_addr = user_data as usize;
    ctx.rt.spawn(async move {
        match peripheral.0.write_descriptor(&desc.0, slice).await {
            Ok(_) => result_cb(true, null(), 0, null(), ud_addr as _),
            Err(err) => {
                let err_msg = CString::new(err.to_string()).unwrap();
                result_cb(false, null(), 0, err_msg.as_ptr(), ud_addr as _);
            }
        }
    });
}
