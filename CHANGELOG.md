# Changelog

## 1.0.8

- Added a first-launch and Settings choice for Light, Dark, or System.
- Adapted the supplied dark kit with deep navy cards, violet accents, and Prompt typography.
- Display known last-reported station names with their codes, including KOHL (KOHLI).

## 1.0.7

- Applied the attached UI kit’s gradient palette, Montserrat typography, and softly raised controls.
- Made background Live a prominent button at the top of freight results and added a softly pulsing Live indicator.
- Removed the file export action and Journey timeline screen section.

## 1.0.6

- Smoothed screen changes, button presses, and freight detail expansion.
- Display reported remaining distance in red.
- Include estimated arrival in refresh, freight-change, and background Live notifications.

## 1.0.5

- Added a Live label beside the green indicator.
- Added user-started background Live tracking, with an ongoing notification and Stop action.
- Show the latest stored result when returning to the app.

## 1.0.4

- Added route summaries to freight results and saved freight cards.
- Replaced live-check text with a blinking green status light.
- Send completion notifications for manual refresh and freight-change notifications during automatic tracking.
- Prefill the save dialog and open saved freight with the same enquiry flow as Search.

## 1.0.3

- Recognize formatted FNRs on FOIS receipt pages and fall back to the first receipt when a link is absent.
- Add neutral backgrounds, card shadows, smooth button presses, and a green live-refresh indicator.
- Show remaining kilometers beneath the ETA and replace sharing actions with clipboard controls.

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
