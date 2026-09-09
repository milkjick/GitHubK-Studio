# Dhizuku compileSdk compatibility fix

The app uses `io.github.iamr0s:Dhizuku-API:2.5.4` instead of 2.6.0.

`Dhizuku-API:2.6.0` publishes AAR metadata requiring compileSdk 37, while this project intentionally builds against Android API 36 for AIDE/Termux compatibility. Dhizuku 2.5.4 targets Android 36, so it removes the AAR metadata failure without changing the application target/min SDK.

Project settings:
- compileSdk: 36
- targetSdk: 36
- minSdk: 24
- Dhizuku-API: 2.5.4
