# Android tunnel

## Purpose

Provide trusted computers with restricted SSH access to this Android device's
local ADB endpoint while preserving Android debugging authorization and the
explicit distinction between wireless debugging and root-assisted operation.

## Requirements

### Requirement: Stable restricted destination
The Android server SHALL accept local TCP forwarding only to the virtual
destination `127.0.0.1:5555` and resolve it to the currently verified local ADB
endpoint. The default SSH listening port SHALL be `19191`.

#### Scenario: Wireless debugging port changes
- **WHEN** Android replaces a previously discovered wireless debugging port
- **THEN** the old connection is revoked and the same SSH forwarding command can use the newly verified local endpoint

#### Scenario: Client requests another destination or SSH service
- **WHEN** a client requests another host or port, shell, exec, PTY, SFTP or reverse forwarding
- **THEN** the SSH server rejects the request

### Requirement: Nonroot debugging remains system controlled
Nonroot operation SHALL depend on Android's existing wireless debugging and
pairing, without enabling a replacement adbd listener or obtaining root.

#### Scenario: Wireless debugging is disabled
- **WHEN** the system wireless debugging endpoint becomes unavailable
- **THEN** new ADB channels are denied and existing SSH/ADB access is revoked

### Requirement: Root operation requires its controller
Root ADB SHALL operate only through the supported native controller and granted
root authorization, with rejection when its prerequisites are unavailable.

#### Scenario: Root authorization or controller is missing
- **WHEN** root mode is selected but authorization or the controller is unavailable
- **THEN** root operation fails with a diagnostic instead of granting nonroot operation root privileges

### Requirement: Visible and explicit tunnel control
The app SHALL expose tunnel state, connection details and actionable diagnostics
in English and Russian, with an explicit stop control and a quick-settings tile.

#### Scenario: User stops the tunnel
- **WHEN** the user disables the tunnel using the app or tile
- **THEN** the SSH listener and active tunnel access close

#### Scenario: A prerequisite prevents access
- **WHEN** a required key, network identity or debugging endpoint is unavailable
- **THEN** the interface reports the blocking condition and a recovery action
