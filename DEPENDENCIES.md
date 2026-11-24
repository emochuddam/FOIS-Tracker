# Dependencies

| Component | Version and use | License |
| --- | --- | --- |
| [Android Liquid Glass](https://github.com/Kyant0/AndroidLiquidGlass) | Backdrop 1.0.6, native Compose backdrop sampling, blur and lens refraction | Apache 2.0 |
| [AndroidX](https://github.com/androidx/androidx) | Compose BOM 2026.03.00, Activity 1.12.4, Fragment 1.8.9, Biometric 1.1.0, WorkManager 2.11.1, SplashScreen 1.0.1 | Apache 2.0 |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2, IO dispatch and request serialization | Apache 2.0 |
| [OkHttp](https://square.github.io/okhttp/) | 4.12.0, HTTPS and cookie scoping. MockWebServer for fixtures | Apache 2.0 |
| [jsoup](https://github.com/jhy/jsoup) | 1.21.2, HTML parsing | MIT |
| [JUnit 4](https://github.com/junit-team/junit4) | 4.13.2, JVM tests | EPL 1.0 |

The Android Liquid Glass artifact and its Shapes dependency are resolved from Maven Central. Backdrop 1.0.6 declares Kotlin 2.3.10 and Compose 1.10.3. This app uses Kotlin 2.3.10 and a Compose BOM on the 1.10 line. The app uses Android 13 and later for refractive surfaces and uses an opaque Material surface fallback on older APIs and in AMOLED mode.

[Now in Android](https://github.com/android/nowinandroid) is a reference application under Apache 2.0. No project files or assets are copied. [Chaquopy](https://github.com/chaquo/chaquopy) (MIT) is unnecessary because the FOIS protocol is implemented in Kotlin and Java cryptography. Compose animates the UI, so no [Lottie](https://github.com/airbnb/lottie-android) (Apache 2.0) runtime is required.

The workflow uses [setup-android](https://github.com/android-actions/setup-android) v4 (MIT), [setup-gradle](https://github.com/gradle/actions/tree/main/setup-gradle) v4, and [upload-artifact](https://github.com/actions/upload-artifact) v4 (MIT), with [Android Emulator Runner](https://github.com/ReactiveCircus/android-emulator-runner) v2 (Apache 2.0) for instrumentation. SDK components are installed under their Android SDK license terms.
