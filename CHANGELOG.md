# Changelog

## 1.1.1

- Reduce background work: unchanged root maintenance preserves the running guard and avoids rewriting its policy. Changed policy or a missing/replaced watcher still triggers the full fail-closed apply.
- Read the large Wi-Fi diagnostic dump only when hotspot identity or an unnamed matching station requires it. Keep the 30-second security check and immediate network-event handling.
- Avoid repeated notification, tile and UI updates for an unchanged tunnel state.
