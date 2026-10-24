# TermBridge — SSH Endpoint Prerequisites (Prompt 05)

TermBridge connects to servers that speak SSH. It does not install,
enable, or configure SSH on the server — if the server does not
offer SSH, the connection fails with a clear error, and that failure
is not an app bug.

## Linux / Unix servers

- An SSH server (usually OpenSSH `sshd`) must be installed, running,
  and reachable on the host's port (default 22, or the port in the
  host profile).
- The username and credential in the host profile must be valid on
  that server. Password auth must be enabled on the server for
  password hosts; key auth requires the public key in the server's
  `authorized_keys` for that user.
- SFTP (prompt 07) additionally requires the server to offer the
  SFTP subsystem — most OpenSSH servers do by default. If a server
  offers SSH but not SFTP, prompt 07 must surface that as "SFTP not
  offered by this server", not as a generic failure.

## Windows servers

- Do NOT assume Windows has SSH enabled. Windows 10/11 and Windows
  Server can run Microsoft's OpenSSH Server as an optional feature,
  but it is not enabled by default.
- Prerequisites for a Windows target: OpenSSH Server installed
  (Settings → Optional features, or `Add-WindowsCapability`), the
  `sshd` service running, and the firewall allowing the SSH port.
- A Windows target without OpenSSH Server will refuse the
  connection (ConnectionRefused) — the app reports exactly that.
- Terminal behaviour on Windows SSH targets (shell type, encoding)
  is a prompt-06 concern and is not assumed here.

## What TermBridge never assumes

- No specific Linux distribution, shell, or home-directory layout.
- No port other than the one in the host profile.
- No agent forwarding, no X11 forwarding, no port forwarding in
  this phase (advanced SSH is prompt 10).
- No server-side software installed by the app.
