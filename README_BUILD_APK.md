# Build APK automatica

Il repository include `.github/workflows/build-apk.yml`.

Ad ogni push su `main` (o tramite avvio manuale), GitHub Actions:

1. prepara JDK 17;
2. prepara Android SDK 37;
3. prepara Gradle 9.6.0;
4. esegue `:app:assembleDebug`;
5. pubblica l'APK come artifact `SpeseOffline-v4-debug-apk`.

APK atteso:
`app/build/outputs/apk/debug/app-debug.apk`

La pipeline non richiede `gradle-wrapper.jar` perché installa direttamente Gradle 9.6.0.
