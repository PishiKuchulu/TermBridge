#!/usr/bin/env python3
"""TermBridge fixture TCP echo server (item T-20).

A deliberately tiny threaded echo service on loopback. It is
the target the port-forwarding integration tests talk THROUGH
the tunnels to: a local forward points at it from the SSH
server's side, and a remote forward's server-side target is
also this service. Anything received is sent back verbatim.

Usage: echo_server.py [port]   (default 18081)
Started by tests/fixture/setup-sshd-fixture.sh; test-only,
loopback only, holds no state and no credentials.
"""
import socket
import sys
import threading

port = int(sys.argv[1]) if len(sys.argv) > 1 else 18081


def handle(conn: socket.socket) -> None:
    try:
        while True:
            data = conn.recv(4096)
            if not data:
                break
            conn.sendall(data)
    except OSError:
        pass
    finally:
        conn.close()


def main() -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(8)
    print(f"echo listening on 127.0.0.1:{port}", flush=True)
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
