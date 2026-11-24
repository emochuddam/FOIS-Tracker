# Changelog

## 1.0.2

- Simplified the app theme to standard Material 3 components and removed the Liquid Glass dependency.
- Added one-second foreground refresh with inline status for open authenticated results.

## 1.0.1

- Show the FNR in the result header and expand freight details by default.
- Accept FNRs and station pairs from the Search tab and show the verification screen immediately for guests.
- Request notification permission on Android 13 and later and add a shortcut in Settings.
- Follow the device theme and refresh open authenticated results every 30 seconds.

## 1.0.0

- Rebuilt the native Android client in Kotlin and Jetpack Compose, preserving the app ID and public FNR form.
- Added authorized ECBS sign-in, retained-session expiry handling, and station-pair enquiries with receipt-detail enrichment.
- Added genuine Backdrop glass effects, five main tabs, four theme modes, and optional biometric app lock.
- Added multi-FNR scheduled refresh, configurable alert channels, saved routes, SQLite result history and ETA history.
- Added pull-to-refresh, update timestamps, journey filtering, and PDF, CSV and text reports.
- Added Android 16 build and test jobs, signed debug APK artifacts, and optional signed release publishing.
