# Android network policy

## Purpose

Restrict Android tunnel availability to user-selected physical networks and
revoke access when the network, its identity or the user's rules no longer
permit it. The policy remains independent of VPN routing configuration.

## Requirements

### Requirement: Explicit network policy with deny precedence
Android SHALL evaluate Wi-Fi SSID and mobile operator identities against the
selected allowlist or denylist mode. Explicit denial SHALL take precedence.

#### Scenario: Identity is both allowed and denied
- **WHEN** the current network matches entries in both lists
- **THEN** tunnel access on that network is denied

#### Scenario: Allowlist is empty
- **WHEN** allowlist mode has no matching permitted network
- **THEN** the tunnel has no listener on that network

#### Scenario: Known network in denylist mode
- **WHEN** denylist mode is selected and a known eligible network has no matching denial
- **THEN** the network policy permits it subject to the other tunnel prerequisites

### Requirement: Unavailable identities fail closed
Android SHALL deny network access when required identity information or the
permission needed to obtain it is unavailable.

#### Scenario: Location permission or SSID is lost
- **WHEN** the current Wi-Fi SSID can no longer be established
- **THEN** access is revoked and the interface explains how to restore identity detection

### Requirement: Policy changes revoke established access
Android SHALL reevaluate policy when network identity, addresses, permissions
or stored rules change, closing sessions that are no longer authorized.

#### Scenario: SSID changes without an address change
- **WHEN** Wi-Fi changes to another SSID while retaining the same IP address
- **THEN** old sessions close and the new SSID receives a fresh policy decision

#### Scenario: User changes a rule
- **WHEN** a saved rule changes the active network's permission
- **THEN** the new decision takes effect immediately and remains after process recreation

### Requirement: Physical network decisions remain separate
Android SHALL bind listeners only on eligible permitted networks, excluding IMS
networks, and SHALL evaluate physical network identity independently of VPN state.

#### Scenario: Wi-Fi and mobile are available together
- **WHEN** multiple physical networks are present
- **THEN** only networks individually permitted by policy expose tunnel access
