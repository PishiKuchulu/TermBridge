#!/usr/bin/env python3
"""Minimal unauthenticated SOCKS5 proxy for the TermBridge
fixture (item T-20).

The app offers exactly one proxy mode — unauthenticated
SOCKS5 (see domain/ProxyConfig.kt) — and the integration
test proves a real SSH connection through a real proxy.
This is the smallest honest proxy for that: SOCKS5, no-auth,
CONNECT only, loopback bind. It is test infrastructure,
never shipped, and it refuses nothing and logs nothing.

Usage: socks5_proxy.py [port]   (default 10809)
Started by tests/fixture/setup-sshd-fixture.sh.
"""
import socket
import struct
import sys
import threading

port = int(sys.argv[1]) if len(sys.argv) > 1 else 10809


def relay(source: socket.socket, target: socket.socket) -> None:
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            target.sendall(data)
    except OSError:
        pass
    finally:
        try:
            target.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass


def handle(client: socket.socket) -> None:
    try:
        greeting = client.recv(262)
        if len(greeting) < 3 or greeting[0] != 5:
            client.close()
            return
        # No authentication (the only mode the app offers).
        client.sendall(b"\x05\x00")
        header = client.recv(4)
        if len(header) < 4 or header[0] != 5:
            client.close()
            return
        if header[1] != 1:  # CONNECT only
            client.sendall(b"\x05\x07\x00\x01\x00\x00\x00\x00\x00\x00")
            client.close()
            return
        atyp = header[3]
        if atyp == 1:
            address = socket.inet_ntoa(client.recv(4))
        elif atyp == 3:
            length = client.recv(1)[0]
            address = client.recv(length).decode("idna")
        elif atyp == 4:
            address = socket.inet_ntop(socket.AF_INET6, client.recv(16))
        else:
            client.sendall(b"\x05\x08\x00\x01\x00\x00\x00\x00\x00\x00")
            client.close()
            return
        (dest_port,) = struct.unpack(">H", client.recv(2))
        remote = socket.create_connection((address, dest_port), timeout=10)
        bound_host, bound_port = remote.getsockname()[:2]
        reply = (
            b"\x05\x00\x00\x01"
            + socket.inet_aton(bound_host)
            + struct.pack(">H", bound_port)
        )
        client.sendall(reply)
        threading.Thread(
            target=relay, args=(client, remote), daemon=True
        ).start()
        relay(remote, client)
    except (OSError, IndexError, struct.error):
        pass
    finally:
        try:
            client.close()
        except OSError:
            pass


def main() -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(8)
    print(f"socks5 listening on 127.0.0.1:{port}", flush=True)
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
