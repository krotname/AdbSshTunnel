# ADB SSH Tunnel specifications

The specs describe the existing product contract, not a promise that every
scenario has been exercised on every device. Android and Linux have different
ADB protocols and network identities; preserve those distinctions.

| Capability | Owning sources | Verification |
|---|---|---|
| [Android tunnel](specs/android-tunnel/spec.md) | `app/src/main/`, `tools/tunnel-*.h` | Java unit tests, native lifecycle regressions, `docs/USAGE.md` |
| [Android networks](specs/android-network-policy/spec.md) | `NetworkPolicy.java`, `NetworkMonitor.java`, `NetworkSettings.java` | `NetworkPolicyTest`, `NetworkDiagnosticsTest`, `docs/network-policy-acceptance.md` |
| [Keys and identity](specs/key-security/spec.md) | `KeyPolicy.java`, `HostKeyPublic.java`, `linux/adb_ssh_tunnel/core.py` | `KeyPolicyTest`, `TestHostKeyPublic.java`, Linux protocol tests |
| [Linux tunnel](specs/linux-tunnel/spec.md) | `linux/adb_ssh_tunnel/`, `linux/packaging/` | `linux/tests/`, `linux/README.md` |
| [Development workflow](specs/development-workflow/spec.md) | `.github/workflows/ci.yml`, `tools/`, npm lockfile | `tools/test-openspec.py`, strict OpenSpec validation, Android CI |

## Work on a change

1. Read the affected specs before changing implementation or tests.
2. For changed behavior, create `openspec/changes/<change-id>/` with
   `proposal.md`, `design.md`, `tasks.md` and `specs/<capability>/spec.md` deltas
   using `## ADDED Requirements`, `## MODIFIED Requirements` or
   `## REMOVED Requirements`. Describe tests and compatibility before coding.
   Small corrections may update the relevant main spec directly; describe the
   preserved contract and regression scenario for behavior-preserving changes.
3. Update the matching spec or delta in the same PR as code. A CI-only spec
   cannot cover Android or Linux implementation changes. Archived deltas and
   deleted specs cannot supply coverage. A delta used for coverage needs its
   proposal, design and tasks in the same change directory.
4. Run the checks below and the targeted product tests. Keep tasks and evidence
   accurate. Archive completed changes with the CLI, which applies their deltas
   to the main specs; commit the resulting main-spec updates together.

## Checks

Node >=22.12 and Python >=3.11 are needed. Install the exact locked CLI without
dependency lifecycle scripts:

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm run spec:validate
python tools/test-openspec.py
python tools/check-openspec.py --base origin/main
```

Use `--snapshot` instead of `--base` only to inspect a checkout without a diff.
CI uses the event's exact base SHA for pull requests and pushes; missing or
invalid bases fail. Manual workflow runs validate the current snapshot.

The existing `android` check runs these gates before native compilation,
Linux packaging and Android tests/builds. It has no path filter or optional
failure setting. Main requires that check and `OpenSpec required`. The final
check runs on GitHub-hosted Ubuntu without checkout or shared cache and requires
an actual successful Android job; skipped, cancelled and failed jobs are rejected.
Structural validation and changed file coverage do not prove semantic agreement between code and prose: inspect
the diff and run the referenced tests and device acceptance when affected.
