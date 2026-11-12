# Linux tunnel

## Purpose

Expose a Linux SDK ADB server to trusted computers through a restricted SSH
gateway managed by the user's systemd session. This protocol and its network
identities remain distinct from Android wireless debugging and Android adbd.

## Requirements

### Requirement: Only the loopback SDK ADB server is forwarded
Linux SHALL allow the SSH username `adb` to forward only `127.0.0.1:5037`, using
SSH port `19191` by default. Shell, exec, PTY, file transfer, agent/X11, UNIX
socket and remote forwarding requests SHALL be rejected.

#### Scenario: Client uses the SDK server protocol
- **WHEN** an authorized client forwards the exact destination and uses `adb -H 127.0.0.1 -P <local-port> devices -l`
- **THEN** it receives the Linux SDK server's list of already attached or paired devices, which may be empty

#### Scenario: Client requests another target or SSH service
- **WHEN** a client requests `localhost`, another port, a shell or reverse forwarding
- **THEN** the SSH gateway denies the request

### Requirement: Known permitted physical network is required
Linux SHALL start stopped with an empty allowlist, give blocked entries
precedence and deny missing identities. Wi-Fi SHALL use SSIDs; Ethernet and
mobile SHALL use NetworkManager connection UUIDs.

#### Scenario: New installation or unknown network
- **WHEN** no permitted current network identity is configured
- **THEN** the listener remains closed

### Requirement: Listener binds to one approved IPv4 address
Linux SHALL bind only the current IPv4 address of the supported physical
NetworkManager profile selected by the default route, rather than all interfaces.

#### Scenario: VPN route has no supported physical profile
- **WHEN** the selected route cannot be associated with an eligible physical network identity
- **THEN** the listener remains closed

### Requirement: Revocation precedes continued access
Linux SHALL close its listener and sessions when polling detects a denied
network, changed address, key revocation, invalid configuration or unavailable
ADB server, and resume only when an enabled tunnel again meets its prerequisites.

#### Scenario: ADB is unavailable while access becomes forbidden
- **WHEN** network policy revokes access while ADB diagnostics are stalled
- **THEN** revocation closes existing access without waiting for the stalled diagnostic

### Requirement: Background service preserves user control
Linux SHALL manage the gateway through `adb-ssh-tunnel.service` in the user's
systemd session. Closing the UI SHALL preserve an enabled tunnel; explicit stop
SHALL close it. The app SHALL leave the SDK server, system sshd and routing under
the user's control.

#### Scenario: User closes and reopens the window
- **WHEN** the UI closes while the service is enabled
- **THEN** the service continues subject to policy and reopening the UI shows its current state
