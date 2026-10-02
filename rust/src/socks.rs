use std::net::{Ipv4Addr, Ipv6Addr};
use tokio::io::{AsyncReadExt, AsyncWriteExt, copy_bidirectional};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;

const SOCKS5_VER: u8 = 0x05;
const AUTH_NO_AUTH: u8 = 0x00;
const CMD_CONNECT: u8 = 0x01;
const ATYP_IPV4: u8 = 0x01;
const ATYP_DOMAIN: u8 = 0x03;
const ATYP_IPV6: u8 = 0x04;

pub async fn run_socks5_server(
    bind_addr: String,
    bound_interface: String,
    mut shutdown_rx: oneshot::Receiver<()>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let listener = TcpListener::bind(&bind_addr).await?;
    println!("[Rust Engine] SOCKS5 server listening on {}", bind_addr);

    loop {
        tokio::select! {
            accept_res = listener.accept() => {
                let (stream, client_addr) = match accept_res {
                    Ok(res) => res,
                    Err(e) => {
                        eprintln!("[Rust Engine] Connection accept failure: {}", e);
                        continue;
                    }
                };

                let iface = bound_interface.clone();
                tokio::spawn(async move {
                    if let Err(e) = handle_client(stream, iface).await {
                        eprintln!("[Rust Engine] Error handling connection from {}: {}", client_addr, e);
                    }
                });
            }
            _ = &mut shutdown_rx => {
                println!("[Rust Engine] Graceful shutdown signal received.");
                break;
            }
        }
    }
    Ok(())
}

async fn handle_client(
    mut inbound: TcpStream,
    bound_interface: String,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut header = [0u8; 2];
    inbound.read_exact(&mut header).await?;

    if header[0] != SOCKS5_VER {
        return Err("Unsupported protocol version".into());
    }

    let num_methods = header[1] as usize;
    let mut methods = vec![0u8; num_methods];
    inbound.read_exact(&mut methods).await?;
    inbound.write_all(&[SOCKS5_VER, AUTH_NO_AUTH]).await?;

    let mut req_header = [0u8; 4];
    inbound.read_exact(&mut req_header).await?;

    if req_header[1] != CMD_CONNECT {
        send_reply(&mut inbound, 0x07).await?;
        return Err("Command unsupported".into());
    }

    let target_addr = match req_header[3] {
        ATYP_IPV4 => {
            let mut ip_bytes = [0u8; 4];
            inbound.read_exact(&mut ip_bytes).await?;
            let port = inbound.read_u16().await?;
            format!("{}:{}", Ipv4Addr::from(ip_bytes), port)
        }
        ATYP_DOMAIN => {
            let len = inbound.read_u8().await? as usize;
            let mut domain_bytes = vec![0u8; len];
            inbound.read_exact(&mut domain_bytes).await?;
            let domain = String::from_utf8(domain_bytes)?;
            let port = inbound.read_u16().await?;
            format!("{}:{}", domain, port)
        }
        ATYP_IPV6 => {
            let mut ip_bytes = [0u8; 16];
            inbound.read_exact(&mut ip_bytes).await?;
            let port = inbound.read_u16().await?;
            format!("[{}]:{}", Ipv6Addr::from(ip_bytes), port)
        }
        _ => {
            send_reply(&mut inbound, 0x08).await?;
            return Err("Address type unsupported".into());
        }
    };

    let socket = tokio::net::TcpSocket::new_v4()?;

    #[cfg(target_os = "android")]
    {
        use std::os::unix::io::AsRawFd;
        if !bound_interface.is_empty() {
            let fd = socket.as_raw_fd();
            unsafe {
                libc::setsockopt(
                    fd,
                    libc::SOL_SOCKET,
                    libc::SO_BINDTODEVICE,
                    bound_interface.as_ptr() as *const libc::c_void,
                    bound_interface.len() as libc::socklen_t,
                );
            }
        }
    }

    match socket.connect(target_addr.parse()?).await {
        Ok(mut outbound) => {
            send_reply(&mut inbound, 0x00).await?;
            let _ = copy_bidirectional(&mut inbound, &mut outbound).await;
            Ok(())
        }
        Err(e) => {
            send_reply(&mut inbound, 0x05).await?;
            Err(format!("Upstream link failed for {}: {}", target_addr, e).into())
        }
    }
}

async fn send_reply(stream: &mut TcpStream, reply_code: u8) -> Result<(), std::io::Error> {
    let response = [SOCKS5_VER, reply_code, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0];
    stream.write_all(&response).await
}
