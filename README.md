<p align="center">
  <img src="branding/logo.png" width="128" alt="Wisp-Logo" />
</p>

<h1 align="center">Wisp</h1>

<p align="center"><b>Ein persönlicher Sprachassistent für Android – Live-Gespräche über Grok Voice oder OpenAI Realtime, mit echten Aktionen am Handy.</b></p>

---

Wisp ersetzt Gemini als digitalen Assistenten. Du drückst lange auf die Power-Taste, ein Sheet gleitet über die aktuelle App, und du redest einfach los. Wisp antwortet mit Stimme, du kannst jederzeit reinreden, und nebenbei stellt es Timer, liest Nachrichten vor, ruft Leute an oder schaltet WLAN aus.

Die App ist für den Eigengebrauch gebaut und wird per Sideload installiert, nicht über den Play Store.

## Screenshots

| Start | Gespräch | Overlay | Einstellungen |
|---|---|---|---|
| ![](docs/screenshots/home.png) | ![](docs/screenshots/conversation.png) | ![](docs/screenshots/overlay.png) | ![](docs/screenshots/settings.png) |

*(Platzhalter – eigene Screenshots in `docs/screenshots/` ablegen.)*

## Der Name

Vorschläge, die im Rennen waren:

| Name | Begründung |
|---|---|
| **Wisp** ✅ | Englisch für „Hauch“ oder „Wölkchen“: eine leise, leichte Stimme, die da ist, wenn man sie braucht. Kurz, im Deutschen gut aussprechbar, keine bekannte App heißt so. |
| Sona | Von lat. *sonus* (Klang). Schön, aber es gibt bereits KI-Produkte mit dem Namen. |
| Nuri | Weich und freundlich, aber ohne Bezug zu Stimme. |
| Olli | Sympathisch, wirkt aber eher wie ein Kinderspielzeug. |
| Mivo | Kunstwort, gut aussprechbar, aber beliebig. |

Package: `dev.davidx3d.wisp`

## Logo

`branding/logo.svg` ist ein weiches, neunzackiges „Cookie“ (eine der Expressive-Shapes aus Material 3) mit einem Verlauf von Violett über Magenta zu Koralle. Darin sitzt eine kurze Wellenform aus zwei Punkten und drei Balken, also eine gesprochene Phrase statt eines Mikrofons. `branding/logo-mono.svg` ist die einfarbige Variante für Themed Icons. Das Adaptive Icon (Vordergrund, Hintergrund, Monochrom-Layer) erzeugt `scripts/make_logo.py`.

## Features

**Onboarding**
- Beim ersten Start: Name, Anbieter mit API-Key, Mikrofon. Die KI kennt deinen Namen und spricht dich damit an (änderbar unter Einstellungen → Persönlichkeit).

**Gespräche**
- Live-Sprachgespräche über die **xAI Grok Voice Agent API** (`grok-voice-latest`) oder die **OpenAI Realtime API** (`gpt-realtime-2.1`), in den Einstellungen umschaltbar
- Live-Transkript in beide Richtungen, Wort für Wort, mit getrennten Bubble-Stilen für dich und Wisp
- **Barge-in:** Wenn du reinredest, verstummt Wisp sofort. Der Server erfährt, wie viel von der Antwort du gehört hast (`conversation.item.truncate`).
- Echo-Unterdrückung über die Audioquelle `VOICE_COMMUNICATION` plus `AcousticEchoCanceler`, `NoiseSuppressor` und `AutomaticGainControl`
- Freihändig (Server-VAD, bei OpenAI auch semantisch) oder Push-to-Talk, mit einstellbarer Empfindlichkeit
- Tippen statt Sprechen ist jederzeit möglich
- Automatischer Reconnect mit Backoff. Bei xAI wird das Gespräch über `resumption` fortgesetzt, bei OpenAI wird der bisherige Verlauf neu eingespielt.
- Klare Fehlermeldungen für falschen Key, fehlendes Guthaben, Rate-Limit oder fehlendes Netz
- Verlauf lokal in Room, durchsuchbar und löschbar
- Minuten- und Kostenanzeige pro Gespräch und insgesamt

**Als System-Assistent**
- `VoiceInteractionService`: lässt sich als digitaler Standard-Assistent auswählen und startet per Power-Taste, Ecken-Wischgeste oder Assist-Geste
- Overlay als Bottom-Sheet über der aktuellen App, lässt sich auf Vollbild ziehen und nach unten wegwischen
- Foreground-Service (Typ `microphone`) mit Benachrichtigung zum Beenden
- Schnelleinstellungs-Kachel und Homescreen-Widget

**Werkzeuge** (jedes einzeln abschaltbar, mit Anzeige der Berechtigung)

| Bereich | Was geht |
|---|---|
| Zeit | Timer stellen und abbrechen, Wecker mit Wochentagen und Name stellen, nächsten Wecker nennen, Wecker ausschalten |
| Kalender | Termine für heute, morgen, die Woche oder einen Zeitraum lesen; Termine und Erinnerungen anlegen |
| Kommunikation | Kontakte unscharf suchen; anrufen; SMS senden und vorlesen; Benachrichtigungen (WhatsApp, Telegram, Signal …) vorlesen; über die Antwort-Funktion der Benachrichtigung antworten |
| Gerät | Taschenlampe, Lautstärke (Medien, Klingelton, Wecker …), Nicht stören, Helligkeit, automatisches Drehen, Akku/Uhrzeit/Speicher, Zwischenablage, Apps öffnen |
| Medien | Play, Pause, Weiter, Zurück; welcher Titel gerade läuft |
| Infos | Standort, Wetter über Open-Meteo (bei xAI zusätzlich die eingebaute Websuche) |
| Bildschirm | Text des aktuellen Bildschirms vorlesen (über die Assist-Daten oder eine optionale Bedienungshilfe) |
| Systemsteuerung | WLAN, mobile Daten, Bluetooth, Flugmodus, NFC, Standort an/aus; App beenden; Neustart – über **Shizuku** oder **Root (KernelSU)** |

Anrufe, Nachrichten und Neustart fragen vorher nach. Du kannst „ja“ sagen oder auf den Button tippen. Das lässt sich abschalten.

## Setup

### 1. Bauen und installieren

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Voraussetzungen: JDK 17 und ein Android SDK mit Plattform 37. Das Gerät braucht Android 12 oder neuer.

### 2. API-Key holen

- **xAI:** [console.x.ai](https://console.x.ai) → API Keys → neuen Key anlegen. Guthaben aufladen.
- **OpenAI:** [platform.openai.com](https://platform.openai.com/api-keys) → API keys. Guthaben aufladen.

In Wisp: **Einstellungen → Anbieter & API-Keys**, Key einfügen, „Speichern & testen“. Der Key wird mit einem Schlüssel aus dem Android Keystore (AES-256-GCM) verschlüsselt und taucht in keinem Log auf.

### 3. Als Assistent festlegen

**Einstellungen → Als Standard-Assistent → Zur Systemeinstellung**, dort „Wisp“ wählen. Auf Pixel-Geräten liegt das unter *Einstellungen → Apps → Standard-Apps → Digitaler Assistent*. Für „Bildschirm vorlesen“ dort zusätzlich *Text auf dem Bildschirm verwenden* einschalten.

### 4. Berechtigungen

Unter **Einstellungen → Werkzeuge** zeigt jedes Werkzeug, was ihm fehlt, mit einem Button „Berechtigung erteilen“:

- Laufzeitrechte (Mikrofon, Kontakte, Telefon, SMS, Kalender, Standort) per Systemdialog
- **Benachrichtigungszugriff** für Nachrichten vorlesen, Antworten und „Was läuft gerade?“
- **Systemeinstellungen ändern** für Helligkeit und Drehen
- **Bitte nicht stören**-Zugriff
- Optional die **Bedienungshilfe** „Wisp Bildschirm lesen“

Android 13+ blockiert eingeschränkte Rechte (SMS, Benachrichtigungen, Bedienungshilfe) bei sideloaded Apps zunächst. Lösung: *App-Info → ⋮ → Eingeschränkte Einstellungen zulassen*.

### 5. Systemsteuerung (optional)

Für WLAN, Flugmodus & Co. braucht Wisp erweiterte Rechte:

- **Shizuku (empfohlen, kein Root nötig):** [Shizuku](https://shizuku.rikka.app/) installieren, per kabellosem Debugging starten, dann in Wisp unter *Werkzeuge → Systemsteuerung* auf „Shizuku erlauben“.
- **Root:** Mit KernelSU (oder Magisk) „Root als Ausweichweg“ einschalten und Wisp in der KernelSU-App freigeben.

Wisp nimmt Shizuku, wenn es läuft, sonst Root. Ohne beides öffnet Wisp bei WLAN, Daten und NFC den passenden System-Schalter.

## Kosten

Beide APIs kosten Geld pro Nutzung. Wisp zeigt eine Schätzung an, verbindlich ist die Abrechnung beim Anbieter.

| Anbieter | Preis (Stand Nov. 2026) | 10 Minuten am Tag |
|---|---|---|
| xAI Grok Voice | 0,08 $ pro Minute | ca. 24 $ im Monat |
| OpenAI gpt-realtime-2.1 | Audio rein 32 $, Audio raus 64 $ pro 1 Mio. Tokens | je nach Gesprächsanteil ähnlich bis höher |

Ein Gespräch läuft nur, solange das Overlay offen ist oder die Benachrichtigung „Gespräch mit Wisp“ sichtbar ist. Schließen beendet es sofort.

## Technik

- Kotlin, Jetpack Compose, **Material 3 Expressive** (`MaterialExpressiveTheme`, `MotionScheme.expressive()`, `MaterialShapes`, `LoadingIndicator`, Flexible-App-Bars, verbundene Toggle-Buttons)
- Dynamic Color mit eigener Fallback-Palette; die Markenfarben werden in die Wallpaper-Farben gemischt
- Schrift: Outfit (SIL OFL, `branding/fonts/OFL-Outfit.txt`)
- MVVM, Hilt, Coroutines/Flow, DataStore, Room
- OkHttp-WebSocket für beide Anbieter, 24 kHz PCM16 mono
- minSdk 31, targetSdk 37, Gradle-Version-Catalog

```
app/src/main/java/dev/davidx3d/wisp/
├── realtime/   Adapter xAI/OpenAI, Event-Parser, WebSocket-Client, REST-Helfer
├── audio/      AudioRecord/AudioTrack mit AEC, Pegel, Barge-in-Tracking
├── session/    VoiceSessionManager (Herzstück) + Foreground-Service
├── tools/      Tool-Interface, Registry, Berechtigungen, alle Werkzeuge, Shizuku/Root-Shell
├── assist/     VoiceInteractionService, Session-Overlay, Kachel, Widget
├── services/   Notification-Listener, Bedienungshilfe, Bildschirmtext
├── data/       Einstellungen, verschlüsselte Keys, Verlauf
└── ui/         Theme, Orb, Aurora, Transkript, Screens
```

### Tools hinzufügen

Ein Werkzeug ist eine Klasse, die `Tool` implementiert: Name, Beschreibung, JSON-Schema, benötigte Rechte und `suspend fun execute(args, ctx): ToolResult`. Danach in `di/AppModule.kt` in `allTools()` eintragen. Die Registry schickt die Definition automatisch per `session.update` an das Modell, prüft Rechte, holt bei Bedarf eine Bestätigung ein und gibt das Ergebnis als `function_call_output` zurück.

### Tests

```bash
./gradlew testDebugUnitTest
```

Abgedeckt sind die Tool-Registry (Specs, Fehler, Rechte, Bestätigungsablauf), das Argument-Parsing, die unscharfe Suche und die Event-Adapter beider Anbieter (Session-Konfiguration, Transkripte, Function Calls, Usage, Fehler).

## Entscheidungen

- **WebSocket statt WebRTC bei OpenAI.** Eine gemeinsame Audio-Pipeline für beide Anbieter, volle Kontrolle über Echo-Unterdrückung und Barge-in. WebRTC würde eine große native Bibliothek bedeuten und hätte bei xAI keinen Gegenpart.
- **Realtime API statt GPT-Live bei OpenAI.** OpenAI hat inzwischen zusätzlich „GPT-Live“ mit Delegation an ein Backend-Modell. Wisp nutzt bewusst die Realtime API, weil sie dasselbe Event-Modell wie xAI hat und Tools direkt aufrufen kann.
- **Eingabe-Transkription bei OpenAI:** `gpt-live-transcribe`. Lehnt der Server das ab, fällt Wisp automatisch auf `gpt-transcribe` und dann auf „ohne Modellangabe“ zurück.
- **Empfindlichkeit bei xAI:** Die xAI-API kennt nur `server_vad` ohne Stellschrauben. Wisp setzt deshalb auf dem Handy ein Noise-Gate davor. Bei OpenAI steuert der Regler direkt `threshold` bzw. `eagerness`.
- **Bestätigungen** laufen über ein eigenes Werkzeug `confirm_action`. So funktioniert „ja“ per Stimme bei beiden Anbietern gleich, und der Button in der App nutzt denselben Weg.
- **Shizuku per Reflection:** Shizuku 13 hat `newProcess` privat gemacht. Für ein paar `svc`- und `cmd`-Befehle wäre ein eigener AIDL-User-Service übertrieben.
- **Benachrichtigungsinhalte** liegen nur im Arbeitsspeicher und werden nie gespeichert.
- **Overlay schließen beendet das Gespräch**, genau wie bei Gemini. Für längere Gespräche nimmst du die App.
- **Hörprobe:** Bei xAI über `POST /v1/tts`, bei OpenAI über `/v1/audio/speech` mit `gpt-4o-mini-tts`.
- **Verbindungstest:** Bei xAI `GET /v1/tts/voices` (liefert auch gleich die aktuelle Stimmenliste), bei OpenAI `GET /v1/models/<modell>`.
