# Changelog

## Unreleased

- Animated power button: a rotating glow while the tunnel connects, a soft breathing glow when it is ready and a smooth fade when it turns off. Colours change smoothly; the glow stays still when system animations are off.
- Plain-language errors: every problem says what happened and what to do, with an icon, a button that opens the right screen and the original message under "Technical details".
- Nested menus restyled to match the main screen: rounded cards, coloured icons, large titles and inline errors instead of plain dialogs and toasts.

## 2.2.0

- New main screen without scrolling: a large colour-coded on/off button with a plain-language status and four tiles (Connect, Networks & keys, ADB mode, More) that open bottom sheets. All previous functions and settings are unchanged.

## 1.1.2

- Resolve Wireless Debugging advertisements one at a time, so other phones on the LAN cannot occupy a concurrent resolution and silently hide the local phone.
- Coalesce newer advertisements, discard lost/stopped services and retry a failed resolution or local TLS probe once without periodic polling.

## 1.1.1

- Reduce background work: unchanged root maintenance preserves the running guard and avoids rewriting its policy. Changed policy or a missing/replaced watcher still triggers the full fail-closed apply.
- Read the large Wi-Fi diagnostic dump only when hotspot identity or an unnamed matching station requires it. Keep the 30-second security check and immediate network-event handling.
- Avoid repeated notification, tile and UI updates for an unchanged tunnel state.
