//! JNI surface for the Conquest Code engine.
//!
//! Naming contract: every function here maps to an `external` declaration in
//! `to.eyed.conquest.code.core.CoreBridge` on the Kotlin side. Keep the two
//! files in sync — this is the only place the two worlds meet.
//!
//! Design rule: calls across this boundary are coarse-grained. The Kotlin
//! layer must never loop over per-character JNI calls; batch work on one side
//! or the other.

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::sync::OnceLock;

use engine::Engine;

static ENGINE: OnceLock<Engine> = OnceLock::new();

fn engine() -> &'static Engine {
    ENGINE.get_or_init(|| {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("conquest-core"),
        );
        log::info!("engine initialized, version {}", engine::ENGINE_VERSION);
        Engine::new()
    })
}

fn get_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_engineVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    engine();
    env.new_string(engine::ENGINE_VERSION)
        .expect("failed to allocate version string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_createBuffer(
    mut env: JNIEnv,
    _class: JClass,
    initial_text: JString,
) -> jlong {
    let text = get_string(&mut env, &initial_text);
    engine().create_buffer(&text) as jlong
}

#[no_mangle]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_closeBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jboolean {
    if engine().close_buffer(buffer_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_editBuffer(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    start: jlong,
    end: jlong,
    text: JString,
) -> jboolean {
    let text = get_string(&mut env, &text);
    match engine().edit(buffer_id as u64, start as usize, end as usize, &text) {
        Ok(()) => JNI_TRUE,
        Err(err) => {
            log::warn!("editBuffer failed: {err}");
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_to_eyed_conquest_code_core_CoreBridge_bufferText(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().text(buffer_id as u64) {
        Ok(text) => env
            .new_string(text)
            .expect("failed to allocate buffer text")
            .into_raw(),
        Err(err) => {
            log::warn!("bufferText failed: {err}");
            std::ptr::null_mut()
        }
    }
}
