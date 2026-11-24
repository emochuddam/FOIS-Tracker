# Dependencies

| Component | Version and use | License |
| --- | --- | --- |
| [AndroidX](https://github.com/androidx/androidx) | Compose BOM 2026.03.00, Activity 1.12.4, Fragment 1.8.9, Biometric 1.1.0, WorkManager 2.11.1, SplashScreen 1.0.1 | Apache 2.0 |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2, IO dispatch and request serialization | Apache 2.0 |
| [OkHttp](https://square.github.io/okhttp/) | 4.12.0, HTTPS and cookie scoping. MockWebServer for fixtures | Apache 2.0 |
| [jsoup](https://github.com/jhy/jsoup) | 1.21.2, HTML parsing | MIT |
| [JUnit 4](https://github.com/junit-team/junit4) | 4.13.2, JVM tests | EPL 1.0 |


[Now in Android](https://github.com/android/nowinandroid) is a reference application under Apache 2.0. No project files or assets are copied. [Chaquopy](https://github.com/chaquo/chaquopy) (MIT) is unnecessary because the FOIS protocol is implemented in Kotlin and Java cryptography. Compose animates the UI, so no [Lottie](https://github.com/airbnb/lottie-android) (Apache 2.0) runtime is required.

The workflow uses [setup-android](https://github.com/android-actions/setup-android) 4.0.4 (MIT), [setup-gradle](https://github.com/gradle/actions/tree/main/setup-gradle) 6.4.1 (MIT), and [upload-artifact](https://github.com/actions/upload-artifact) 7.0.2 (MIT), with [Android Emulator Runner](https://github.com/ReactiveCircus/android-emulator-runner) v2 (Apache 2.0) for instrumentation. SDK components are installed under their Android SDK license terms.
