# Keys and server identity

## Purpose

Authenticate trusted computers using imported public keys, protect the server's
persistent identity and keep platform-specific key acceptance rules explicit.
Private client keys remain with the client, outside application configuration.

## Requirements

### Requirement: Public-key-only authentication
Both products SHALL authenticate SSH clients only with imported public keys
and SHALL reject password authentication, private-key imports and authorized-key
options supplied as part of an imported key line.

#### Scenario: Client uses a password or an untrusted key
- **WHEN** a client authenticates without an accepted imported public key
- **THEN** authentication is rejected

#### Scenario: Import contains a private key or key options
- **WHEN** the user imports a private key or a public-key line prefixed with options
- **THEN** the import is rejected rather than interpreted as server configuration

### Requirement: Platform-specific key validation
Android SHALL accept validated Ed25519 and RSA public keys, with RSA at least
2048 bits, at most 32 distinct keys and at most 65536 input characters. Linux
SHALL apply its public-key parser and require RSA keys of at least 3072 bits.

#### Scenario: Android receives invalid encoded key material
- **WHEN** key type, decoded structure, Ed25519 point or RSA parameters are invalid
- **THEN** Android rejects the import

#### Scenario: Linux receives a short RSA key
- **WHEN** an RSA public key has fewer than 3072 bits
- **THEN** Linux rejects the import

### Requirement: Persistent verifiable server identity
Each installation SHALL retain a private SSH host key and expose its public
SHA256 fingerprint so the user can verify server identity through a trusted path.
Changing permitted client keys or network rules SHALL preserve that identity.

#### Scenario: Settings or package version changes
- **WHEN** keys, network rules or a normal package upgrade change configuration
- **THEN** the existing host identity remains available instead of being silently replaced

### Requirement: Revoked keys lose access
The server SHALL reevaluate active access when authorized client keys change.

#### Scenario: An active client's key is removed
- **WHEN** the user revokes an imported key used by a connected client
- **THEN** that client loses access and cannot establish a new authenticated tunnel with the removed key
