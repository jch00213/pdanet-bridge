mod socks;

use std::sync::Mutex;
use tokio::runtime::Runtime;
use tokio::sync::oneshot;

static RUNTIME_STATE: Mutex<Option<(Runtime, oneshot::Sender<()>)>> = Mutex::new(None);

#[cxx::bridge]
mod ffi {
    extern "Rust" {
        fn start_proxy_engine(bind_ip: String, port: u16, interface_name: String) -> bool;
        fn stop_proxy_engine() -> bool;
    }
}

pub fn start_proxy_engine(bind_ip: String, port: u16, interface_name: String) -> bool {
    let mut state = RUNTIME_STATE.lock().unwrap();
    if state.is_some() {
        return false;
    }

    let rt = match Runtime::new() {
        Ok(rt) => rt,
        Err(_) => return false,
    };

    let (shutdown_tx, shutdown_rx) = oneshot::channel();
    let bind_addr = format!("{}:{}", bind_ip, port);

    rt.spawn(async move {
        let _ = socks::run_socks5_server(bind_addr, interface_name, shutdown_rx).await;
    });

    *state = Some((rt, shutdown_tx));
    true
}

pub fn stop_proxy_engine() -> bool {
    let mut state = RUNTIME_STATE.lock().unwrap();
    if let Some((rt, shutdown_tx)) = state.take() {
        let _ = shutdown_tx.send(());
        rt.shutdown_background();
        true
    } else {
        false
    }
}
