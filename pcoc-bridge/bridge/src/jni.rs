//! JNI entry points. The method names match `PcocNative`'s static methods.
//! The `jni` crate is built without the `invocation` feature so this library
//! does not link a JVM. Android and the host test JVM pass `JNIEnv` in.

use crate::produce::{spawn_live, LiveConfig};
use crate::verify::{outcome_json, verify_object, yielded_key};
use crate::{redact, BARI_URL, INDEX_URL};
use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jbyteArray, jint, jstring};
use jni::{JNIEnv, JavaVM};
use serde_json::json;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

struct Slot {
    stop: Arc<AtomicBool>,
    running: Arc<AtomicBool>,
}

static SLOT: Mutex<Option<Slot>> = Mutex::new(None);

struct Callback {
    vm: JavaVM,
    listener: jni::objects::GlobalRef,
}

impl Callback {
    fn emit(&self, event: serde_json::Value) {
        let text = event.to_string();
        let mut env = match self.vm.attach_current_thread() {
            Ok(env) => env,
            Err(_) => return,
        };
        let jtext = match env.new_string(text) {
            Ok(text) => text,
            Err(_) => return,
        };
        let called = env.call_method(
            &self.listener,
            "onEvent",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&jtext)],
        );
        if called.is_err() || env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
    }
}

fn java_string(env: &mut JNIEnv, value: &JString) -> Result<String, ()> {
    let text = env.get_string(value).map_err(|_| ())?;
    text.to_str().map(|text| text.to_owned()).map_err(|_| ())
}

fn return_string(env: &mut JNIEnv, text: String) -> jstring {
    env.new_string(text)
        .map(|text| text.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_net_primal_android_pcoc_bridge_PcocNative_indexUrl<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    return_string(&mut env, INDEX_URL.to_owned())
}

#[no_mangle]
pub extern "system" fn Java_net_primal_android_pcoc_bridge_PcocNative_bariUrl<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    return_string(&mut env, BARI_URL.to_owned())
}

#[no_mangle]
pub extern "system" fn Java_net_primal_android_pcoc_bridge_PcocNative_verify<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    bytes: JByteArray<'local>,
    index_url: JString<'local>,
    threads: jint,
) -> jstring {
    let owned = match env.convert_byte_array(&bytes) {
        Ok(bytes) => bytes,
        Err(_) => {
            return return_string(&mut env, engine_error("the object bytes were not readable"))
        }
    };
    let url = match java_string(&mut env, &index_url) {
        Ok(url) => url,
        Err(()) => return return_string(&mut env, engine_error("the index url was not readable")),
    };
    let threads = usize::try_from(threads).unwrap_or(1);
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        verify_object(&owned, &url, threads)
    }));
    let text = match outcome {
        Ok(outcome) => outcome_json(&outcome, if url.is_empty() { INDEX_URL } else { &url }),
        Err(_) => engine_error("the hike engine stopped unexpectedly"),
    };
    return_string(&mut env, text)
}

#[no_mangle]
pub extern "system" fn Java_net_primal_android_pcoc_bridge_PcocNative_yieldedPublicKey<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    bytes: JByteArray<'local>,
) -> jbyteArray {
    let owned = match env.convert_byte_array(&bytes) {
        Ok(bytes) => bytes,
        Err(_) => return std::ptr::null_mut(),
    };
    let key = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| yielded_key(&owned)));
    match key {
        Ok(Some(key)) => env
            .byte_array_from_slice(&key)
            .map(|array| array.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_net_primal_android_pcoc_bridge_PcocNative_startProduce<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    public_key_hex: JString<'local>,
    output_path: JString<'local>,
    gossip_peer: JString<'local>,
    listener: JObject<'local>,
) {
    let pubkey = java_string(&mut env, &public_key_hex).unwrap_or_default();
    let output = java_string(&mut env, &output_path).unwrap_or_default();
    let peer = java_string(&mut env, &gossip_peer).unwrap_or_default();
    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(_) => return,
    };
    let listener = match env.new_global_ref(listener) {
        Ok(listener) => listener,
        Err(_) => return,
    };
    let callback = Arc::new(Callback { vm, listener });
    let fail = |message: &str| {
        callback.emit(json!({
            "kind": "failed",
            "message": redact(message),
        }));
    };
    if pubkey.is_empty() || output.is_empty() {
        fail("the produce settings were refused by the reference parser");
        return;
    }
    let stop = Arc::new(AtomicBool::new(false));
    let running = Arc::new(AtomicBool::new(true));
    {
        let mut slot = SLOT.lock().unwrap_or_else(|err| err.into_inner());
        if slot
            .as_ref()
            .is_some_and(|slot| slot.running.load(Ordering::SeqCst))
        {
            fail("a hike is already running");
            return;
        }
        *slot = Some(Slot {
            stop: Arc::clone(&stop),
            running: Arc::clone(&running),
        });
    }
    let config = LiveConfig {
        public_key_hex: pubkey,
        output,
        gossip_peer: Some(peer),
    };
    let emit_running = Arc::clone(&running);
    spawn_live(
        config,
        stop,
        Arc::new(move |event| {
            let kind = event.get("kind").and_then(|value| value.as_str());
            let finished = matches!(kind, Some("written" | "stopped" | "failed"));
            callback.emit(event);
            if finished {
                emit_running.store(false, Ordering::SeqCst);
            }
        }),
    );
}

#[no_mangle]
pub extern "system" fn Java_net_primal_android_pcoc_bridge_PcocNative_stopProduce<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    if let Some(slot) = SLOT.lock().unwrap_or_else(|err| err.into_inner()).as_ref() {
        slot.stop.store(true, Ordering::SeqCst);
    }
}

fn engine_error(message: &str) -> String {
    json!({ "outcome": "engine", "message": message }).to_string()
}
