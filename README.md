# Spese Offline 4.0

App Android personale per gestire entrate, uscite, budget e analisi finanziarie in locale.

## Novità v4
- redesign completo dell'interfaccia in stile fintech
- palette chiara/scura dedicata
- tipografia e gerarchia visiva più curate
- dashboard con saldo in primo piano
- mini-card separate per entrate e uscite
- navigazione inferiore con icone Material
- pulsante rapido "Nuovo movimento"
- intestazioni più chiare in Home, Movimenti, Analisi e Gestione
- selettore mese ridisegnato
- grafico circolare rifinito e corretto
- importi entrata/uscita differenziati visivamente
- card e spaziature uniformate

## Funzioni preservate dalla v3
- entrate e uscite
- saldo mensile
- budget generale e per categoria
- categorie con icona e colore
- modifica, duplicazione ed eliminazione movimenti
- ricerca e filtri
- movimenti ricorrenti
- analisi ultimi 6 mesi
- dashboard annuale
- confronto col mese precedente
- CSV per Excel
- backup/ripristino JSON
- PIN locale
- biometria Android opzionale
- tema chiaro/scuro

## Privacy
- nessun `android.permission.INTERNET`
- `android:allowBackup="false"`
- database SQLite privato dell'app
- PIN derivato con PBKDF2-HMAC-SHA256 e salt casuale
- dati biometrici gestiti esclusivamente dal sistema Android
- esportazioni solo su azione esplicita dell'utente

## Compatibilità dati
La v4 mantiene il database schema v3: installando la nuova versione sopra la v3, i dati esistenti restano compatibili.

## Stack
- Android SDK 37
- Android Gradle Plugin 9.4.0
- Gradle 9.6 configurato
- Kotlin 2.3.21
- Jetpack Compose BOM 2026.08.00
- Material 3
- Material Icons Extended
- AndroidX Biometric 1.1.0
- SQLite nativo

## Apertura
1. Estrai lo ZIP.
2. Apri la cartella `SpeseOffline` con Android Studio.
3. Usa JDK 17.
4. Esegui Gradle Sync.
5. Avvia su dispositivo/emulatore Android 8.0+.

## Nota build
Nel pacchetto sorgente non è incluso `gradle-wrapper.jar`. Se Android Studio lo richiede, rigenera una volta il wrapper con:
`gradle wrapper --gradle-version 9.6.0`

La compilazione iniziale sul PC può richiedere Internet per scaricare Gradle e dipendenze. L'app installata non possiede il permesso Internet.
