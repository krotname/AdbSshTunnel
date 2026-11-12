# Development workflow

## Purpose

Make the product's OpenSpec contract a mandatory, reproducible part of the
existing Android and Linux delivery pipeline while preserving its runner,
cache, security boundaries and distinction between builds and publication.

## Requirements

### Requirement: All specifications are validated before builds
Every supported CI event SHALL run the locked OpenSpec CLI in strict,
noninteractive mode over all main specs and active changes before compilation,
product tests or packaging. A failed check SHALL stop the existing `android` job.

#### Scenario: A requirement has no valid scenario
- **WHEN** a main spec or active delta fails strict validation
- **THEN** CI fails before compiling or packaging either product

#### Scenario: Required baseline is removed
- **WHEN** any of the five baseline capability specifications is absent or empty
- **THEN** the repository gate fails even if the CLI reports no remaining items to validate

### Requirement: Implementation changes carry relevant specification changes
Pull requests and pushes SHALL compare the exact event base against the checked
out revision. Android, Linux and delivery-tooling changes, including CMake build
manifests and OpenSpec configuration, SHALL include an
added or modified specification for their respective capability group. Shared
key-security specs MAY cover either product. Deleted or archived deltas SHALL
provide no coverage. Active deltas used for coverage SHALL have a proposal,
design and task list.

#### Scenario: Code changes without matching specification changes
- **WHEN** Android implementation changes while only a Linux or workflow spec changes
- **THEN** CI rejects the uncovered Android change

#### Scenario: Documentation-only update
- **WHEN** a PR changes ordinary documentation or images without changing implementation or behavioral instructions
- **THEN** all specs are validated without requiring an unrelated specification edit

#### Scenario: Diff base is missing or invalid
- **WHEN** a push or pull request cannot resolve its supplied comparison base
- **THEN** the gate fails rather than silently skipping changed-file coverage

### Requirement: Existing runner and tests remain authoritative
The pipeline SHALL retain its owner-source restriction, repository-scoped Red
ARC pool, cache contract, concurrency policy, timeout and existing native,
Linux and Android tests. Main SHALL require the `android` status check without
introducing a human approval gate.

#### Scenario: OpenSpec succeeds but a product test fails
- **WHEN** the later native, Linux or Android checks fail
- **THEN** the required CI check fails and the change remains unmergeable

### Requirement: Verification claims match their evidence
Specifications and reports SHALL distinguish structural validation, regression
tests, device acceptance, signed artifacts, installation and public release.

#### Scenario: CI produces unsigned packages
- **WHEN** CI validates specs and builds unsigned artifacts
- **THEN** the result is recorded as tested build evidence without claiming device installation or store publication
