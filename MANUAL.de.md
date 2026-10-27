# Qonecta+ · Benutzerhandbuch

**Sprachen:** [Español](MANUAL.md) · [Português](MANUAL.pt.md) · [English](MANUAL.en.md) · [Italiano](MANUAL.it.md) · **Deutsch**

Handbuch für Fahrer zur Version **0.3** von Qonecta+, von **[CharlysEV](https://github.com/CharlysEV)**, basierend auf [HeadQLink](https://github.com/ryazrm/headqlink) von **ryazrm**
([CharlysEV/qonecta](https://github.com/CharlysEV/qonecta)).
Qonecta+ ist seit Version 0.2.52 der neue Name von HeadQLink (dieselbe App; sie wird über die alte installiert und behält die Einstellungen).
Die Namen von Tasten und Menüs stehen in „Anführungszeichen“, so wie die App sie auf Deutsch anzeigt.
Fragen, Fehler und Ideen: im [Qonecta+-Thread des LPMFF-Forums](https://www.lpmff.de/thread/3341-qonecta-app/).

> [!WARNING]
> Nimm alle Einstellungen **bei geparktem Fahrzeug** vor. Fass das Handy während der Fahrt nicht an, und schau dir auf
> dem Fahrzeugdisplay keine Videos, kein TV, keine Webseiten und keine Spiele an, solange das Fahrzeug nicht steht.

**Kurzfassung**

1. Installiere die APK aus den [GitHub Releases](https://github.com/CharlysEV/qonecta/releases).
2. Öffne Qonecta+ und folge dem Assistenten mit 3 Schritten, bis bei „Voraussetzungen prüfen“ oben „Alles bereit“ steht.
3. Im Fahrzeug: Schalte den Hotspot des Handys ein, öffne die Spiegelungsapp auf dem Fahrzeugdisplay und tippe auf
   „Verbinden“.
4. Sobald Android Auto erscheint, sperre das Handy und leg es weg.

## Inhalt

1. [Was ist Qonecta+ und was brauchst du](#1-was-ist-qonecta-und-was-brauchst-du)
2. [Installation](#2-installation)
3. [Erster Start](#3-erster-start)
4. [Im Fahrzeug](#4-im-fahrzeug)
5. [Nützliche Einstellungen](#5-nützliche-einstellungen)
6. [Hitze und Akku](#6-hitze-und-akku)
- [Häufige Fragen](#häufige-fragen)
7. [Fehlerbehebung](#7-fehlerbehebung)
8. [Log exportieren und Hilfe holen](#8-log-exportieren-und-hilfe-holen)
9. [Sicherheit, Datenschutz und rechtliche Hinweise](#9-sicherheit-datenschutz-und-rechtliche-hinweise)

---

## 1. Was ist Qonecta+ und was brauchst du

Qonecta+ bringt **Android Auto** auf das Display des **Leapmotor C10** und nutzt dafür die Spiegelungsverbindung, die das
Fahrzeug schon mitbringt (die seiner App QDLink/SSPLink). Du brauchst weder Adapter noch Kabel: Handy und Fahrzeug
sprechen per WLAN miteinander, und das Handy kann **gesperrt und mit ausgeschaltetem Bildschirm** bleiben. Root ist
nicht nötig.

Du kannst zwischen zwei Modi wählen:

| Modus | Was du im Fahrzeug siehst |
|---|---|
| „Auto“ (empfohlen) | Android Auto im Vollbild: Karten, Musik und Nachrichten. Belastet Handy und Verbindung am wenigsten. |
| „Auto erweitert“ | Dasselbe, dazu eine eigene Seitenleiste links mit mehr Infos zu Fahrzeug und Fahrt (Route, Fahren, Fahrten, Instrumente, Effizienz) und weiteren Funktionen (Fotos, Videos, Web, TV, Radio, Spiele). |

**Was du brauchst**

| | |
|---|---|
| Fahrzeug | Leapmotor C10 mit seiner Spiegelungsapp (Handy-Projektion) auf dem Display. Funktioniert auch in einem **T03** mit normalem Android Auto (von einem Nutzer bestätigt). |
| Handy | Android mit installiertem **Android Auto**. Empfohlen ist **Android 10 oder neuer**; um die Sprache der App zu wählen, brauchst du Android 13. |
| Getestet mit | Samsung Galaxy **S25 Ultra** mit **Android 16** und Android Auto 17.7. Andere Handys können funktionieren, sind aber nicht getestet. |
| Android Auto 17.4 oder neuer | Du musst einmalig den **Entwicklermodus** von Android Auto und die **Bedienungshilfe** von Qonecta+ aktivieren. Die App führt dich durch (Abschnitt 3). Bei älteren Versionen ist das nicht nötig. |
| Internet (optional) | Nur für Funktionen von „Auto erweitert“ wie Routen, Wetter oder Radio. |

> [!NOTE]
> **Stand der Tests.** Qonecta+ ist ein privates, experimentelles Projekt. Auf einer echten Fahrt mit einem C10, einem
> S25 Ultra und der Verbindung „Handy-Hotspot“ lief Android Auto in zwei Sitzungen von je etwa 16 Minuten. Dabei wurde
> das Handy zu heiß, deshalb bringt diese Version das Bildprofil „Fahrzeug“, das Ausschalten des Handybildschirms und
> die Anpassung an Hitze (Abschnitt 6). Die Wi-Fi-Direct-Verbindung mit der QDAuto-Engine ist im Fahrzeug noch nicht
> getestet.

---

## 2. Installation

Qonecta+ gibt es nicht bei Google Play: Es wird über eine APK-Datei installiert.

### Installieren

1. Öffne auf dem Handy [github.com/CharlysEV/qonecta/releases](https://github.com/CharlysEV/qonecta/releases).
2. Klappe bei der neuesten Version **Assets** auf und tippe auf die `.apk`-Datei (etwa `com.headqlink.app_0.2….apk`).
3. Öffne die heruntergeladene Datei (über die Benachrichtigung oder über Eigene Dateien › Downloads).
4. Android meldet, dass es aus dieser Quelle keine unbekannten Apps installieren darf. Tippe auf **Einstellungen**,
   erlaube die Installation **aus dieser Quelle** und geh zurück.
5. Tippe auf **Installieren**.

<!-- Screenshot: Android-Hinweis „Unbekannte Apps installieren“ -->

> [!TIP]
> **Warnung von Play Protect.** Da die App nicht aus Google Play stammt, kann Play Protect warnen, dass es sich um eine
> unbekannte App handelt, oder sie blockieren. Wenn du diesem Projekt vertraust, tippe auf **Weitere Details › Trotzdem
> installieren**.
>
> **Samsung:** Ist die **Automatische Sperre** (Auto Blocker; Einstellungen › Sicherheit und Datenschutz) aktiviert,
> lässt sie keine APK-Installation zu. Schalte sie zum Installieren aus und danach, wenn du willst, wieder ein.

### Aktualisieren

1. Lade die neue APK von derselben Releases-Seite herunter.
2. Installiere sie über die vorhandene: Deine Einstellungen bleiben erhalten.
3. Öffne Qonecta+ und sieh unter „Voraussetzungen prüfen“ nach: **Android schaltet die Bedienungshilfe beim
   Aktualisieren oft aus**, und du musst sie wieder einschalten. Seit 0.2.62 sagt dir Qonecta+ in diesem Fall mit einer
   Benachrichtigung Bescheid, die direkt zur Einstellung führt.

> [!IMPORTANT]
> Aktualisieren geht nur mit einer APK, die **mit demselben Schlüssel signiert** ist, also aus den Releases dieses
> Forks. Meldet Android, dass die App nicht installiert wurde oder mit einer anderen in Konflikt steht, hast du eine
> Version mit anderer Signatur (zum Beispiel das originale HeadQLink von ryazrm, das dieselbe Kennung nutzt, oder eine
> selbst kompilierte). Deinstalliere sie zuerst; ihre Einstellungen gehen dabei verloren.

### Deinstallieren

1. Wenn du sie nicht mehr nutzen willst: In den Android-Auto-Einstellungen kannst du den Entwicklermodus wieder beenden
   (Menü ⋮).
2. Android-Einstellungen › Apps › Qonecta+ › **Deinstallieren**.
3. Exportierte Logs bleiben unter Downloads/Qonecta+: Lösche sie von Hand, wenn du sie nicht mehr brauchst.

---

## 3. Erster Start

Wenn du Qonecta+ zum ersten Mal öffnest, erscheint ein Assistent mit 3 Schritten. Geh ihn bei geparktem Fahrzeug durch;
noch besser im Fahrzeug, mit geöffneter Spiegelungsapp auf seinem Display.

### Schritt 1 von 3: Willkommen

Bildschirm „Dein Handy auf dem Display des C10“ mit allem, was du brauchst, unter „Bevor du startest“. Tippe auf
„Loslegen“.

### Schritt 2 von 3: Modus und Verbindung

<!-- Screenshot: Schritt 2 des Assistenten, Modus und Verbindung -->

**„Was möchtest du im Fahrzeug sehen?“** Wähle „Auto erweitert“ oder „Auto“ (Tabelle in Abschnitt 1).

> [!NOTE]
> **Datenschutz bei „Auto erweitert“.** Seine Ansichten nutzen den Standort des Handys, wenn du es erlaubst, und offene
> Internetdienste: OpenStreetMap und OSRM für Route und Ladestationen, Open-Meteo für Wetter und Wind und
> radio-browser.info für das Radio. Für die Berechnung erhalten diese Dienste deine Position oder dein Ziel. Wenn du das
> nicht willst, wähle „Auto“ oder erteile die Berechtigung „Fotos, Videos, Musik und Standort“ nicht.

**„Verbindung zum Fahrzeug“**

| Option | So funktioniert es | Empfehlung |
|---|---|---|
| „Handy-Hotspot“ | Das Fahrzeug verbindet sich mit dem Hotspot deines Handys. | **Empfohlen mit „Auto“**: Diese Verbindung ist am C10 getestet, auch bei ausgeschaltetem Bildschirm. |
| „Wi-Fi Direct“ | Das Fahrzeug erstellt das Netz, und das Handy tritt bei. Der Hotspot des Handys muss aus sein. | Das ist die Verbindung des originalen HeadQLink. Mit der QDAuto-Engine ist sie im Fahrzeug noch nicht getestet. |
| „USB-Kabel“ | Das Handy hängt per Kabel am USB-Datenanschluss des Fahrzeugs, ohne WLAN. | **Empfohlen mit „Auto erweitert“**: Bild mit 60 fps, keine Funkaussetzer, und das Handy lädt. Funktioniert nur, wenn das Fahrzeug das Handy in den Zubehörmodus schaltet, wie es der C10 tut. Siehe [Verbindung per USB-Kabel](#verbindung-per-usb-kabel). |

> [!TIP]
> Die Markierung „Empfohlen“ wechselt mit dem Modus: „Handy-Hotspot“ bei „Auto“ und „USB-Kabel“ bei „Auto erweitert“.
> Wähle die gewünschte Verbindung, bevor du auf „Weiter“ tippst.

Tippe auf „Weiter“. Modus und Verbindung kannst du jederzeit mit der Taste „Ändern“ auf dem Hauptbildschirm wechseln.

### Schritt 3 von 3: „‚Auto erweitert‘ einrichten“ (oder „Android Auto einrichten“)

Hier steht die Liste **„Voraussetzungen prüfen“**: alles, was deine Konfiguration braucht. Es ist dieselbe Liste, die du
später im Menü ⚙ › „Voraussetzungen prüfen“ findest.

<!-- Screenshot: Bildschirm „Voraussetzungen prüfen“ -->

**So liest du sie**

- Oben steht „Alles bereit“ (grün) oder „N Punkte fehlen“: rot, wenn etwas Pflicht fehlt, und orange, wenn nur etwas
  Empfohlenes fehlt.
- Jede Zeile hat ein Symbol (✓ erledigt, ! fehlt, ✕ Fehler, ? unbekannt, … wird geprüft, i Tipp), ihre Wichtigkeit
  („Pflicht“, „Empfohlen“, „Optional“ oder „Tipp“) und eine **Taste, die dich direkt zur passenden Einstellung bringt**.
- Die Liste aktualisiert sich von selbst, wenn du zu Qonecta+ zurückkehrst.

**Zeile für Zeile.** Es erscheinen nur die Zeilen, die für deinen Modus und deine Verbindung wichtig sind.

| Zeile | Was sie bedeutet | Worauf du tippst |
|---|---|---|
| „Android Auto“ | Muss installiert und aktiviert sein. Zeigt die Version an. | „Installieren“ (Play Store) oder „App-Info“, wenn es deaktiviert ist. |
| „Qonecta+-Bedienungshilfe“ (Pflicht mit Android Auto 17.4 oder neuer; Optional beim manuellen Start) | Qonecta+ nutzt sie, um Android Auto unbemerkt zu starten und die Toucheingaben des Fahrzeugs zu empfangen. In den Android-Einstellungen heißt sie **„Qonecta+ Touch“**. | „Aktivieren“ und „Qonecta+ Touch“ einschalten. Siehe den Hinweis darunter. |
| „Ohne Bedienungshilfe?“ (Tipp, nur wenn die Bedienungshilfe fehlt) | Du kannst den Android-Auto-Server selbst starten und sie ausgeschaltet lassen. | „Manueller Start“ (siehe [Ohne Bedienungshilfe](#ohne-bedienungshilfe-android-auto-server-manuell-starten)). |
| „Entwicklermodus von Android Auto“ (Pflicht mit 17.4 oder neuer) | Android Auto akzeptiert ein „Fahrzeugdisplay“ im Handy nur über seinen Entwicklermodus. Er wird einmal aktiviert. | „AA öffnen“ und der Anleitung unten folgen. „Prüfen“ kontrolliert es (braucht die aktivierte Bedienungshilfe). |
| „Android-Auto-Server“ (Info, nur beim manuellen Start) | Was sich **ohne Verbindung zu ihm** sagen lässt (eine Testverbindung würde ihn blockieren): „Von Qonecta+ genutzt“, „Antwortet nicht“ (die Versuche mit dem Fahrzeug schlagen fehl) oder, wenn unbekannt, wie du ihn startest. Er verhindert nie das Verbinden: Antwortet er nicht, sagt Qonecta+ Bescheid und versucht es erneut. | „AA öffnen“ (⋮ › „Server für Infotainmentsystem beenden“, falls vorhanden, und ⋮ › „Server für Infotainmentsystem starten“) oder „Automatischer Modus“. |
| „Benachrichtigungen“ (Empfohlen) | Um den Verbindungsstatus und Warnungen zu sehen. | „Zulassen“ (oder „Öffnen“, wenn du sie blockiert hast). |
| „Keine Akkueinschränkungen“ (Empfohlen) | Damit Android Qonecta+ bei ausgeschaltetem Bildschirm nicht beendet und die automatische Verbindung nicht blockiert. | „Zulassen“ und die Abfrage von Android bestätigen. |
| „Samsung: nie im Standby“ (Tipp, nur Samsung) | Samsung beendet Apps im Hintergrund auf eigene Faust. Das lässt sich aus der App nicht prüfen. | „Öffnen“ und „Nicht eingeschränkt“ wählen. Außerdem: Einstellungen › Akku › Grenzen der Hintergrundnutzung › Apps, die nie im Standby sind › Qonecta+ hinzufügen. |
| „Energiemanager von …“ (Tipp, andere Marken) | Xiaomi, Honor, Oppo und andere haben ihre eigene Akku-Sparfunktion. | „Öffnen“ und Autostart bzw. Hintergrundaktivität erlauben. |
| „Handy-Hotspot“ (Pflicht mit „Handy-Hotspot“) | Der Hotspot muss eingeschaltet sein. Wenn alles passt, steht dort „Hotspot an (…)“. | „Öffnen“ und einschalten. |
| „5-GHz-Band“ (Tipp) | Die App kann das nicht auslesen. Mit 5 GHz läuft es besser, denn bei 2,4 GHz teilt sich der Funk das Band mit dem Bluetooth des Fahrzeugs. | „Öffnen“: 5 GHz wählen und das automatische Ausschalten des Hotspots deaktivieren. |
| „Wi-Fi-Geräte in der Nähe“ oder „Standort“ (Pflicht mit „Wi-Fi Direct“) | Berechtigung, dem Wi-Fi-Direct-Netz des Fahrzeugs beizutreten („Standort“ bei Android 12 oder älter). | „Zulassen“ (oder „Öffnen“, wenn du sie abgelehnt hast). |
| „Wi-Fi an“ (Pflicht mit „Wi-Fi Direct“) | Wi-Fi Direct braucht eingeschaltetes WLAN, auch wenn du mit keinem Netz verbunden bist. | „Öffnen“. |
| „Hotspot aus“ (Pflicht mit „Wi-Fi Direct“) | Wi-Fi Direct funktioniert nicht bei eingeschaltetem Hotspot. | „Öffnen“ und ausschalten. |
| „QDLink“ (Empfohlen) | Die offizielle App QDLink ist auf dem Handy installiert. Ist sie geöffnet, belegt sie Port 18463, und das Fahrzeug findet Qonecta+ nicht. | Vor dem Verbinden „App-Info“ › „Beenden erzwingen“. |
| „QDLink“ oder „Port 18463“ in Rot („Belegt“) | QDLink (oder eine andere App) ist gerade geöffnet. | „Beenden erzwingen“. |
| „Bluetooth (Geräte in der Nähe)“ (Pflicht, wenn du die automatische Verbindung einschaltest) | Um das Bluetooth des Fahrzeugs zu erkennen und von selbst zu verbinden. | „Zulassen“. |
| „Über anderen Apps einblenden“ (Optional) | Damit Android Auto auch aus dem Hintergrund geöffnet werden kann. | „Zulassen“. |
| „Fotos, Videos, Musik und Standort“ (Optional, „Auto erweitert“) | Für die Galerie, deine Musik und die Fahrtanzeigen im Fahrzeug. | „Zulassen“. |
| „Standort immer zulassen“ (Empfohlen, „Auto erweitert“) | Damit die Fahrzeugdaten bei gesperrtem Handy weiterlaufen (siehe [Was du siehst und wie du es bedienst](#was-du-siehst-und-wie-du-es-bedienst)). | „Öffnen“: erklärt den Grund und öffnet die Android-Seite; wähle „Immer zulassen“. Hat die App noch gar keinen Standortzugriff, fragt sie vorher danach. |

> [!IMPORTANT]
> **„Eingeschränkte Einstellung“ beim Aktivieren der Bedienungshilfe.** Ab Android 13 lässt Android Apps, die aus einer
> APK installiert wurden, die Bedienungshilfe nicht auf Anhieb aktivieren. Erscheint beim Einschalten von „Qonecta+
> Touch“ die Meldung „Eingeschränkte Einstellung“:
>
> 1. Tippe auf die zweite Taste der Zeile, „App-Info“ (oder öffne Einstellungen › Apps › Qonecta+).
> 2. Tippe auf das Menü ⋮ (oben rechts) › **„Eingeschränkte Einstellungen zulassen“** und bestätige. Diese Option
>    erscheint erst, nachdem du einmal versucht hast, die Bedienungshilfe einzuschalten.
> 3. Geh zurück, tippe auf „Aktivieren“ und schalte „Qonecta+ Touch“ ein.
>
> Steht in der Zeile „Aktiv, läuft aber nicht (Android hat sie gestoppt)“, schalte sie aus und wieder ein.
>
> Willst du dir das sparen? Siehe [Ohne Bedienungshilfe](#ohne-bedienungshilfe-android-auto-server-manuell-starten).

**So aktivierst du den Entwicklermodus von Android Auto** (dieselbe Anleitung, die die App zeigt):

1. Tippe auf „AA öffnen“. Fehlt die Taste, tippe zuerst auf „Prüfen“.
2. Scrolle in den Android-Auto-Einstellungen ganz nach unten.
3. Tippe **10-mal auf „Version“** und bestätige die Warnung.
4. Kehre zu Qonecta+ zurück: Die Prüfung läuft von selbst.

<!-- Screenshot: Android-Auto-Einstellungen mit „Version“ ganz unten -->

Bei der Verbindung „Handy-Hotspot“ erscheint unter der Liste außerdem „So verbindest du das Fahrzeug mit dem Hotspot“
(Abschnitt 4).

Tippe auf **„Fertig“**. Fehlt noch etwas Pflicht, sagt es dir der Hinweis „Es fehlt noch“: Du kannst „Zurück“ wählen
oder „Trotzdem abschließen“ und es später nachholen.

> [!NOTE]
> Nach dem Assistenten, und jedes Mal, wenn du das eingerichtete Qonecta+ öffnest, **beginnt die App von selbst zu
> verbinden** (wie ein Tippen auf „Verbinden“) und wartet bis zu 5 Minuten auf das Fahrzeug (oder so lange, wie unter
> „Auf das Fahrzeug warten“ eingestellt ist, falls das länger ist). Mit Android Auto 17.4 oder neuer siehst du kurz die
> Einblendung „Android Auto startet…“: Fass nichts an. Bist du nicht im Fahrzeug, tippe auf „Trennen“.

### Ohne Bedienungshilfe (Android-Auto-Server manuell starten)

Wenn du die Bedienungshilfe nicht aktivieren willst (oder kannst), wähle **„Bildeinstellungen“ › „Erweitert ▾“ ›
„Start des Android-Auto-Servers“ › „Manuell (ohne Bedienungshilfe)“**, oder tippe unter „Voraussetzungen prüfen“ in der
Zeile „Ohne Bedienungshilfe?“ auf „Manueller Start“. Mit „Auto“ und „Auto erweitert“ braucht Qonecta+ sie dann für
nichts mehr: Die Bedienungshilfe wird „Optional“ („Nur für den automatischen Modus“), und die Zeile „Android
Auto-Server“ erscheint.

> [!IMPORTANT]
> **Der Android-Auto-Server antwortet, solange er läuft, sofern die Verbindungen sauber geschlossen werden.** Qonecta+
> schließt seine immer so (Trennen, Ende der Fahrt, Wechsel des Bildprofils), deshalb **musst du ihn zwischen den Fahrten
> nicht neu starten**. Blockiert wird er durch eine **halb abgebrochene** Verbindung (etwas, das sich verbindet und
> kommentarlos wieder verschwindet, oder eine Sitzung, die schlagartig abreißt): Ab dann nimmt er zwar Verbindungen an,
> antwortet aber niemandem mehr, bis du ihn beendest und neu startest. Deshalb **prüft Qonecta+ ihn nicht vor dem
> Verbinden** (genau so eine Verbindung wäre die Prüfung): Das zeigt erst der erste echte Versuch mit dem Fahrzeug.

1. Starte den Server selbst: „AA öffnen“ › ⋮ (oben rechts) › **„Server für Infotainmentsystem starten“**. Siehst du
   diese Option nicht, aktiviere zuerst den Entwicklermodus (10-mal auf „Version“ tippen). Steht im Menü „Server für
   Infotainmentsystem beenden“, läuft er bereits: Dann musst du nichts tun. Nach einem Neustart des Handys (oder einem
   Update von Android Auto) kann er aus sein: Dann musst du ihn erneut starten.
2. Tippe auf „Verbinden“ (oder lass die Verbindung von selbst per Bluetooth oder Kabel starten). Sobald das Fahrzeug
   verbunden ist, startet Qonecta+ Android Auto und wartet, bis es **antwortet** (höchstens 6 s). Läuft der Server,
   funktioniert das auch **bei gesperrtem Handy**: ohne Entsperren und ohne irgendetwas zu automatisieren.
3. Antwortet er nicht (aus oder durch eine halb abgebrochene Verbindung blockiert), erscheint die Benachrichtigung
   **„Starte den Android-Auto-Server (neu)“**, und die Zeile „Auto“ (und das Widget) zeigt „Warte auf den Android
   Auto-Server“. Tippe darauf: Android Auto › ⋮ › „Server für Infotainmentsystem beenden“ (falls vorhanden) und ⋮ ›
   „Server für Infotainmentsystem starten“, dann zurück. Qonecta+ versucht es von selbst alle 5 s erneut, solange das
   Fahrzeug verbunden ist; sobald Android Auto antwortet, verschwindet die Benachrichtigung von selbst.
4. Alles andere läuft wie beim automatischen Start: Verschwindet das Fahrzeug, bleibt das Video noch 30 s aktiv, danach
   ist Android Auto pausiert; läuft „Auf das Fahrzeug warten“ ab (oder bei „Trennen“), schließt Qonecta+ Android Auto
   sauber. Nur den Server schaltet es nicht aus (ohne Bedienungshilfe geht das nicht): **Er bleibt an, und die nächste
   Fahrt nutzt ihn ohne Neustart wieder**, auch bei gesperrtem Handy, solange er läuft.

Was sich ändert und warum der automatische Start weiterhin **empfohlen** ist:

- Du musst ihn von Hand starten, wenn er aus ist (zum Beispiel nach einem Neustart des Handys), und ihn, falls er einmal
  blockiert, beenden und neu starten.
- **Er bleibt an**, bis du ihn beendest, und lauscht im ganzen Netz: In einem **öffentlichen WLAN** könnte jedes Gerät
  in diesem Netz versuchen, sich mit ihm zu verbinden (und ihn blockieren, wenn es sich verbindet und einfach wieder
  geht). Beende ihn, wenn du ihn nicht brauchst.
- Der Modus „App“ (eine bestimmte App im Fahrzeug) braucht die Bedienungshilfe weiterhin für die Toucheingaben.

> [!TIP]
> **Installierst du mit Obtainium, entfällt meist der Schritt mit den „eingeschränkten Einstellungen“.** Obtainium
> installiert über den sitzungsbasierten Installer von Android, und unter Android 13 und 14 lässt sich die
> Bedienungshilfe so meist auf Anhieb aktivieren. Ab Android 15 ist das nicht garantiert.

### Android-Auto-Versionen

Seit 17.4 startet Qonecta+ Android Auto über dessen „Server für Infotainmentsystem“ aus den Entwickleroptionen, und jede
neue Android-Auto-Version kann daran etwas ändern (die Texte im Menü ⋮ oder wie die Verbindung angenommen wird). Mit
diesen Versionen wurde getestet:

| Android Auto | Status |
|---|---|
| 17.7.x | Mit Qonecta+ **getestet** (Oktober 2026) |
| 17.8, 17.9 und neuer | **Noch nicht getestet.** Andere Projekte (Open Headunit) berichten von Problemen: Mit 17.8 bricht die Verbindung nach 1–2 s ab; mit 17.9 funktioniert der automatische Start des Servers nicht mehr |

„Voraussetzungen prüfen“ zeigt das in der Zeile „Android Auto 17.x“ (getestet / noch nicht getestet), und das Log hält es
beim Start (`androidAuto=…`) und bei jeder Sitzung fest (Spalte `aa_version` in `sessions.csv`).

**Empfehlung: Schalte das automatische Update von Android Auto aus.** Play Store › nach „Android Auto“ suchen › ⋮ (oben
rechts) › den Haken bei der automatischen Aktualisierung entfernen. So steht das Fahrzeugdisplay nicht von heute auf
morgen wegen einer neuen Version ohne Bild da; aktualisiere von Hand, sobald sie getestet ist.

Wenn eine neue Version nicht mehr funktioniert:

1. **„Qonecta+ findet die Servertaste nicht“**: Android Auto hat sein Menü geändert. Tippe auf „AA öffnen“ und starte
   den Server von Hand (⋮ › „Server für Infotainmentsystem starten“ oder wie die Option jetzt heißt), oder tippe auf
   „Manueller Start“, um nicht von der Bedienungshilfe abzuhängen (siehe [Ohne Bedienungshilfe](#ohne-bedienungshilfe-android-auto-server-manuell-starten)).
2. **„Android Auto verbindet sich und bricht nach ein paar Sekunden ab“** (zwei Abbrüche hintereinander innerhalb von
   10 s): Leere den Cache von Android Auto (Einstellungen › Apps › Android Auto › Speicher › **Cache leeren**; die Daten
   musst du nicht löschen) und beende und starte seinen Server (⋮). Qonecta+ versucht es von selbst erneut, höchstens
   alle 10 s.
3. Hilft das nicht, **exportiere das Log** (Abschnitt 8) und schick es: Es enthält die Android-Auto-Version und einmalig,
   was Qonecta+ in dessen Menü gesehen hat (die Menütexte, ohne persönliche Daten). Genau das wird gebraucht, um
   Qonecta+ anzupassen.

> [!WARNING]
> **Zu einer älteren Android-Auto-Version zurückzukehren heißt nicht „Updates deinstallieren“.** Auf vielen Handys ist
> die vorinstallierte Android-Auto-Version nur ein Grundgerüst (1.x), das für Qonecta+ nicht taugt: Du musst die APK der
> getesteten Version (17.7.x), die zu deinem Handy passt (Architektur und Android-Version), von Hand installieren und
> danach das automatische Update ausschalten, sonst spielt der Play Store sie wieder auf.

---

## 4. Im Fahrzeug

### Der Hauptbildschirm

<!-- captura: pantalla principal de Qonecta+ -->

- Oben der Modus und die Verbindung (zum Beispiel „Auto erweitert · Handy-Hotspot“) mit der Taste „Ändern“ und das
  Menü ⚙.
- Der Schalter „Verbinden, wenn das Bluetooth des Fahrzeugs erkannt wird“.
- Die Karte „Status“. Oben der Bereich „Live“: zwei große Zahlen, die **fps** (Bilder pro Sekunde) und die **Mbps**
  (Mbit/s) des Videos, das im Fahrzeug ankommt, jede mit einem Balken, der sich danach füllt, was dein Bildprofil
  hergibt (mit „Fahrzeug“ 30 fps). Der Punkt bei „Live“ blinkt, solange Video ankommt; ohne Video zeigen die Zahlen
  „—“. Darunter eine Zeile für jeden Teil der Verbindung:

| Zeile | Was sie dir sagt |
|---|---|
| „Fahrzeug“ | „Getrennt“, „Suche…“, „Gefunden, verbinde…“, „Verbunden“ (mit der Bildschirmgröße des Fahrzeugs) oder „Verbinde erneut… (Android Auto wartet)“. |
| „Netzwerk“ | „Hotspot an (…)“, „Hotspot aus: bitte einschalten“, „Wi-Fi Direct: suche das Fahrzeug“ oder „Wi-Fi Direct: in der Gruppe des Fahrzeugs (…)“. Ist der Hotspot aus, öffnet ein Tippen auf die Zeile seine Einstellungen. |
| „Bild“ | Das Video, das gerade an das Fahrzeug gesendet wird. |
| „Auto“ | Der Status von Android Auto und, falls es nicht startet, der Grund (zum Beispiel „Startet nicht: entsperre das Handy“). |
| „Voraussetzungen“ | „Alles bereit“ oder „N Punkte fehlen“. Antippen öffnet „Voraussetzungen prüfen“. |

- Ein Hilfetext und die große Taste „Verbinden“ / „Trennen“.

### Schritt für Schritt mit „Handy-Hotspot“ (empfohlen mit „Auto“)

1. **Schalte den Hotspot des Handys ein**, am besten mit 5 GHz und ohne automatisches Ausschalten.
2. **Nur beim ersten Mal:** Wähle im Fahrzeug unter Einstellungen › Wi-Fi den Hotspot deines Handys. Danach verbindet
   sich das Fahrzeug von selbst.
3. **Öffne die Spiegelungsapp** auf dem Fahrzeugdisplay.
4. **Öffne Qonecta+** bei entsperrtem Handy. Die App beginnt von selbst mit dem Verbinden; war sie schon offen und
   getrennt, tippe auf „Verbinden“. Mit Android Auto 17.4 oder neuer siehst du kurz „Android Auto startet…“.
5. Die Zeile „Fahrzeug“ wechselt zu „Gefunden, verbinde…“ und dann zu „Verbunden“. **Android Auto erscheint auf dem
   Fahrzeugdisplay.**
6. **Sperre das Handy** und leg es an einen kühlen Ort. Das Bild kommt auch bei ausgeschaltetem Handybildschirm weiter
   im Fahrzeug an.

> [!CAUTION]
> Bei eingeschaltetem Hotspot **könnte das Fahrzeug deine mobilen Daten nutzen.**

**Mit „Wi-Fi Direct“:** Lass den Hotspot aus und das WLAN an, öffne die Spiegelungsapp im Fahrzeug und danach
Qonecta+. Die Zeile „Netzwerk“ wechselt von „Wi-Fi Direct: suche das Fahrzeug“ zu „Wi-Fi Direct: in der Gruppe des
Fahrzeugs (…)“.

> [!IMPORTANT]
> Ist QDLink auf dem Handy installiert, **beende es vor dem Verbinden mit „Beenden erzwingen“**. Ist es geöffnet,
> belegt es den Port, den Qonecta+ braucht, und das Fahrzeug findet Qonecta+ nicht.

### Verbindung per USB-Kabel

Wie die Original-App des Fahrzeugs kann Qonecta+ das Bild **per Kabel** statt per WLAN übertragen: Das Fahrzeug
schaltet das Handy in den „Zubehörmodus“, und die gewohnte Sitzung läuft über das Kabel. **Getestet im C10
(06.10.2026): 5 Minuten mit 40 fps, ohne einen einzigen Abbruch**, und das Handy lädt dabei auch noch. Nach vielen
Fahrten ist das die empfohlene Verbindung für „Auto erweitert“.

1. Nimm ein **Datenkabel** (kein reines Ladekabel) und steck es in den **USB-Datenanschluss** des Fahrzeugs (den für
   Musik oder Android Auto/CarPlay, keinen reinen Ladeanschluss).
2. Öffne die Spiegelungsapp auf dem Fahrzeugdisplay.
3. Schaltet das Fahrzeug das Handy in den Zubehörmodus, öffnet Android Qonecta+ von selbst. Ist auch **QDLink**
   installiert, fragt Android eventuell, **welche App „QDriveLink“ öffnen soll**: Wähle **Qonecta+** und **„Immer“**
   (oder deinstalliere QDLink).
4. Die Zeile „Netzwerk“ zeigt „USB-Kabel: Neusoft QDriveLink 1.0“, und das Fahrzeug verbindet sich wie immer.
5. **Antwortet das Fahrzeug nicht** (das Bild kommt nicht innerhalb weniger Sekunden), **zieh das Kabel ab und steck es
   wieder ein**: Das Fahrzeug meldet sich nur in den ersten Sekunden nach dem Einstecken, und hatte Qonecta+ das Kabel
   da noch nicht (zum Beispiel, weil Android gefragt hat, mit welcher App es geöffnet werden soll), ist diese
   Gelegenheit verpasst.

- **Der Hotspot ist nicht nötig.** Steckt das Kabel, **hat das Kabel Vorrang**: Auch wenn eine andere Verbindung
  gewählt ist, pausiert Qonecta+ das WLAN und kehrt nach dem Abziehen des Kabels dorthin zurück. Um nur das Kabel zu
  nutzen, wähle mit „Ändern“ die Verbindung „USB-Kabel“.
- Das Kabel abzuziehen ist, als würde das Fahrzeug wegfahren (30 s lang bleibt das Video aktiv, dann wird Android Auto
  pausiert); steckst du es wieder ein, geht es sofort weiter.

**Wenn es nicht funktioniert**, exportiere das Log ([Abschnitt 8](#8-log-exportieren-und-hilfe-holen)) direkt nach dem
Einstecken des Kabels und schick es uns, mit der Uhrzeit. Wir suchen die Zeilen **`HQL/USB`**: was Android über das
Kabel meldet (`USB_STATE`), ob das Fahrzeug das Handy in den Zubehörmodus geschaltet hat und unter welchem Namen
(Hersteller, Modell und Version). Damit wissen wir, ob der C10 es unterstützt.

### Was du siehst und wie du es bedienst

- **„Auto“:** Android Auto im Vollbild.
- **„Auto erweitert“:** eine Seitenleiste links, auf der Fahrerseite, mit „Auto“, „Fahrzeug“ (Tabs Route, Fahren,
  Fahrten, Instrumente, Effizienz und Status), „Fotos“, „Videos“, „Web“, „TV“, „Radio“, „Spiele“ und „Einstellungen“.
  Ist Android Auto im Bild, klappt die Seitenleiste nach ein paar Sekunden von selbst ein (auch bei Fotos, TV,
  Spielen…); tippe auf den Bildschirmrand auf der Seite der Leiste, damit sie wiederkommt.
  Fotos, Videos, Web, TV und Spiele sind **nur für das stehende Fahrzeug** gedacht.
- **Geteilter Bildschirm („Auto erweitert“):** Bei „Fahrzeug“, „Reiseführer“, „Web“, „Videos“ und „TV“ legt die Taste
  „Bildschirm teilen“ in der Seitenleiste Android Auto nach links, neben die Leiste, und diese Ansicht nach rechts. Die Taste ⇄ darunter tauscht die Seiten
  (die Ansicht neben die Leiste, Android Auto nach rechts), ohne Android Auto zu unterbrechen, und merkt sich das. Mit
  „Fahrzeug“ hast du die Instrumente (Verbrauch, Akku, Reichweite, nächster Halt) neben Android Auto, und das auch
  während der Fahrt, ohne Warnhinweis. Android Auto bekommt etwas mehr als die Hälfte, gerade genug, damit sich Google
  Maps verschieben und durchsuchen lässt. Jede Seite reagiert auf ihre eigenen Berührungen. „Vollbild“ zeigt die
  Ansicht wieder allein. Die Teilung bleibt, solange du mit dem Fahrzeug verbunden bist. Beim ersten Mal pro Verbindung
  erscheint ein Hinweis, den du bestätigen musst: Es wird empfohlen, sie nur bei stehendem Fahrzeug zu nutzen, und die
  Nutzung erfolgt auf eigene Verantwortung. Beim T03 gibt es sie nicht: Sein Bildschirm ist zu schmal zum Teilen.
- **Tastatur im Fahrzeug** (Web, Zielsuche, Namen): oben Ziffern, unten „#+=“ mit allen Sonderzeichen (jedes Passwort
  lässt sich eingeben) und „äé“ mit Umlauten und Akzenten (ä ö ü ß, á é í ó ú, ç, ã…; nach einem Zeichen geht es von
  selbst zurück zu den Buchstaben). ⇧ schreibt den nächsten Buchstaben groß; zweimal hintereinander getippt bleibt die
  Großschreibung (⇪). ⌫ gedrückt halten löscht weiter. Über den Tasten siehst du, was du tippst, auch in Feldern von
  Webseiten; bei einem Passwort Punkte und kurz den letzten Buchstaben.
- **Bereich „Fahrzeug“** (geschätzt mit den Sensoren des Handys und offenen Diensten; mit dem Leapmotor-Konto auch mit
  echten Fahrzeugdaten: siehe [Echte Fahrzeugdaten](#echte-fahrzeugdaten-leapmotor-konto)):
  - „Route“: Ziel, Ankunft, voraussichtliche Energie und voraussichtlicher Verbrauch; das Höhenprofil, nach Steigung
    eingefärbt (grün die Gefällstrecken, auf denen Energie zurückgewonnen wird), der Wind pro Abschnitt, die
    Ladestationen und der voraussichtliche Akkustand; der Akkustand bei Ankunft in einem Ring (gib deinen Stand in % mit
    „−5“/„+5“ an), das Wetter am Ziel und die Ladestationen an der Route („Los“ öffnet die Navigation in Google Maps).
    - **Ziel suchen:** Die Suche läuft schon beim Tippen (sobald du kurz innehältst, ab 3 Buchstaben), mit der
      Adresssuche von Android (der von Google) und Photon (OpenStreetMap) gleichzeitig: Adressen mit Hausnummer und
      Orte („Tankstelle“, „Rewe Hauptstraße“), jeweils mit der Entfernung in km. Jede Taste leuchtet beim Drücken auf,
      und das Getippte siehst du auch über der Tastatur. Bei leerem Feld erscheinen deine **letzten Ziele**.
      „Sprechen“ sucht, was du sagst (die Spracherkennung des Handys; braucht die Mikrofonberechtigung).
    - **Woher die Prognose kommt:** Unter den Kacheln steht, wie viel es auf den restlichen km bergauf und bergab geht
      und was diese Steigungen kosten („Vor dir: bergauf 90 m, bergab 20 m: +0,5 kWh“). Mit dem Leapmotor-Konto ist
      die gestrichelte Linie über den Balken **der Durchschnitt deines Fahrzeugs** (laut seinem Verlauf: siehe [Echte
      Fahrzeugdaten](#echte-fahrzeugdaten-leapmotor-konto)), und die Prognose passt sich deinem echten Verbrauch an;
      bei Ankunft siehst du „erwartet … · real …“.
    - **Ladestationen filtern:** „Filter“ hat drei Tabs. Unter **„Ladesäulen“** die Mindestleistung (beliebig, 22, 50,
      100, 150, 200 oder 300 kW), die Spannung und die **Ladenetze**: oben die auf deiner Route, mit der Zahl ihrer
      Ladestationen, darunter die deines Landes von A bis Z (dazu die, die es fast überall in Europa gibt: Tesla,
      Ionity, Shell, Lidl); „Ladenetze anderer Länder zeigen“ zeigt den Rest. Ist keins ausgewählt, zählen alle.
      **„Ladeplan“** enthält, mit wie viel du ankommst, bis wie viel du lädst, Laden am Ziel, Essen, Sprachansage und
      Navi-App, und **„Bevorzugt und gesperrt“** die Ladestationen, die du markiert hast. Das wird gespeichert, und die
      Blitze im Profil folgen dem auch.
    - **Woher die Ladestationen kommen:** In Spanien aus dem **offiziellen Register** der Ladepunkte (Ministerio para
      la Transición Ecológica, veröffentlicht von der DGT auf nap.dgt.es): Leistung und **Spannung** jeder Station. Es
      wird einmal pro Woche aufs Handy geladen (etwa 3 MB). Was im Register fehlt (manche Betreiber haben nicht alle
      ihre Ladepunkte gemeldet), wird mit OpenStreetMap ergänzt, ohne die schon vorhandenen doppelt aufzunehmen;
      außerhalb Spaniens kommt alles aus OpenStreetMap, wo Leistung oder Ladenetz fehlen können. **In Deutschland**
      kommen die Schnelllader aus dem offiziellen Register der Bundesnetzagentur (Ladesäulenregister), mit Leistung und
      Steckern (ohne Spannung: Es wird die typische des jeweiligen Ladenetzes verwendet). Es ist etwa 55 MB groß, deshalb
      wird es nur per WLAN geladen, einmal im Monat und nur auf Handys, die schon eine Route durch Deutschland geplant
      haben; bis dahin gilt OpenStreetMap. Die App wählt das Register danach, wo die Route verläuft, nicht nach der
      Sprache des Handys. **„Ladestationen an der Route“** öffnet die Liste mit dem Kennzeichen jedes Ladenetzes, dem
      km, der Leistung, der Spannung („350 kW · 920 V“) und den Steckern.
    - **Ladestationen in deiner Nähe:** „In der Nähe“ (neben „Filter“) sucht Ladestationen im Umkreis von 5, 10, 25
      oder 50 km um deinen Standort, ohne Route, von der nächsten bis zur entferntesten: Du wählst das Ladenetz („die
      Wenea-Stationen in meiner Nähe“) und die Mindestleistung und siehst, wie weit sie entfernt sind, ihre Leistung,
      ihre Spannung, die Stecker und die Ladepunkte, mit „Los“.
    - **Mit Google Maps oder Waze navigieren:** unter „Filter“ › „Ladeplan“ › „Navigieren mit“ (wenn Waze installiert
      ist). Die Taste zum Navigieren und die „Los“-Tasten öffnen die gewählte App. Waze kann keine Zwischenstopps: „Mit
      Stopps navigieren“ führt dich zum nächsten.
    - **Ladeplan (kostenlos, wie ABRP):** Reicht der Akku nicht mit der gewünschten Reserve, sagt dir die Route **wo du
      halten und bis wie viel du laden solltest**, unter den Ladestationen des Filters: den besten Schnelllader auf dem
      letzten Abschnitt, den du erreichst, und nur so viel wie nötig (der C10 lädt über 50 % langsamer: besser zwei
      kurze Stopps als einer bis 100 %). Jeder Stopp zeigt den km, mit wie viel du ankommst, bis wie viel du laden
      sollst, und die Minuten; die Akkulinie steigt bei jedem Stopp an. „Mit Stopps navigieren“ öffnet Google Maps mit
      ihnen (bis zu 3). Unter „Filter“ › „Ladeplan“ wählst du, mit wie viel du ankommen willst (10–30 %) und bis wie
      viel du höchstens laden willst (70–100 %); kommst du mit etwas mehr als dem Maximum ans Ziel, lädt der Plan so
      viel, statt wenige km vor dem Ende noch einen Stopp einzulegen. Beim REEV gibt es keinen Plan: Der Generator
      liefert, was fehlt.
    - **Einen Stopp ändern:** Jeder Stopp des Plans hat „Ändern“: Du siehst die anderen Ladestationen dieses Abschnitts,
      die du erreichst, mit wie viel du ankommst und wie sich die Standzeit ändert („+5 Min. Standzeit“, „Stopps: 1 →
      2“). „Diese nehmen“ legt sie **für diese Fahrt** fest (📌), und der Plan wird ab ihr neu berechnet; ändert sich der
      Verbrauch und du kommst nicht mehr hin, wählt er eine andere und sagt es dir. „Meine Wahl entfernen“ geht zurück
      zum Stopp des Plans. Bei einem anderen Ziel wird sie vergessen; um eine Ladestation immer zu bevorzugen, markiere
      sie mit ★.
    - **800 V (C10 mit 81,9 kWh):** Der große Akku arbeitet mit 800 V und lädt an 400–500-V-Ladestationen zwar, aber
      nur mit halber Leistung. Der Plan hält an 800-V-Stationen (oder an solchen mit 150 kW oder mehr, die das fast
      immer sind, auch wenn das Register es nicht angibt) und lässt die 400–500-V-Stationen als Reserve: Die Liste
      markiert sie orange („470 V: dein 800-V-Auto lädt hier mit halber Leistung“), und der Plan sagt, wie viele es
      unterwegs gibt. Die Tesla-Supercharger in Spanien arbeiten mit 400–500 V.
    - **Laden live:** Mit dem Leapmotor-Konto erscheint beim Einstecken über der Route ein Block mit den kW, dem % und
      dem Ziel („54 % → 64 %“), der Uhrzeit, zu der das Laden fertig ist, und mit wie viel du am nächsten Stopp oder am
      Ziel ankommst; sparst du dir durch etwas mehr Laden den nächsten Stopp, sagt er es dir („Bis 85 % sparst du dir
      den Halt in Lleida (+7 min hier, −23 min dort)“). Die Kurve vergleicht, was geliefert wird (durchgezogene Linie),
      mit dem, was dein Fahrzeug an dieser Ladestation aufnehmen sollte (gestrichelt), und warnt, wenn es viel langsamer
      lädt als erwartet. Ist das Ziel erreicht: **„Du kannst weiterfahren“** mit einem Ton auf dem Handy und, wenn du im
      Fahrzeug bist, einer Sprachansage und einer grünen Karte. Das Handy überwacht weiter, **auch wenn das
      Fahrzeugdisplay ausgeht** (du gehst etwas trinken), auch das langsame Laden zu Hause (alle 5 Minuten eine
      Abfrage): eine feste Benachrichtigung mit dem %, der Uhrzeit und einem Balken bis zum Ziel (ohne Plan 80 % beim
      Schnellladen und 100 % beim langsamen Laden), die beim Ausstecken von selbst verschwindet. Ab Android 16 ist sie
      ein **Live-Update**: ganz oben, und je nach Handy mit dem % in der Statusleiste oder in der Now Bar von Samsung.
      Auf dem Handy zeigt **Fahrten** oben „Lädt gerade“: das %, die kW, wann das Ziel und 100 % erreicht sind, das
      Geladene, die Kosten und die Kurve. Jeder Ladevorgang wird auf dem Handy gespeichert (echte kW, % und Minuten), um
      nach und nach die echte Ladekurve des Fahrzeugs kennenzulernen.
    - **Der Plan wird während der Fahrt von selbst neu berechnet:** Mit dem Leapmotor-Konto vergleicht er, wie viel dein
      Akku wirklich verliert, mit dem, was für diese km erwartet war, und passt den Rest an („An den Verbrauch dieser
      Fahrt angepasst: +12 %“). Die gewählten Stopps bleiben, solange du sie mit Reserve erreichst; wird dein Akku knapp
      (oder bleibt dir mehr übrig), ändert sich der Plan und **du wirst gewarnt**: eine orangefarbene Karte auf dem
      Fahrzeugdisplay (auch über Android Auto) mit „Los“ (Google Maps mit den neuen Stopps) und „Route“, die Taste
      „Fahrzeug“ in Orange und **eine Sprachansage** („Ladeplan geändert. Du verbrauchst 12 % mehr als erwartet. Neuer
      Stopp: …“). Die Sprachansage schaltest du unter „Filter“ › „Ladeplan“ › „Sprachansage, wenn sich der Plan ändert“
      aus. Während du lädst, gibt es keine Warnung (das hast du selbst entschieden).
  - „Fahren“: das nächste Fahrmanöver groß, mit einem Balken, der sich bis zum Abbiegen leert, die Fahrspuren und das
    Manöver danach; die Geschwindigkeit mit dem Tempolimit-Schild, die Fahrtrichtung, die Höhe, die Steigung und die
    Sonne bis zum Sonnenuntergang.
  - „Fahrten“: die laufende Fahrt und die gespeicherten, mit Strecke, Verbrauch, Höhenunterschied und Kosten; die km der
    letzten 14 Tage und die Rekorde. **Tippe auf eine Fahrt**, und sie öffnet sich groß: die Strecke auf einer Karte
    (OpenStreetMap), Start, Ankunft und **die Stopps** (2 Minuten oder länger am selben Ort, mit Dauer, Uhrzeit und km)
    sowie ihre Zahlen. Die Stopps werden bei Fahrten ab Version 0.2.7 erfasst. **Der Geist:** Fährst du eine Strecke
    erneut (gleicher Start, gleicher Weg), zeigt „Aktuelle Fahrt“ live, wie weit du vor oder hinter deiner Bestzeit bis
    hierher bist („Geist: 1:20 hinter deiner Bestzeit“), und die schnellste Fahrt jeder wiederholten Strecke bekommt
    „Streckenrekord“.
  - „Instrumente“: Tacho mit Tempolimit und Höchstgeschwindigkeit, G-Kräfte mit der Spur der letzten Sekunden und den
    Spitzenwerten, Neigung und eine Note für die Sanftheit (0–100) nach dem Rucken beim Beschleunigen, Bremsen und
    Lenken.
  - „Effizienz“: die Leistung der letzten 2 Minuten (grün, was zurückgewonnen wird), der Verbrauch jetzt, auf der Fahrt
    und im Mittel, wofür die Energie verbraucht wird, die Kosten der Fahrt, das CO₂, das aus keinem Auspuff gekommen
    ist, und ein Tipp.
  - „Status“ (mit dem Leapmotor-Konto): Akku, Reichweite, Ladung, Reifendruck, Türen und Kilometerstand, echte Werte
    des Fahrzeugs mit dem Alter der Daten; die Reifendrücke auf **einem 3D-C10, den du mit dem Finger drehst** (offene
    Türen und Heckklappe werden offen dargestellt, in Orange). Ohne Konto steht dort, wie du es einrichtest.
  - **Bei gesperrtem Handy: „Standort immer zulassen“.** Route, Fahren, Fahrten, Instrumente und Effizienz nutzen das
    GPS des Handys. Hat Qonecta+ den Standort nur mit „Nur während der Nutzung der App zulassen“, gibt Android ihr das
    GPS nur, wenn Qonecta+ sichtbar ist oder wenn die Verbindung mit Qonecta+ im Vordergrund gestartet wurde. Startet
    sie ohne das (automatische Verbindung per Bluetooth, Widget, Start von Android Auto…), läuft beim Sperren des
    Handys das Fahrzeugdisplay weiter und die Bewegungssensoren auch, aber Geschwindigkeit, Route, Fahrt und Verbrauch
    bleiben stehen, bis du entsperrst. Qonecta+ holt das von selbst nach, sobald du die App bei entsperrtem Handy
    siehst; um davon nicht abzuhängen, empfiehlt „Voraussetzungen prüfen“ in „Auto erweitert“ **„Standort immer
    zulassen“**: „Öffnen“ › „Immer zulassen“ (oder Einstellungen › Apps › Qonecta+ › Berechtigungen › Standort). In
    „Auto“ ist das nicht nötig. Qonecta+ nutzt das GPS nur bei laufender Verbindung oder geöffneten
    „Fahrzeug“-Ansichten.
  - **Ohne GPS keine eingefrorenen Zahlen.** Kommen länger als 5 s keine Positionen, zeigt die Geschwindigkeit „—“, und
    die Anzeigen melden „GPS pausiert · Handy gesperrt (Standort ‚immer‘ zulassen)“ (oder „kein GPS“, zum Beispiel in
    einem Tunnel). Kommt das GPS zurück, wird der Abschnitt ohne Positionen als gerade Linie mit der
    Durchschnittsgeschwindigkeit zur Fahrt hinzugerechnet, statt so zu zählen, als hätte das Fahrzeug gestanden.
- **Touchscreen:** funktioniert wie in Android Auto, mit **Multitouch** mit bis zu 3 Fingern (zum Beispiel mit zwei
  Fingern in die Karte zoomen).
- **Lenkradtasten:** Wiedergabe/Pause, Weiter und Zurück funktionieren über das **Bluetooth des Fahrzeugs**, ohne
  weiteres Koppeln.
- **Ton:** Musik und Navigationsansagen kommen vom Handy über das **Bluetooth des Fahrzeugs**, das Handy muss also wie
  gewohnt damit verbunden sein. Anrufe laufen über die Freisprecheinrichtung des Fahrzeugs.

### Abbrüche und automatische Wiederverbindung

- Bricht die Verbindung zum Fahrzeug kurz ab, zeigt die Zeile „Fahrzeug“ „Verbinde erneut… (Android Auto wartet)“,
  und das Bild kommt von selbst zurück, meist in weniger als einer Sekunde, **ohne Android Auto neu zu starten** (mit
  der Protokoll-Engine QDAuto, die standardmäßig eingestellt ist).
- Ist das Fahrzeug **länger weg** (zum Beispiel, weil du es bei einem kurzen Halt ausschaltest), hört Qonecta+ nach 30
  Sekunden auf, Bild zu senden, und **pausiert Android Auto**, **hört aber weiter auf das Fahrzeug**, und zwar für die
  Dauer von „Auf das Fahrzeug warten“ (standardmäßig **5 Minuten**; einstellbar unter „Bildeinstellungen“ ›
  „Erweitert“). Die Benachrichtigung zeigt „Warte auf das Fahrzeug · Android Auto pausiert“. Kommt das Fahrzeug in
  dieser Zeit zurück, ist das Bild **sofort** da, ohne das Handy zu entsperren oder etwas zu drücken.
- Nach Ablauf von „Auf das Fahrzeug warten“ beendet Qonecta+ alles: Android Auto, seinen Server und die Verbindung. Um
  die App wieder zu nutzen, tippe auf „Verbinden“ oder überlass es der automatischen Verbindung per Bluetooth. Beim
  manuellen Start des Servers wird ebenfalls alles beendet außer dem Server, der für die nächste Fahrt an bleibt
  ([Ohne Bedienungshilfe](#ohne-bedienungshilfe-android-auto-server-manuell-starten)).
- Tippst du auf „Verbinden“ und taucht innerhalb von **5 Minuten** (oder der Dauer von „Auf das Fahrzeug warten“, falls
  länger) kein Fahrzeug auf, stoppt Qonecta+ von selbst. Solange sich das Fahrzeug weiter meldet, auch ohne dass eine
  Verbindung zustande kommt, beginnt die Wartezeit von vorn.

### Das Video passt sich der Verbindung an

Die Funkverbindung zwischen Handy und Fahrzeug schafft nicht immer die 5 Mbit/s, die der C10 verlangt: Lag das Handy
weit vom Fahrzeug entfernt, lief die Verbindung über 2,4 GHz oder gab es Störungen, kam das Bild ruckelnd und verzögert
an. Jetzt, mit den Profilen „Fahrzeug“, „Automatisch“, „Mittel“ und „Sehr niedrig“ (denen, die auf dem Handy neu
kodieren):

- Qonecta+ misst die Verbindung zehnmal pro Sekunde (noch zu sendende Daten und Round-Trip-Zeit), und wenn sie wirklich
  verstopft (eine halbe Sekunde am Stück mit aufgestauten Daten oder mit sprunghaft gestiegener Round-Trip-Zeit),
  **senkt Qonecta+ die Bitrate** (jeweils ×0,75, höchstens bis auf die Hälfte dessen, was das Fahrzeug verlangt: 2,5
  Mbit/s beim C10); bleibt die Verbindung trotzdem verstopft, geht es auf **24 fps** herunter. Ist die Verbindung 3
  Sekunden lang sauber, geht es schnell wieder hoch (erst die fps, dann +25 % Bitrate alle 3 s): vom Minimum bis zu
  dem, was das Fahrzeug verlangt, in etwa 12 s. Einzelne WLAN-Neuübertragungen zählen nicht (die sind normal).
- Was nicht durch die Verbindung passt, wird auf dem Handy verworfen, bevor sich Verzögerung aufbaut (höchstens
  ~150 ms in der Warteschlange).
- Hört das Fahrzeug kurz auf zu lesen, kommuniziert aber weiter (Heartbeats, Berührungen), hält die Sitzung bis zu
  **20 s** durch, bevor sie als verloren gilt (vorher 10 s); dabei wird altes Video verworfen und so bald wie möglich
  ein frisches Bild gesendet.

Im Log (Abschnitt 8) siehst du das als `enlace: congestión (outq 96 KB 300 ms, retrans +21) → bitrate 3.6 Mbit/s`,
`enlace: enlace limpio 5 s → bitrate 4.1 Mbit/s` und in der Zusammenfassung jeder Sitzung
`enlace: bitrate mín. 2.5 Mbit/s · congestiones 4`.

### Am Ende der Fahrt

- Schalte das Fahrzeug aus (Qonecta+ beendet sich von selbst, wenn „Auf das Fahrzeug warten“ abläuft, standardmäßig
  nach 5 Minuten) oder tippe auf „Trennen“, um die App sofort zu beenden. Ohne das Handy anzufassen: im Fahrzeug
  Einstellungen › Verbindung › „Trennen“ (fragt nach einem zweiten Tippen) oder die Kachel in den Schnelleinstellungen,
  die auch bei gesperrtem Handy funktioniert. Ziehst du nur das Kabel ab, wird die Fahrt trotzdem gespeichert; die App
  beendet sich, wenn „Auf das Fahrzeug warten“ abläuft.
- War das Handy beim Beenden gesperrt, siehst du die Benachrichtigung „Android Auto wartet, bis du das Handy
  entsperrst (dann schließt es sich von selbst)“. **Entsperre das Handy einmal**, damit es sich ganz schließt: Du
  siehst ein paar Sekunden lang „Android Auto wird beendet…“ (Abschnitt 9). Kommst du vor dem Entsperren zurück ins
  Fahrzeug und nutzt die automatische Verbindung per Bluetooth, ist Android Auto sofort wieder da, ohne „Android Auto
  wird beendet…“. Beim manuellen Start ist das nicht nötig: Android Auto wird gleich beendet, und sein Server bleibt an.
- „Android Auto wird beendet…“ und „Android Auto startet…“ dauern ein paar Sekunden. Bleiben sie hängen, verschwinden
  sie nach 15 s von selbst; konnte der Android-Auto-Server an bleiben, siehst du „Der Android-Auto-Server läuft noch ·
  Zum Ausschalten tippen“: Tippe bei entsperrtem Handy darauf.
- Schalte den Hotspot aus, wenn du ihn nicht brauchst.

### Reisetagebuch

Im Tab „Fahrten“ auf dem Handy › „Strecken“ öffnest du eine Fahrt und tippst auf **„Postkarte der Fahrt“**: ein
Bild mit Datum, km und Zeit, der Strecke mit den Stopps, Verbrauch, Kosten, Höhenmetern, bis zu drei Fotos, die du
unterwegs mit dem Handy gemacht hast, und den Orten, die dir der Reiseführer erzählt hat. Zuerst siehst du sie;
„Ohne Fotos“ entfernt sie, und „Teilen“ schickt sie, wohin du willst. Sie verlässt das Handy nur, wenn du sie
teilst. Nach einer Fahrt ab 50 km bietet eine dezente Benachrichtigung sie an.

### Monatsbericht

Dein Monat in Zahlen, im Tab „Fahrten“ auf dem Handy › „Bericht ansehen“. Mit ‹ und › wechselst du zwischen den
Monaten.

- **Oben:** „Dein Oktober mit dem C10“ mit km, Durchschnittsverbrauch (kWh/100 km), Energie und Ausgaben. Jede Zahl
  zeigt ihre Veränderung gegenüber dem Vormonat: grün bei einer Verbesserung, orange bei einer Verschlechterung.
- **Was du sparst** gegenüber einem Benziner, mit Verbrauch und Benzinpreis aus den Fahrteinstellungen.
- **Ladevorgänge:** wie viele, die kWh zu Hause und am Schnelllader, der schnellste und was sie gekostet haben
  (gespeichert von „Laden live“).
- **Mit geparktem Auto:** wie viel der Akku im Monat im Stand verloren hat.
- **Highlights:** die effizienteste Fahrt, die längste und der Tag mit den meisten km.
- **„Teilen“:** ein Bild nur mit den Zahlen, ohne Orte oder Routen.
- **Am 1.** (bis zum 7.) kommt eine Benachrichtigung mit dem gerade beendeten Monat.

Die Fahrten sind die des Fahrzeugs (Leapmotor-Konto), wenn sein Verlauf den ganzen Monat abdeckt, sonst die von der
App aufgezeichneten. Die Cloud speichert nur 35 Tage, deshalb wird jeder Monat zusammengefasst auf dem Handy
gespeichert. Der erste Monat erscheint ohne Vergleich.

### Reiseführer („Auto erweitert“)

Er erzählt dir, was sich in der Nähe der Straße lohnt: Burgen, Kathedralen, geschichtsträchtige Orte, Stauseen,
Gebirge… Die Infos stammen aus Wikipedia, in der Sprache der App.

- **Einschalten:** Er ist standardmäßig aus. Im Fahrzeug: „Einstellungen“ › „Tasten der Seitenleiste“ › „Reiseführer“
  (an). Ab dann ist „Reiseführer“ in der Seitenleiste.
- **Was du siehst:** eine Infokarte mit Foto, Name, Entfernung und Seite („2,8 km · rechts“) und einer
  Zusammenfassung; darunter, was weiter vorn kommt. „Erzählen“ liest sie vor, und „Hinfahren“ startet die Navigation
  dorthin. Tippst du auf einen Ort in der Liste, erscheint er in der Infokarte.
- **Stimme:** Ist „Stimme“ an (oben rechts), erzählt er nur von den bedeutendsten Orten, die weniger als 5 km vor dir
  liegen, von jedem nur einmal und höchstens alle 4 Minuten von einem. Er spricht nie über einen Anruf, die Ansagen
  der Navigation oder einen anderen Hinweis hinweg, und die Musik wird leiser, solange er spricht. Während er spricht,
  erscheint „Stopp“, das ihn sofort unterbricht. „Ohne Stimme“: nur die Karten (und was er gerade erzählt, bricht ab).
- **Quiz:** „? Quiz“ (oben) stellt Fragen mit drei Antworten zu den Orten in der Nähe, für die Mitfahrer: aus
  welchem Jahrhundert, was es ist (ein Ort, ein Denkmal, ein Stausee …), was am nächsten liegt und was die meisten
  Aufrufe in Wikipedia hat. Die richtige wird grün und, mit „Stimme“, laut gesagt; die Treffer werden gezählt.
  „Zurück zum Reiseführer“ schließt es.
- **Im Stand:** Nach einer Minute Stillstand (zum Beispiel beim Laden) zeigt er, was du zu Fuß in der Nähe hast, mit
  der Gehzeit in Minuten.
- **Geteilter Bildschirm:** Mit „Bildschirm teilen“ steht der Reiseführer in einer Spalte neben Android Auto, und das
  auch während der Fahrt, ohne Warnhinweis.
- Er funktioniert, solange der Reiseführer geöffnet ist, allein oder neben Android Auto. Er braucht die GPS-Position
  und sehr wenig mobile Daten. Texte und Fotos: Wikipedia und Wikimedia Commons (CC BY-SA).

### Karaoke („Auto erweitert“)

Der Liedtext dessen, was in Android Auto läuft (Spotify, YouTube Music…), synchron zum Lied. Er stammt von LRCLIB
(lrclib.net).

- **Einschalten:** Es ist standardmäßig aus. Im Fahrzeug: „Einstellungen“ › „Tasten der Seitenleiste“ › „Karaoke“
  (an).
- **Im Stand:** der ganze Text, die aktuelle Zeile hervorgehoben und automatisch weiterlaufend.
- **Während der Fahrt:** nur die aktuelle und die nächste Zeile, groß und ruhig, um nicht abzulenken.
- Oben Cover, Titel und Zurück / Pause / Weiter.
- Braucht den „Benachrichtigungszugriff“ (derselbe wie für „Google-Maps-Route lesen“, im Menü ⚙ auf dem Handy). Hat ein
  Lied keinen synchronisierten Text, erscheint der Text ohne Zeitangaben; gibt es gar keinen, wird das angezeigt.
- Mit „Bildschirm teilen“ steht es neben Android Auto; wie die übrigen Unterhaltungsfunktionen besser bei stehendem
  Fahrzeug.

### Sprachbegleiter (KI) („Auto erweitert“)

Sprich im Fahrzeug mit Claude (von Anthropic): Unterhaltung, Wissenswertes, Quizfragen oder Geschichten für die
Kinder auf langen Fahrten. Tippe auf das Mikrofon, sprich, und die kurze Antwort wird vorgelesen und angezeigt.

- **Einrichtung:** auf dem Handy Menü ⚙ › „Sprachbegleiter (KI)“: Füge deinen API-Schlüssel von Anthropic ein
  (console.anthropic.com › API Keys; er wird verschlüsselt auf dem Handy gespeichert) und, wenn du willst,
  „Schneller und günstiger“ (Claude Haiku 5.5 statt Claude Opus 5.5). Im Fahrzeug: „Einstellungen“ › „Tasten der
  Seitenleiste“ › „Begleiter“ (Ja).
- **Kosten:** Jede Frage wird deinem Anthropic-Konto berechnet, etwa 1–2 Cent mit Opus und viel weniger im schnellen
  Modus.
- **Was gesendet wird:** was du sagst, mit Uhrzeit, Akku und Reichweite des Fahrzeugs; dein Standort nie. Das Log der
  App speichert weder, was du sagst, noch die Antwort.
- Tippen während er spricht, bringt ihn zum Schweigen; „Neues Gespräch“ beginnt von vorn (alle 12 Fragen beginnt von
  selbst ein neues). Braucht Android 8 oder neuer und mobile Daten.

### Automatische Verbindung: „Verbinden, wenn das Bluetooth des Fahrzeugs erkannt wird“

Mit diesem Schalter wartet Qonecta+ auf das Fahrzeug, sobald sich das Handy mit dessen Bluetooth verbindet, ohne dass
du die App öffnest.

1. Schalte ihn auf dem Hauptbildschirm ein.
2. Gib im Dialog „Automatische Verbindung“ einen Text ein, der den **Bluetooth-Namen des Fahrzeugs** enthält
   (voreingestellt ist `Leapmotor_BT`; Groß- und Kleinschreibung spielen keine Rolle), und tippe auf „Speichern“. Hat
   das Bluetooth deines Fahrzeugs einen anderen Namen, musst du nichts ändern: Nach der ersten Sitzung mit dem
   Fahrzeug erkennt Qonecta+ es an seiner Adresse.
3. Erteile die Bluetooth-Berechtigung und heb die Akkueinschränkungen auf, wenn du danach gefragt wirst. Sonst lässt
   Android die App nicht im Hintergrund starten.

Danach:

- Verschwindet das Bluetooth, ohne dass eine Verbindung mit dem Fahrzeug zustande kam, stoppt Qonecta+. Verschwindet
  es nach einer Sitzung (du schaltest das Fahrzeug aus), wartet die App weiter auf das Fahrzeug, bis „Auf das Fahrzeug
  warten“ abläuft.
- War Android Auto noch pausiert (du kommst zurück, bevor „Auf das Fahrzeug warten“ abläuft, oder es blieb bei
  gesperrtem Handy geparkt), ist das Bild sofort da, **ohne das Handy zu entsperren**.
- Lässt Android die App nicht im Hintergrund starten, siehst du die Benachrichtigung „Fahrzeug erkannt · Tippen, um
  Qonecta+ zu verbinden“. Tippe darauf.
- Muss Android Auto gestartet werden und ist das Handy gesperrt, siehst du die Benachrichtigung **„Entsperre das
  Handy, um Android Auto zu starten“**. Entsperre es (bei stehendem Fahrzeug): Qonecta+ startet Android Auto selbst,
  ohne die App zu öffnen (du siehst kurz „Android Auto startet…“).

### Widget und Schalter in den Schnelleinstellungen

Zum Verbinden, ohne die App zu öffnen:

- **Widget „Qonecta+“** (Startbildschirm): Menü ⚙ › „Widget zum Startbildschirm hinzufügen“, oder tippe lange auf eine
  freie Stelle des Startbildschirms › Widgets › Qonecta+. Es belegt 4x2 und lässt sich bis auf 2x2 verkleinern.
  - Die **große Taste** macht dasselbe wie „Verbinden“ / „Trennen“ in der App: Fehlt etwas Pflichtiges, öffnet sie
    „Voraussetzungen prüfen“. Ihre Farbe zeigt den Status: grau aus, orange beim Suchen oder Warten auf das Fahrzeug,
    grün mit Bild im Fahrzeug (mit fps und Mbit/s, alle 5 s aktualisiert) und rot bei einem Problem (zum Beispiel,
    wenn QDLink offen ist).
  - Unten die **Verbindung**: „Hotspot“, „Wi-Fi Direct“ oder „USB-Kabel“. Tippe auf eine andere, um sie zu wechseln;
    läuft Qonecta+, wird sofort gewechselt, ohne Android Auto neu zu starten. Steckt das Kabel des Fahrzeugs und wird
    es genutzt, hat es weiter Vorrang: Die gewählte Verbindung ist die, zu der Qonecta+ beim Abziehen zurückkehrt.
  - Oben der **Modus** („Auto“ / „Erweitert“). Läuft Qonecta+, verbindet ein Wechsel Video und Android Auto neu, wie
    beim Speichern eines anderen Bildprofils.
  - Bei 2x2 bleiben die Taste, der Status und das Symbol der Verbindung: Tippe darauf, um zur nächsten zu wechseln.
  - „HEADQLINK“ öffnet die App, ohne zu verbinden. Das Widget wird nur aktualisiert, wenn sich etwas ändert: Es
    verbraucht im Hintergrund keinen Akku.
- **Schalter „Qonecta+“ in den Schnelleinstellungen** (Android 8+): Mit Android 13+ über Menü ⚙ › „Schalter zu den
  Schnelleinstellungen hinzufügen“; sonst öffne die Schnelleinstellungen, tippe auf den Stift (Bearbeiten) und zieh
  ihn dorthin. Antippen verbindet oder trennt (zum Verbinden bei gesperrtem Handy wirst du zuerst ums Entsperren
  gebeten); langes Drücken öffnet die App. Darunter steht der Status („Suche das Fahrzeug…“, „30 fps · 4,8 Mbit/s“…).

---

## 5. Nützliche Einstellungen

Alles findest du im Menü ⚙ auf dem Hauptbildschirm, einem Fenster, das von unten hochfährt. Oben steht „Virtuelles
Auto“, unten „Diagnose“. Dazwischen liegen Gruppen, die sich beim Antippen aufklappen (jede zeigt, wie viele Optionen sie
enthält):

- **Auto:** „Voraussetzungen prüfen“, „Bildeinstellungen“ und „Fahrzeugdaten (Leapmotor-Konto)“.
- **Inhalte des erweiterten Modus** (nur in „Auto erweitert“): „TV-Liste“, „Radioliste“ und „Web-Lesezeichen“.
- **Spiele** (nur in „Auto erweitert“): „Spieleordner“.
- **App:** „Nach neuer Version suchen“, „Sprache“, „Widget zum Startbildschirm hinzufügen“ und „Schalter zu den
  Schnelleinstellungen hinzufügen“ (Android 13+).

### „Bildeinstellungen“

<!-- captura: Ajustes de imagen -->

**„Bildprofil“.** Die App markiert das Profil, das am besten zu deinem Handy passt, mit „Empfohlen“. Auf
leistungsstarken Handys ist das „Fahrzeug“.

| Profil | Was es macht |
|---|---|
| „Automatisch (…)“ | Folgt dem empfohlenen Profil für dieses Handy. **Die beste Wahl, wenn du nicht weißt, welches du nehmen sollst.** |
| „Fahrzeug“ (empfohlen) | Was das Fahrzeug verlangt: 30 fps (60 mit „Bildrate“) und etwa 5 Mbit/s. Volle Auflösung, wenn das Handy bei den letzten Fahrten kühl blieb, und 720p, wenn es warm wurde; während der Fahrt geht es nur auf 720p herunter, wenn es warm wird (verbindet neu: 2-4 s schwarz). In der Seitenleiste erscheint ein Hinweis mit Auflösung, fps und Handytemperatur. Das kühlste bei voller Auflösung. |
| „Maximum (Kabel)“ | Fürs Kabel: 60 fps in voller Auflösung und das schärfste Bild (H.264 High, Encoder auf Maximum). Per WLAN 30 fps. Erwärmt das Handy am stärksten. Bleibt das Fahrzeugdisplay schwarz, zurück zu „Fahrzeug“. |
| „Sehr hoch“ | Per Kabel 60 fps in voller Auflösung mit der geringsten Verzögerung (Encoder auf Maximum). Per WLAN 30 fps. Erwärmt das Handy deutlich. |
| „Hoch“ | Per Kabel 60 fps in voller Auflösung, ohne das Handy zu fordern (etwas mehr Verzögerung als „Sehr hoch“). Per WLAN 30 fps. |
| „Mittel“ | 720p mit konstant 45 fps, ohne das Handy zu fordern. |
| „Basis“ | 720p mit 30 fps und wenig Bitrate: leichter als „Fahrzeug“. |
| „Sehr niedrig“ | 720p mit 20 fps und sehr wenig Bitrate: das Minimum für Akku und Verbindung. |

Wenn du verbunden bist und ein anderes Profil speicherst, verbinden sich Fahrzeug und Android Auto in ein paar Sekunden
von selbst neu.

> [!TIP]
> Wenn du in einer früheren Version „Sehr hoch“ von Hand gewählt hast, bleibt es dabei. Stell es auf „Automatisch“ oder
> „Fahrzeug“ um: Das verhindert, dass das Handy heiß wird (Abschnitt 6).

**„Bildrate“.** Gilt nur für die Profile „Fahrzeug“ und „Automatisch“:

- „30 fps · weniger Hitze (60 in den Emulatoren)“ (empfohlen): das, was das Fahrzeug anfordert (30 fps und ca.
  5 Mbit/s beim C10). So wird das Handy am wenigsten warm.
- „60 fps · maximal flüssig“: 60 fps und 8 bis 12 Mbit/s, wie beim ursprünglichen HeadQLink (der C10 nimmt das an). Das
  Bild läuft flüssiger, aber das Handy wird wärmer: Erreicht es den Wärmestatus „mäßig“, geht Qonecta+ von selbst auf
  30 fps zurück (Abschnitt 6). Wenn du verbunden bist, verbinden sich Fahrzeug und Android Auto beim Speichern von selbst
  neu; sonst gilt es ab der nächsten Sitzung.

**Weitere Optionen in „Bildeinstellungen“:**

- „Seitenleiste nach ein paar Sekunden ausblenden (Auto erweitert)“: standardmäßig an.
- „Handybildschirm anlassen (mehr Hitze und Akkuverbrauch; sonst geht er wie gewohnt aus und die Übertragung läuft
  weiter)“: standardmäßig aus. **Lass sie aus**, außer du brauchst sie.
- „Hitzeschutz“: was Qonecta+ tut, wenn Android meldet, dass das Handy warm wird (Abschnitt 6).
  - „Normal (empfohlen)“: geht bei „mäßig“ auf 30 fps (nur wenn die Sitzung mit 60 läuft), bei „schwer“ auf 24 fps
    und bei „kritisch“ auf 20 fps, jeweils mit weniger Bitrate.
  - „Sanft“: senkt nur die Bitrate; die fps fallen nicht unter 30, außer bei „kritisch“ (20 fps). Für alle, denen ein
    flüssiges Bild wichtiger ist, auch wenn das Handy dabei wärmer wird.
  - „Aus“: ändert nichts, vermerkt es nur im Log.

  Gilt sofort, ohne neu zu verbinden.
- „Erweitert ▾“ › „Protokoll-Engine“:
  - „QDAuto (empfohlen)“: die Standard-Engine, mit Wiederverbindung ohne Neustart von Android Auto und Anpassung an
    Hitze.
  - „Älterer Motor (übernommen)“: die Engine des ursprünglichen HeadQLink, als **Plan B**, falls QDAuto dir Probleme
    macht.

  Die Änderung gilt ab der nächsten Verbindung: Tippe auf „Trennen“ und dann auf „Verbinden“.
- „Erweitert ▾“ › „Auf das Fahrzeug warten“: „1 min“, „5 min (empfohlen)“ oder „15 min“. So lange lauscht Qonecta+
  weiter auf das Fahrzeug, mit Android Auto in Pause, nachdem die Verbindung verloren gegangen ist (Abschnitt 4). Mehr
  Zeit = nach längeren Stopps ist das Bild sofort wieder da; weniger = der Android-Auto-Server geht früher aus. Gilt
  schon für den nächsten Stopp.
- „Erweitert ▾“ › „Start des Android-Auto-Servers“: „Automatisch (Bedienungshilfe) · Empfohlen“ oder „Manuell (ohne
  Bedienungshilfe)“ (Abschnitt 3, [Ohne Bedienungshilfe](#ohne-bedienungshilfe-android-auto-server-manuell-starten)).
  Gilt sofort.
- Der Rest von „Erweitert“ (fps, kbps, Breite, Höhe, H.264-Profil, Latenzoptimierungen, Drosselung) ist für Tests. Lass
  ihn leer oder so, wie er ist.

### Einstellungen im Fahrzeug („Auto erweitert“)

Mit der Taste „Einstellungen“ in der Seitenleiste auf dem Fahrzeugdisplay kannst du das Bildprofil und die „Bildrate“
ändern („Übernehmen · verbindet kurz neu“), das automatische Ausblenden der Seitenleiste und die Latenzoptimierungen
einstellen, den „Strompreis“ festlegen (€/kWh, für die Kosten der Fahrten; standardmäßig 0,20) und die genutzte
Verbindung und Engine sehen. Nutze das nur bei stehendem Fahrzeug.

„Kraftstoff“ (im Fahrzeug unter Einstellungen › Fahrt, auf dem Handy in den Einstellungen): „Benzinpreis“ und
„Dieselpreis“ (€/l; standardmäßig 1,60 und 1,50), „Vergleichen mit“ (Benziner oder Diesel) und der „Verbrauch des
Vergleichsautos“ (standardmäßig 6,5 und 5,5 l/100 km). Daraus ergeben sich die Ersparnis gegenüber einem Verbrenner
(Effizienz, Monatsbericht) und das eingesparte CO₂; beim REEV wird das Benzin des Generators immer zum Benzinpreis
gerechnet.

„Farbe der Seitenleiste“: „Automatisch“ (nachts dunkel und tagsüber hell, wie das Fahrzeugdisplay) oder eine von 16
festen Farben, die tagsüber und nachts gleich bleibt. Gilt sofort, und der Text der Seitenleiste wird hell oder dunkel,
damit er lesbar bleibt.

Darunter ergeben „Farbton“ und „Hell / Dunkel“ jede beliebige andere Farbe, und „Transparenz“ lässt sie mit dem Schwarz
des Displays verschmelzen. „Seitenleiste rechts“ setzt die Leiste auf die andere Seite, mit Android Auto direkt am
linken Rand. „Geteilter Bildschirm: Android Auto rechts“ macht dasselbe wie die Taste ⇄ beim geteilten Bildschirm. „Tasten der Seitenleiste“ legt fest, welche zu sehen sind (Ja / Nein) und in welcher Reihenfolge (Pfeile);
„Auto“ und „Einstellungen“ sind fest. Ist die Leiste auf Symbole verkleinert, erscheinen unten das Wetter (ein Emoji)
mit der Außentemperatur und der Handy-Akku (wenn Daten da sind); die Uhrzeit nicht, weil LeapOS und Android Auto sie
schon anzeigen. Und auf dem Hauptbildschirm des Handys erscheint bei einer neuen Version ein Hinweis mit den Neuerungen
und „Herunterladen und installieren“ (die App lädt die APK herunter und öffnet den Android-Installer; beim ersten Mal
musst du Qonecta+ erlauben, unbekannte Apps zu installieren); beim ersten Start einer frisch installierten Version
werden ihre Neuerungen angezeigt. Im Menü ⚙ prüft „Nach neuer Version suchen“ das sofort.

„Blitzer-Sprachwarnungen“ (standardmäßig an, außer in Deutschland und der Schweiz, wo sie aus bleiben, bis du sie selbst einschaltest): Wenn du dich einem Blitzer näherst, „Blitzer in 700 Metern, Limit 80“,
einmal pro Blitzer; und wenn du 400 Meter davor oder näher schneller fährst als erlaubt, „Du fährst 95, Limit 80“. Die
Blitzer stammen aus OpenStreetMap, es kann also einer fehlen.

> [!CAUTION]
> In **Deutschland** ist es dem Fahrer verboten, während der Fahrt Blitzerwarner zu nutzen, auch als App auf dem
> Handy (§ 23 Abs. 1c StVO); in der **Schweiz** ebenso. Deshalb schaltet Qonecta+ die „Blitzer-Sprachwarnungen“ dort
> von selbst aus (es erkennt das Land am Mobilfunknetz). Schaltest du sie selbst ein, liegt die Verantwortung bei dir.

### „Sprache“

„Sprache“ (Android 13 oder höher): „Systemsprache“, „Español“, „English“, „Português (Portugal)“, „Português
(Brasil)“, „Italiano“, „Français“, „Deutsch“ oder „Nederlands“. Die Oberfläche im Fahrzeug wechselt bei der nächsten
Verbindung. Unter Android 12 oder älter nutzt die App die Systemsprache.

### „TV-Liste“ und „Radioliste“ („Auto erweitert“)

Füge die URL einer M3U-Liste ein oder tippe auf „Datei wählen“. Wenn du keine Radioliste angibst, erscheinen im
Fahrzeug die beliebtesten Sender.

### „Meine Musik“ (Radio, „Auto erweitert“)

Im Fahrzeug zeigt Radio › „♪ Meine Musik“ die Titel auf dem Handy (MP3, FLAC, OGG, M4A … die in der Mediathek von
Android: Ordner „Music“, Downloads oder ein USB-Stick), nach Interpret und Album. Tippst du einen an, laufen die
anderen danach; „Zufällig“ mischt sie. Es spielt wie das Radio: läuft beim Bildschirmwechsel weiter, mit dem
Mini-Player im Panel und den Lenkradtasten. Dafür braucht es die Berechtigung „Fotos, Videos, Musik und Standort“
(ab Android 13).

### „Web-Lesezeichen“ („Auto erweitert“)

Die Verknüpfungen auf dem Bildschirm „Web“ im Fahrzeug (standardmäßig YouTube, Google, Wikipedia und Twitch). Füge
eigene mit Name und Adresse hinzu, entferne sie oder ändere ihre Reihenfolge mit den Pfeilen; „Standard“ stellt den
Ausgangszustand wieder her.

### „Aquarium“ (Spiele › Für die Wartezeit)

Für die Kinder, bei stehendem Fahrzeug: bunte Fische, die schwimmen, Blasen, Algen und Lichtstrahlen. **Tippst du ins
Wasser**, fällt Futter, und die Fische holen es sich; tippst du einen Fisch an, erschrickt er und schwimmt davon.
„Noch ein Fisch“ fügt einen hinzu (bis zu 14).

### „Spieleordner“: NES, Mega Drive, Super Nintendo und PlayStation („Auto erweitert“)

Menü ⚙ › Spiele. Wähle den Ordner auf dem Handy, in dem deine Spiele liegen: ein einziger Ordner für alle Konsolen, und
.zip-Dateien funktionieren direkt (die App enthält keine Spiele). Im Fahrzeug öffnen Spiele › NES, Mega Drive oder SNES
diesen Ordner mit seinen Unterordnern, jeweils mit den passenden Spielen (.nes; .md, .gen, .bin oder .smd; .sfc oder
.smc; und .zip-Dateien, die sie enthalten). Tippe auf ein Spiel, um zu spielen, mit Touch-Tasten oder mit einem
Bluetooth-Controller, der mit dem Handy verbunden ist. Beim Mega Drive sind X, A und B des Controllers A, B und C; LB, Y
und RB sind X, Y und Z (Spiele mit sechs Tasten), und Select ist Mode. Beim Super Nintendo zählt die Position, wie auf
seinem Controller: A, B, X und Y des Controllers sind B, A, Y und X, und LB und RB sind L und R. Beim Start öffnet sich
auf dem Handy „Gamepad mit dem Auto verbunden“: Lass es im Vordergrund, damit der ganze Controller beim Spiel ankommt.
Nur bei stehendem Fahrzeug. Spiele mit Speicherstand speichern von selbst. Die Cores stammen von RetroArch: Nestopia
(NES), ClownMDEmu (Mega Drive), bsnes (Super Nintendo, mit den Chips der Module: Super FX, SA-1, DSP …) und PCSX
ReARMed (PlayStation).

PlayStation (Spiele › PS1): die Discs als .chd (am besten: eine einzige, komprimierte Datei), .cue mit den .bin
daneben, .pbp, .iso oder eine .m3u-Liste für Spiele mit mehreren Discs; auch in einer .zip. Beim ersten Mal wird die
Disc aufs Handy kopiert (der Fortschritt wird angezeigt); danach startet sie sofort. Die drei zuletzt gespielten Discs
bleiben gespeichert. Das BIOS von Sony brauchst du nicht: Der Core bringt sein eigenes mit. Wenn du dein eigenes hast
(scph1001.bin, scph5501.bin …), leg es in den Spieleordner (oder neben das Spiel), dann wird es verwendet. Manche Spiele
brauchen es: Ohne bleibt Gran Turismo beim Start des Rennens schwarz. Die Tasten zählen nach Position, wie auf ihrem
Controller: A, B, X und Y des Controllers sind ✕, ○, □ und △; LB und RB sind L1 und R1, und die Trigger L2 und R2. Der
Spielstand wird auf der Memory Card gespeichert, wie auf der Konsole.

### „Diagnose“

- „Log exportieren“: Abschnitt 8.
- „Sitzungen“: die letzten Sitzungen mit dem Fahrzeug, eine pro Zeile: wann, wie lange, über welche Verbindung, die
  fps, die Abbrüche und wie sie endete. So siehst du, was schiefging, ohne das Log zu schicken.
- „Test ohne Android Auto (Testbild)“: zeigt im Fahrzeug ein Testbild statt Android Auto. So erkennst du, ob die
  Verbindung oder Android Auto das Problem ist. **Schalte ihn danach wieder aus.**
- „Testoptionen (QDAuto)“: zum Testen der Engine. Normalerweise lässt du sie, wie sie sind. Dazu gehört „Video ohne
  Fahrzeug aktiv halten, in Sekunden (5-600; leer = 30)“.
- „Vorschau des erweiterten Modus“: die Fahrzeugansicht auf dem Handy (im Querformat), mit einer Demo-Fahrt; du
  bedienst sie wie im Fahrzeug. Es wird nichts gespeichert, und bei verbundenem Fahrzeug ist sie nicht verfügbar.

### Echte Fahrzeugdaten (Leapmotor-Konto)

**Was es ist.** Optional. Mit deinem Leapmotor-Konto liest Qonecta+ aus der Leapmotor-Cloud den Zustand deines
Fahrzeugs (Akku und Reichweite, Laden, Reifendruck, Türen, Kofferraum und Verriegelung, Temperaturen und
Kilometerstand) und nutzt ihn in „Auto erweitert“:

- **„Status“** (der sechste Tab unter „Fahrzeug“): der Akku als Ring mit der Reichweite und den verbleibenden kWh; das
  Laden (Wechselstrom oder Schnellladen, mit Leistung und Restzeit) oder, wenn nicht eingesteckt, die abgegebene oder
  aufgenommene Leistung; die Akkutemperatur (mit „Kalter Akku: langsameres Schnellladen und weniger Rekuperation“ unter
  10 °C); die vier Reifendrücke auf **einem C10 in 3D**, den du mit dem Finger drehst; ein Reifen mit zu wenig Druck ist
  orange markiert (unter 2,1 bar, 0,3 bar oder mehr unter den anderen oder bei einer Warnung des Fahrzeugs
  selbst); offene Türen und Kofferraum (in der Zeichnung geöffnet), die Verriegelung, den Kilometerstand und wie alt
  die Daten sind. Die Fenster liefert die Cloud nicht. Das 3D-Modell wird nur gezeichnet, während du es bewegst (das
  Handy wird dabei nicht warm); ohne WebGL erscheint das Fahrzeug von oben.
- **C10 REEV (mit Range Extender):** Qonecta+ erkennt ihn von selbst (das Fahrzeug meldet seinen Tank) und stellt den
  Akku auf 28,4 kWh um. In „Status“ der **Tank** (%, genaue Liter, Reichweite mit Benzin und Gesamtreichweite); in
  „Fahrten“ die **Liter** jeder Fahrt und die L/100 km (bei stehendem Generator „0 L Benzin: rein elektrisch“) sowie die
  Kosten mit Benzin; in „Route“ fällt der Akku nicht unter 20 % (dort springt der Generator an), und es wird angezeigt,
  wie viele Liter er bis zum Ziel beisteuert.
- **„Route“**: Der aktuelle %-Wert ist der echte („−5“/„+5“ verschwinden), und der Akkustand bei Ankunft wird daraus
  mit der Kapazität deiner Variante berechnet. Die **Verbrauchsprognose** passt sich an dein Fahrzeug an: Sie geht vom
  echten Verbrauch deiner Fahrten aus (ihrem Verlauf) und wird mit jeder beendeten Route genauer (bei Ankunft vergleicht
  sie die Prognose mit dem, was der Akku tatsächlich verloren hat). Der Anteil der Steigungen ist Physik und bleibt
  unverändert: Geht es auf dem Rest der Strecke bergauf, steigt die Prognose, auch wenn dein Durchschnitt niedriger ist.
- **„Effizienz“**: Der Verbrauch der Fahrt ist der echte (was der Akku verloren hat, mal seine Kapazität, geteilt durch
  die km laut Kilometerzähler), sobald der Akku um 2 % gesunken ist; vorher steht dort „noch zu wenig verbraucht zum
  Messen“. Neben der geschätzten Leistung erscheint die echte, wenn die Daten aktuell sind. Die Aufteilung der Energie
  bleibt geschätzt.
- **„Fahrten“**: Der Verbrauch jeder Fahrt ist **der, den das Fahrzeug** in seinem Verlauf angibt (markiert mit
  „FAHRZEUG“; derselbe wie in der offiziellen Leapmotor-App) oder, falls er fehlt, der echte aus dem Rückgang des
  %-Werts (markiert mit „REAL“); unter „Rekorde“ die Summe der letzten 30 Tage laut Fahrzeug.
- **Fahrtenverlauf des Fahrzeugs:** Die Cloud speichert deine Fahrten der letzten Wochen mit den kWh (beim REEV auch
  den Litern), die das Fahrzeug selbst misst, und seinen wöchentlichen Durchschnittsverbrauch. Qonecta+ liest ihn
  höchstens alle 30 Minuten (3 und 12 Minuten nach dem Ende einer Fahrt) und den Wochendurchschnitt alle 12 Stunden.

Jede Zahl zeigt, woher sie stammt: „real · vor 40 s“ (das Alter der Daten: Die Cloud arbeitet nicht in Echtzeit und
liefert bei ausgeschaltetem Fahrzeug den letzten bekannten Stand) oder „geschätzt“.

**So richtest du es ein** (⚙ › „Fahrzeugdaten (Leapmotor-Konto)“, Android 6 oder höher):

1. **Client-Zertifikat.** Es ist dasselbe, das die App LMB10 verlangt; Qonecta+ enthält es nicht und stellt es nicht
   bereit. Tippe auf „Zertifikat importieren…“ und wähle in der Dateiauswahl app.crt und app.key: beide gleichzeitig
   (lange drücken, um zwei zu markieren) oder **nacheinander** (die App sagt, welche fehlt; „Neu beginnen“ verwirft eine
   halb fertige Auswahl), eine .pem mit beiden Blöcken oder eine .p12/.pfx (hat sie ein Passwort, wird es abgefragt).
2. **Konto.** Deine Leapmotor-E-Mail-Adresse und dein Passwort, dann „Anmelden“. Das Passwort wird nicht gespeichert.
3. **Fahrzeug.** Hat das Konto eines, ist es schon ausgewählt; hat es mehrere, wähle deins.
4. **Akku.** Die Cloud nennt die Variante nicht: „C10 Life · 69,9 kWh“, „C10 ProMax · 81,9 kWh“, „C10 REEV · 28,4 kWh
   + Benzin“ (wird automatisch gewählt, wenn das Fahrzeug einen Tank hat) oder „Andere“ (kWh von Hand). Damit wird der
   %-Wert in kWh umgerechnet.
5. **„Status jetzt abrufen“** zum Prüfen: Akku, Reichweite, Laden, Reifendruck und Alter der Daten.

Mit laufendem „Auto erweitert“ (oder der „Vorschau des erweiterten Modus“) liest Qonecta+ das Fahrzeug alle 2 Minuten
aus (alle 90 s, wenn der Bereich „Fahrzeug“ angezeigt wird, wie bei LMB10); hat das Fahrzeug nichts Neues hochgeladen
(geparkt oder im Ruhezustand), alle 5 und dann alle 15 Minuten; bei Fehlern nach 2, 5 und 10 Minuten; und höchstens 400
Abfragen pro Tag (einschließlich Verlauf), um eine inoffizielle API nicht übermäßig zu belasten. Beim Trennen hört es
auf. „Echte Fahrzeugdaten nutzen“ schaltet es ab, ohne etwas zu löschen.

**Datenschutz und Sicherheit.**

- Nur lesend, mit einer optionalen Ausnahme: die **Befehle des Widgets „Auto“** (verriegeln, entriegeln, Kofferraum,
  Klima und finden), und nur, wenn du die PIN des Fahrzeugs in „Fahrzeugdaten“ → „5 · PIN des Autos“ speicherst und dort
  die Befehle über das Widget einschaltest (es ist die PIN der Leapmotor-App; sie wird verschlüsselt auf dem Handy
  gespeichert und landet nie im Log). Ohne PIN **sendet Qonecta+ nie Befehle an das Fahrzeug**, und andere Befehle gibt
  es nicht (weder Laden noch Limits). Jeder Befehl wird vor dem Senden bestätigt; „PIN vergessen“ löscht die PIN.
- Deine Daten gehen **nur an die Server von Leapmotor**. Die Position des Fahrzeugs wird weder gelesen noch gespeichert.
- Zertifikat, Sitzung und Fahrtenverlauf werden mit einem Schlüssel aus dem Android Keystore **verschlüsselt**
  gespeichert, außerhalb der Backups. Das Passwort wird nicht gespeichert: Läuft die Sitzung ab, zeigt „Status“ das an,
  und du meldest dich auf dem Handy erneut an.
- Der Leapmotor-Server nutzt ein Zertifikat seiner eigenen Zertifizierungsstelle. Qonecta+ prüft, ob dessen Schlüssel
  der bekannte ist; ändert er sich eines Tages, verbindet sich Qonecta+ erst, wenn du ihn auf dem Handy akzeptierst (tu
  das nur in einem vertrauenswürdigen Netz).
- Im Log: die E-Mail-Adresse maskiert (c\*\*\*@e\*\*\*.com) und eine Zeile pro Abfrage (%, Reichweite, Laden, kW, km
  und Latenz), ohne Tokens, FIN oder Position.
- „Abmelden und Daten löschen“ entfernt Zertifikat, Sitzung und Kontoeinstellungen von diesem Handy.

> [!WARNING]
> Diese Funktion nutzt eine **inoffizielle API** von Leapmotor: Sie kann jederzeit aufhören zu funktionieren, und
> Qonecta+ steht in keinerlei Verbindung zu Leapmotor. Beim **C10 REEV** taugt der echte Verbrauch aus dem Rückgang des
> %-Werts nicht, wenn der Generator während der Fahrt lädt: Dann zählen der Verlauf des Fahrzeugs und sein Benzinzähler.

Das Protokoll stammt von **[LMB10](https://github.com/txurtxil/LPB10)** von **txurtxil** (GPL-3.0). Die Tanksignale der
REEV und der Fahrtenverlauf stammen von **[leapmotor-mate](https://github.com/ProtossBlaster/leapmotor-mate)**
(ProtossBlaster), der Wochenverbrauch von **[leapmotor-api](https://github.com/markoceri/leapmotor-api)** (markoceri),
beide AGPL-3.0.

#### Zweites Konto als „gemeinsames Mitglied“ (empfohlen)

Die offizielle Leapmotor-App erlaubt nur eine Sitzung pro Konto: Meldest du dich in Qonecta+ mit demselben Konto an,
kann dich die offizielle App auf dem Handy abmelden (und umgekehrt). Im Forum wird dafür diese Lösung genutzt:

1. Lege mit einer anderen E-Mail-Adresse ein zweites Leapmotor-Konto an.
2. Lade dieses Konto in der offiziellen App als Mitglied zur gemeinsamen Nutzung deines Fahrzeugs ein (im Bereich zum
   Teilen des Fahrzeugs bzw. zum Hinzufügen von Mitgliedern).
3. Nimm die Einladung mit dem zweiten Konto an.
4. Nutze dieses zweite Konto nur in Qonecta+.

So bleibt die offizielle App mit deinem Hauptkonto angemeldet. Fernbefehle (z. B. entriegeln oder Klima) funktionieren
nur, wenn der Eigentümer dem Mitglied diese Berechtigungen gibt.

---

## 6. Hitze und Akku

Gleichzeitig Video übertragen und WLAN und GPS nutzen: Das erwärmt das Handy. Beim ersten langen Test mit dem Profil
„Sehr hoch“ bei 60 fps erreichte das S25 Ultra in 15 Minuten den Android-Wärmestatus „schwer“ und danach „kritisch“.
Deshalb bringt diese Version drei Änderungen:

1. **Profil „Fahrzeug“:** sendet 30 fps und die Bitrate, die der C10 anfordert (ca. 5 Mbit/s). Genau das zeigt das
   Fahrzeug an: Mehr sieht nicht besser aus und erwärmt nur stärker.
2. **Handybildschirm aus:** Der Bildschirm geht wie gewohnt aus, und die Übertragung läuft weiter. Die Option
   „Handybildschirm anlassen“ ist standardmäßig aus.
3. **Automatische Hitzeanpassung** (Android 10 oder höher und Engine QDAuto; mit „Basis“ im Modus „Auto“ greift sie
   nicht, weil dieses Profil nicht neu kodiert):

| Wärmestatus von Android | „Hitzeschutz“: „Normal (empfohlen)“ | „Sanft“ |
|---|---|---|
| Normal oder leicht | Nichts: die fps und die Bitrate der Sitzung. | Nichts. |
| Mäßig | Auf **30 fps**, wenn die Sitzung wegen „Bildrate“ mit 60 läuft (bei 30 bleiben die fps gleich), und 80 % der Bitrate. | Nur die Bitrate auf 80 %. |
| Schwer | Runter auf **24 fps** und höchstens **3,5 Mbit/s**. | Höchstens **30 fps** und 3,5 Mbit/s. |
| Kritisch oder schlimmer | Runter auf **20 fps** und höchstens **3 Mbit/s**. | Dasselbe: 20 fps und 3 Mbit/s. |

Höher gestuft wird sofort. Zurück zum Normalzustand geht es, wenn das Handy **30 Sekunden am Stück** kühler ist. Das
passiert ohne Abbruch der Sitzung, ohne Neustart von Android Auto und ohne Hinweise: Du merkst nur, dass das Bild etwas
weniger flüssig ist. Es wird im Log vermerkt. Mit „Hitzeschutz“ auf „Aus“ ändert sich nichts (es wird nur vermerkt). Ist
zusätzlich die Verbindung knapp, gilt die niedrigere der beiden Grenzen (Hitze oder Verbindung).

**Vorausschau** (Android 11 oder höher): Neben dem Status liefert Android eine Prognose des thermischen Spielraums. Nähert er sich 20 Sekunden lang dem Limit (0,85), geht das Bild auf 45 fps (falls es mit 60 lief) und 90 % der Bitrate; nähert er sich noch weiter (0,92), auf 30 fps und 80 %. So erreicht das Handy „schwer“ gar nicht erst, und es gibt keine abrupten Einbrüche. Nach einer Minute Abkühlung geht es Schritt für Schritt zurück. Mit „Aus“ greift auch das nicht.

**Laden und Hitze:** Über 80 % erwärmt das Laden das Handy am meisten. Lädt das Handy über 80 % bei einer Akkutemperatur von 39 °C oder mehr, erinnert Qonecta+ dich (einmal am Tag) daran, „Akku schützen“ einzuschalten (lädt bis 80 %), mit einer Taste zu den Akku-Einstellungen.

**Tipps**

- Nutze das Profil „Automatisch“ oder „Fahrzeug“.
- **Sperr das Handy**, sobald Android Auto erscheint.
- Vermeide **direkte Sonne**: Lass es nicht auf dem Armaturenbrett liegen und nicht direkt an der Windschutzscheibe.
- Besser eine **belüftete Halterung oder eine mit Lüfter**, zum Beispiel am Lüftungsgitter der Klimaanlage, als eine
  geschlossene Ablage. Wenn das kabellose Ladegerät es stark erwärmt, lade per Kabel.
- Nimm während der Übertragung **keine 4K-Videos auf**, spiel nicht und nutze keine anderen rechenintensiven Apps auf
  dem Handy.
- Nimm im Sommer eine dicke Hülle ab.
- Lass das Handy auf langen Fahrten laden, wenn möglich per Kabel.

---

## Häufige Fragen

**Warum muss ich die Bedienungshilfe nach einem Update wieder einschalten?**
Android (und manche Hersteller) schaltet die Bedienungshilfen einer App ab, wenn sie per APK aktualisiert wird. Seit
Version 0.2.62 meldet Qonecta+ das mit einer Benachrichtigung, die direkt die Einstellung öffnet; du findest es auch unter
⚙ › „Voraussetzungen prüfen“ › „Aktivieren“. Das dauert nur einen Moment: „Qonecta+ Touch“ einschalten, fertig.

**Bei der Installation meldet Android, die App sei wegen eines Konflikts mit einem vorhandenen Paket nicht installiert
worden.**
Du hast eine andere App mit derselben Kennung, die mit einem anderen Schlüssel signiert ist: fast immer das ursprüngliche
HeadQLink. Deinstalliere sie (ihre Einstellungen gehen dabei verloren) und installiere Qonecta+ erneut.

**Beim Trennen scheint das Handy neu zu starten (der Bildschirm wird kurz schwarz und der Startbildschirm lädt neu).**
Das war der „Android-Automodus“: Beim Verlassen lädt Android alle Apps neu. Seit Version 0.2.62 nutzt Qonecta+ ihn
nicht mehr. Wenn du ihn zurückhaben willst: „Bildeinstellungen“ › „Erweitert“ › „Android-Automodus“.

**Die Musik ist leiser als mit einem Dongle.**
Mit Qonecta+ läuft der Ton über das Bluetooth des Fahrzeugs, nicht über das Kabel. Dreh die Medienlautstärke des Handys
ganz auf und regle die Lautstärke am Fahrzeug. Ist es immer noch zu leise, aktiviere in den „Entwickleroptionen“ des
Handys „Absolute Lautstärke deaktivieren“.

**Beim T03 ist alles riesig.**
Seit Version 0.2.55 passt sich Android Auto kleinen Bildschirmen von selbst an. Willst du es kleiner oder größer:
„Bildeinstellungen“ › „Größe von Android Auto“ (auch vom Fahrzeug aus, unter Einstellungen › Bild).

**Mit dem Profil „Maximum (Kabel)“ bleibt das Fahrzeugdisplay schwarz oder zeigt Bildfehler.**
Das liegt am Format H.264 High, das nicht jedes Fahrzeug annimmt. Geh zurück zu „Fahrzeug“, oder bleib bei „Maximum
(Kabel)“ und stell „Erweitert“ › „H.264-Profil“ › „Baseline“ ein.

**Route und Ladestationen werden nicht angezeigt.**
Fast immer war der Dienst überlastet, der die Geländehöhen liefert. Seit Version 0.2.60 erscheint die Route trotzdem
(ohne die Steigungen zu berücksichtigen, und die App weist darauf hin). Erscheint gar nichts, prüfe, ob das Handy mobile
Daten hat.

**Im TV fehlen Antena 3, laSexta, Cuatro… (Spanien)**
Diese Sender haben keinen frei zugänglichen Internet-Stream, deshalb bringt sie keine M3U-Liste mit. Mit der JSON-Liste
von TDTChannels (`https://www.tdtchannels.com/lists/tv.json`) öffnen die Sender von Mediaset die Webseite ihres
Livestreams, und die YouTube-Kanäle erscheinen.

**Mit dem Bluetooth-Gamepad geht bei jedem Tastendruck der Handybildschirm an.**
Das macht Android, und eine App kann es nicht verhindern. Seit Version 0.2.60 legt das Handy während des Spiels eine
schwarze Ebene mit minimaler Helligkeit über den Bildschirm, damit das nicht passiert; tippst du aufs Handy, verschwindet
sie.

**Warum höre ich das DAB-Radio statt Android Auto, oder warum fragt das Fahrzeug, ob es die Quelle wechseln soll?**
Der C10 spielt immer nur eine Audioquelle gleichzeitig. Der Ton von Android Auto kommt wie bei jedem anderen Handy über
das Bluetooth des Fahrzeugs. Fragt das Fahrzeug, ob es zum Handy als Quelle wechseln soll, bestätige das; um wieder
Radio zu hören, wähle im Fahrzeug das Radio aus.

**WLAN oder Kabel?**
Per USB-Kabel läuft es flüssiger, das Bild ist besser, und das Handy wird geladen. Per WLAN ist manchmal eine
Verzögerung zu spüren. Ruckelt es bei dir per WLAN, probier das Kabel aus
([Verbindung per USB-Kabel](#verbindung-per-usb-kabel)).

**Die automatische Verbindung stellt die Bluetooth-Verbindung nicht wieder her.**
Qonecta+ wartet, bis sich das Handy mit dem Bluetooth des Fahrzeugs verbindet. Verbindet Android es nicht von selbst
neu, verbinde es einmal von Hand und prüfe in den Bluetooth-Einstellungen des Handys, ob das Fahrzeug als
vertrauenswürdiges Gerät eingetragen ist bzw. sich automatisch verbinden darf.

**Kann ich das Projekt unterstützen?**
Qonecta+ ist kostenlos und werbefrei. Wenn es dir hilft, kannst du mir über [Ko-fi](https://ko-fi.com/charlysev) (mit
Karte oder PayPal) oder per [PayPal](https://www.paypal.me/CharlysEV) einen Kaffee spendieren; auch in der App unter
„Über und Danksagungen“. Danke!

**Wie melde ich einen Fehler oder schlage etwas vor?**
Im Thread des LPMFF-Forums: [lpmff.de/thread/3341-qonecta-app](https://www.lpmff.de/thread/3341-qonecta-app/). Bei
einem Fehler hilft das exportierte Log (Abschnitt 8); teile es lieber privat als offen im Thread.

**Wie bekomme ich Hilfe?**
Exportiere das Log (Abschnitt 8) und schreib dazu, was passiert ist und um wie viel Uhr. Es enthält keine Passwörter,
keine Anschriften und nichts von dem, was du eintippst.
Um einen Fehler zu melden oder etwas vorzuschlagen, eröffne ein „Issue“ unter [github.com/CharlysEV/qonecta/issues](https://github.com/CharlysEV/qonecta/issues). Das ist öffentlich: das Log lieber privat teilen.

---

## 7. Fehlerbehebung

Fang immer mit dem Menü ⚙ › „Voraussetzungen prüfen“ an: Jede rote Zeile hat ihre eigene Taste.

| Symptom | Wahrscheinliche Ursache | Was tun |
|---|---|---|
| Per Kabel friert das Fahrzeugdisplay ein, und „Trennen“ und „Verbinden“ helfen nicht | Die Spiegelungsapp im Fahrzeug (QDLink) hängt bei eingestecktem Kabel: Sie antwortet nicht mehr und liest nicht einmal, was ankommt. Das liegt am Fahrzeug, nicht am Handy. | Zieh das Kabel ab, steck es wieder ein und öffne QDLink auf dem Fahrzeugdisplay; Qonecta+ verbindet sich selbst neu. Es sagt dir das per Sprache und mit einer Meldung auf dem Handy („Die App im Auto hängt“). |
| Das Fahrzeug erscheint nicht (dauerhaft „Suche…“) | Das Fahrzeug ist nicht im Hotspot des Handys, der Hotspot hat sich von selbst ausgeschaltet, oder die Spiegelungsapp ist im Fahrzeug nicht offen. Mit Wi-Fi Direct: Hotspot an oder WLAN aus. | Sieh dir die Zeile „Netzwerk“ an: Dort muss „Hotspot an (…)“ stehen. Verbinde das Fahrzeug mit dem Hotspot (Einstellungen › Wi-Fi im Fahrzeug) und öffne die Spiegelungsapp. Schalte das automatische Ausschalten des Hotspots ab. Meldet sich das Fahrzeug 5 Minuten lang nicht, hört Qonecta+ auf: Tippe noch einmal auf „Verbinden“. |
| „Port 18463 ist belegt (ist QDLink offen?)“ oder „Port 18463 · Belegt“ in „Voraussetzungen prüfen“ | Die App QDLink (oder eine andere) ist auf dem Handy offen und belegt den Port. | „Beenden erzwingen“ für QDLink. Qonecta+ versucht es alle 5 s erneut, und der Hinweis verschwindet von selbst. |
| Schwarzes Bild im Fahrzeug, oder die Zeile „Auto“ zeigt einen Fehler | Android Auto ist nicht gestartet: Das Handy war gesperrt („Startet nicht: entsperre das Handy“), oder der Entwicklermodus oder die Bedienungshilfe fehlt. | Entsperre das Handy (bei der Meldung „Entsperre das Handy, um Android Auto zu starten“ startet es beim Entsperren von selbst). Klappt es weiterhin nicht, sieh unter „Voraussetzungen prüfen“ nach und tippe auf „Trennen“ und dann auf „Verbinden“. Um herauszufinden, woran es liegt, schalte unter „Diagnose“ den „Test ohne Android Auto (Testbild)“ ein: Siehst du das Testbild, funktioniert die Verbindung, und das Problem liegt bei Android Auto (schalte den Test danach wieder aus). Hilft nichts davon, probier unter „Protokoll-Engine“ die Option „Älterer Motor (übernommen)“. |
| Das Bild ruckelt oder kommt verzögert | Hotspot auf 2,4 GHz, Fahrzeug weit vom Handy entfernt, Handy heiß, Profil zu hoch oder Handybildschirm an (dann sucht Android oft nach WLAN-Netzen). | Hotspot auf 5 GHz, Profil „Automatisch“ oder „Fahrzeug“ (beide passen sich von selbst an die Verbindung an: im Log, das auf Spanisch ist, die Zeilen `enlace: congestión…`), Handy sperren und abkühlen lassen. Ruckelt es weiter, probier „Mittel“. |
| Viele Funkaussetzer (das Bild bleibt immer wieder eine halbe Sekunde stehen; im Log Zeilen „Corte … RADIO“ und „enlace muy congestionado“) | Die Funkverbindung zwischen Handy und Fahrzeug ist am Limit: Hotspot auf 2,4 GHz, das Handy in einer Tasche, in einer Metallablage oder weit vom Display entfernt. Qonecta+ senkt die Qualität schon so weit wie möglich (bis auf 1,2 Mbit/s und 20 fps), aber bei überlasteter Funkverbindung reicht das nicht. | Stell den Hotspot auf **5 GHz** (Einstellungen › Hotspot › Band). Nimm das Handy aus Taschen und Metallablagen und leg es nah an das Display des Fahrzeugs. Am stabilsten ist das **USB-Kabel**. Im Log nennt die Zeile „radio de la zona Wi-Fi“ Band und Kanal, wenn Android sie auslesen lässt. |
| Das Bild ist weniger flüssig als mit dem ursprünglichen HeadQLink | Das Original schickte 60 fps; Qonecta+ schickt 30 (so viel, wie das Fahrzeug anfordert), damit das Handy nicht heiß wird. Oder das Handy ist schon heiß und die Hitzeanpassung hat die fps gesenkt, oder die Verbindung ist knapp und Bitrate oder fps wurden gesenkt. | „Bildeinstellungen“ › „Bildrate“ › „60 fps · maximal flüssig“ (mit dem Profil „Fahrzeug“ oder „Automatisch“). Senkt die Hitze die fps: „Hitzeschutz“ › „Sanft“ (Abschnitt 6). Im Log zeigen die Zeilen „HQL/Térmico“ („estado térmico 2“ oder höher) und „enlace:“, welche der beiden Ursachen zutrifft. |
| Das Bild friert etwa 10 s ein, dann verbindet es sich neu | In früheren Versionen hat ein vollständiges Bild (Keyframe) von mehr als ~512 KB den Empfänger im Fahrzeug aufgehängt: Er las nichts mehr, bis die Verbindung abbrach. **Behoben**: Qonecta+ schickt keine so großen Bilder mehr und hält sie bei höchstens etwa 300 KB. | Aktualisiere Qonecta+. Passiert es dir mit dem Profil „Basis“ (dort bestimmt Android Auto die Größe), nimm „Automatisch“ oder „Fahrzeug“. Tritt es weiter auf, exportiere das Log (Abschnitt 8). |
| Ein Video oder ein TV-Sender läuft ohne Ton | Der Ton ist AC-3, E-AC-3 (Dolby Digital Plus) oder DTS, das viele Handys nicht decodieren können. **Behoben**: Qonecta+ decodiert ihn mit FFmpeg, wenn das Handy es nicht kann. | Aktualisiere Qonecta+. Erscheint weiterhin „Kein Ton: Dieses Handy kann den Ton dieses Videos nicht decodieren …“, exportiere das Log (Abschnitt 8). |
| Die Verbindung bricht oft ab | Automatisches Ausschalten des Hotspots, Energiesparmodus, QDLink offen oder die Spiegelungsapp im Fahrzeug geschlossen. | Kurze Abbrüche verbinden sich von selbst neu („Verbinde erneut…“). Sind sie lang oder häufig: „Keine Akkueinschränkungen“, „Samsung: nie im Standby“, das automatische Ausschalten des Hotspots abschalten und QDLink schließen. Passiert es weiter, exportiere das Log (Abschnitt 8). |
| Benachrichtigung „Starte den Android-Auto-Server (neu)“, oder die Zeile „Auto“ zeigt „Warte auf den Android-Auto-Server“ | Manueller Start, und Android Auto antwortet nicht: Sein Server ist aus (nach einem Neustart des Handys oder einem Update von Android Auto) oder blockiert: Eine Verbindung wurde mittendrin abgebrochen (etwas hat sich verbunden und ist ohne Abmeldung wieder verschwunden, oder eine Sitzung wurde abrupt beendet). | Tippe auf die Benachrichtigung oder die Zeile: Android Auto › ⋮ › „Server für Infotainmentsystem beenden“ (falls vorhanden) und ⋮ › „Server für Infotainmentsystem starten“. Qonecta+ versucht es alle 5 s erneut und macht von selbst weiter. |
| Benachrichtigung „Qonecta+ findet die Servertaste nicht“ | Android Auto wurde aktualisiert und hat sein Menü ⋮ geändert (die Automatisierung sucht die Taste anhand ihres Textes). | „AA öffnen“ und den Server von Hand starten, oder „Manueller Start“. Sieh dir [Android-Auto-Versionen](#android-auto-versionen) an und exportiere das Log (Abschnitt 8). |
| Benachrichtigung „Android Auto verbindet sich und bricht nach ein paar Sekunden ab“ | Bekanntes Symptom einiger neuer Android-Auto-Versionen (17.8). | Leere den Cache von Android Auto (Einstellungen › Apps › Android Auto › Speicher › Cache leeren), beende seinen Server und starte ihn neu, oder geh zu einer getesteten Version zurück ([Android-Auto-Versionen](#android-auto-versionen)). |
| Die Bedienungshilfe schaltet sich von selbst aus | Android schaltet sie beim Update der App aus, oder wenn die App abrupt beendet wurde. Manche Hersteller ebenfalls. | „Voraussetzungen prüfen“ › „Aktivieren“. Steht dort „Aktiv, läuft aber nicht“, schalte sie aus und wieder ein. Steht dort „Eingeschränkte Einstellung“: „Eingeschränkte Einstellungen zulassen“ (Abschnitt 3). Entferne die Akkueinschränkungen. |
| Android Auto bittet dich im Fahrzeug, aufs Handy zu schauen | Android Auto sieht diesen „Fahrzeugbildschirm“ zum ersten Mal, oder es muss eine Berechtigung oder Bestätigung von dir einholen. | Parke, entsperre das Handy und bestätige, was Android Auto verlangt. Normalerweise passiert das nur einmal. |
| Das Handy wird sehr heiß | Profil „Sehr hoch“ oder „Hoch“, direkte Sonne, Bildschirm an, kabelloses Laden. | Abschnitt 6. |
| Benachrichtigung „Android-Auto-Server ist offen · Entsperre das Handy, um ihn zu schließen“ | Die Sitzung endete bei gesperrtem Handy, und der Android-Auto-Server ist noch offen. | Entsperre das Handy: Qonecta+ schließt ihn. |
| Nach einem Halt dauert es lange, bis das Bild wieder da ist, oder du musst die App schließen und wieder öffnen | Der Halt dauerte länger als „Auf das Fahrzeug warten“, und Qonecta+ hat alles geschlossen; beim Entsperren schließt es zuerst Android Auto („Android Auto wird beendet…“), danach muss es neu gestartet werden. | Stell „Auf das Fahrzeug warten“ auf „15 min“ (Bildeinstellungen › Erweitert) und schalte die automatische Verbindung per Bluetooth ein: Wenn du zurückkommst, ist Android Auto sofort da, ohne Entsperren. Siehst du „Entsperre das Handy, um Android Auto zu starten“, entsperre es und warte: Es startet von selbst, ohne dass du die App öffnest. |
| „Android Auto wird beendet…“ oder „Android Auto startet…“ verschwindet nicht, oder die Benachrichtigung „Der Android-Auto-Server läuft noch · Zum Ausschalten tippen“ | Die Automatisierung in den Einstellungen von Android Auto wurde nicht fertig (zum Beispiel, weil sich das Handy mittendrin gesperrt hat). | Die Einblendung verschwindet nach 15 s von selbst. Tippe bei entsperrtem Handy auf die Benachrichtigung, um den Server auszuschalten. Wiederholt es sich, exportiere das Log (Abschnitt 8): Die Zeilen „ciclo:“ beschreiben jeden Schritt. |
| Die Fahrzeugdaten frieren ein, wenn du das Handy sperrst (Tempo, Route, Fahrt, Verbrauch; nach dem Entsperren laufen sie weiter) | Qonecta+ hat den Standort nur „während der Nutzung der App“, und Android entzieht ihm bei gesperrtem Handy das GPS. Das Fahrzeugdisplay und die Bewegungssensoren laufen weiter, das GPS nicht. | „Voraussetzungen prüfen“ › „Standort immer zulassen“ › „Öffnen“ › „Immer zulassen“. Im Log bestätigt „GPS: ubicación todo el tiempo sí · con el móvil bloqueado llega“, dass es funktioniert. |
| Die automatische Verbindung startet nicht | Der Bluetooth-Name stimmt nicht, oder Android lässt sie nicht im Hintergrund starten. | Prüfe den Text im Dialog „Automatische Verbindung“, entferne die Akkueinschränkungen oder tippe auf die Benachrichtigung „Tippen, um Qonecta+ zu verbinden“. |
| Die Installation oder das Update klappt nicht | Play Protect, der Auto Blocker von Samsung oder eine Version mit anderer Signatur. | Abschnitt 2. |

---

## 8. Log exportieren und Hilfe holen

Wenn etwas nicht funktioniert, hilft das Log sehr, die Ursache zu finden. **Exportiere es direkt nach dem Problem**, und
notiere dir nach Möglichkeit die Uhrzeit, zu der es passiert ist.

1. Menü ⚙ › „Diagnose“.
2. Tippe auf **„Log exportieren“**. Du siehst „Exportiere das Log…“ und danach „Gespeichert in Downloads/Qonecta/…“.
3. Es öffnet sich „Qonecta+-Log teilen“: Schick es per E-Mail, Telegram, Drive oder mit einer beliebigen App, oder lass
   es einfach gespeichert.

<!-- captura: pantalla Diagnóstico con «Exportar log» -->

**Wo es landet:** `Downloads/Qonecta/Qonecta-log-JJJJMMTT-HHMMSS.zip`. Unter Android 9 oder älter bleibt es im Ordner
der App (`Android/data/com.headqlink.app/files/exports/`), und der Hinweis nennt den genauen Pfad.

**Was das ZIP enthält**

| Datei | Inhalt |
|---|---|
| `resumen.txt` | App-Version, Handymodell und Android-Version, deine Einstellungen, Verbindung und Engine, aktueller Status, Netzwerkschnittstellen, letzte Sitzungen und die Liste der enthaltenen Dateien (auf Spanisch). |
| `logs/` | Das vollständige Protokoll, mit `sessions.csv` (eine Zeile pro Sitzung mit dem Fahrzeug). |
| `car/`, `perf/`, `logcat/`, `crash/` | Fahrzeugtagebuch, Leistung, System-Log der App und unerwartete Abstürze (jeweils die neuesten). |

**Datenschutz im Log**

- **Enthalten:** Kennungen des Fahrzeugs (CarUUID, ProjectID), IP-Adressen, Netzwerknamen, Handymodell und deine
  Einstellungen.
- **Nicht enthalten:** deine Fahrten mit GPS (`trips/` wird nie exportiert), Koordinaten, Ziele und Screenshots. Logs
  früherer Versionen konnten allerdings einzelne Standorte enthalten.
- **Nichts wird automatisch verschickt:** Du entscheidest, mit wem du es teilst. Am besten privat, nicht öffentlich
  posten.

Hilfe bekommst du auch auf der Projektseite bei GitHub: [CharlysEV/qonecta](https://github.com/CharlysEV/qonecta).

---

## 9. Sicherheit, Datenschutz und rechtliche Hinweise

### Berechtigungen und wofür sie gebraucht werden

| Berechtigung | Wofür | Wann |
|---|---|---|
| Bedienungshilfe „Qonecta+ Touch“ | Den Android-Auto-Server starten und stoppen, indem sie sein Entwicklermenü bedient, und die Berührungen vom Fahrzeug empfangen. **Sie ist auf Android Auto beschränkt:** Andere Apps sieht sie nicht. | Android Auto 17.4 oder neuer |
| Wi-Fi-Geräte in der Nähe (bzw. Standort unter Android 12 oder älter) | Dem Wi-Fi-Direct-Netz des Fahrzeugs beitreten. | Nur mit „Wi-Fi Direct“ |
| Benachrichtigungen | Den Verbindungsstatus und die Hinweise anzeigen. | Immer (empfohlen) |
| Bluetooth (Geräte in der Nähe) | Das Bluetooth des Fahrzeugs erkennen. | Nur mit der automatischen Verbindung |
| Keine Akkueinschränkungen | Bei ausgeschaltetem Bildschirm weiterlaufen. | Empfohlen |
| Über anderen Apps einblenden | Android Auto öffnen, während die App im Hintergrund läuft. | Optional |
| Fotos, Videos, Musik und Standort | Galerie, deine Musik und Fahransichten. | Optional, „Auto erweitert“ |
| Standort immer zulassen | Damit die Fahrzeug-Ansichten bei gesperrtem Handy weiterlaufen. Wird nur genutzt, solange die Verbindung läuft oder die Fahrzeug-Ansichten offen sind. | Empfohlen, „Auto erweitert“ |
| Mikrofon | „Sprechen“ in „Ziel suchen“: Die Spracherkennung des Handys wertet es aus. | Optional, „Auto erweitert“ |
| Internet | Dienste von „Auto erweitert“ (OpenStreetMap, OSRM, Open-Meteo, radio-browser.info), die Zielsuche (Photon und die Adresssuche von Android, also von Google: Beide erhalten, was du eintippst), die Karte der Fahrten (Kacheln von OpenStreetMap), deine TV- und Radiolisten und, wenn du sie einrichtest, die Leapmotor-Cloud (echte Fahrzeugdaten, nur lesend). | Nur für diese Funktionen |

- Qonecta+ **sendet keine Telemetrie** und zeigt keine Werbung.
- Standardmäßig **teilt es das GPS nicht** mit Android Auto.
- Seine internen Dienste sind für andere Apps nicht zugänglich.
- Das APK deklariert weitere Berechtigungen, die von Open Headunit übernommen sind. Qonecta+ fragt sie bei der
  Einrichtung nicht ab; das Mikrofon wird nur für „Sprechen“ in der Suche genutzt (ist es nicht erlaubt, sagt das
  Fahrzeug, wie du es freigibst).
- Die **letzten Ziele** der Suche und die **Strecken der Fahrten** werden nur auf dem Handy gespeichert (das Log enthält
  weder Ziele noch Positionen).

> [!IMPORTANT]
> **Der Android-Auto-Server.** Mit Android Auto 17.4 oder neuer schaltet Qonecta+ den „Server für Infotainmentsystem“
> aus dem Entwicklermodus von Android Auto ein. Solange er läuft, lauscht er in allen Netzen des Handys, deshalb
> **schaltet Qonecta+ ihn am Ende aus**: wenn du auf „Trennen“ tippst oder wenn „Auf das Fahrzeug warten“ abläuft
> (höchstens 15 Minuten ohne Fahrzeug, während Android Auto pausiert und den Server belegt). Bei gesperrtem Handy kann
> es ihn nicht ausschalten und lässt ihn in Wartestellung, bis du entsperrst („Android Auto wartet, bis du das Handy
> entsperrst…“). **Entsperre das Handy nach jeder Fahrt.**
>
> Beim manuellen Start (ohne Bedienungshilfe) kann Qonecta+ ihn nicht ausschalten: Am Ende schließt es Android Auto,
> aber der Server bleibt an (dadurch startet die nächste Fahrt, ohne dass du etwas tun musst). **Beende ihn selbst**
> (Android Auto › ⋮ › „Server für Infotainmentsystem beenden“), wenn du ihn nicht brauchst, vor allem in einem
> öffentlichen WLAN.

**Wenn du die App nicht nutzt:**

- Tippe auf „Trennen“, wenn du Qonecta+ außerhalb des Fahrzeugs geöffnet hast: Beim Öffnen beginnt es von selbst zu
  verbinden.
- Schalte den Hotspot aus.
- Schalte „Verbinden, wenn das Bluetooth des Fahrzeugs erkannt wird“ aus, wenn die App nicht von selbst starten soll.
- Nutzt du sie längere Zeit nicht, schalte die Bedienungshilfe „Qonecta+ Touch“ aus und verlasse, wenn du willst, den
  Entwicklermodus von Android Auto.

### Rechtlicher Hinweis

- **Die Software wird „WIE BESEHEN“ und OHNE GEWÄHRLEISTUNG JEGLICHER ART bereitgestellt.** Sie ist experimentell,
  nutzt ein nicht dokumentiertes Protokoll und kann jederzeit Fehler aufweisen oder nicht mehr funktionieren.
- **Die Autoren haften nicht** für Schäden jeglicher Art: Unfälle, Verletzungen, Schäden am Fahrzeug, am Handy oder bei
  Dritten, Datenverlust, Bußgelder, das Erlöschen von Garantien oder Verstöße gegen Bedingungen Dritter. Du nutzt die
  Software **auf eigene Verantwortung**.
- **Nutze sie nicht, während du fährst.** Allein der Fahrer ist für die Einhaltung der Verkehrsregeln verantwortlich.
  Sieh dir während der Fahrt keine Videos, kein TV und keine Webseiten an und spiele keine Spiele.
- Qonecta+ steht **in keiner Verbindung** zu Leapmotor, Google, Neusoft, Open Headunit oder einem anderen Unternehmen
  oder Projekt und wird **von keinem davon unterstützt oder gesponsert**. Leapmotor, C10, Android Auto und QDLink sind
  Marken ihrer jeweiligen Inhaber.

### Lizenz und Danksagungen

- **Qonecta+ stammt von [CharlysEV](https://github.com/CharlysEV)** (Copyright © 2026 CharlysEV).
- Lizenz: **[GNU AGPL-3.0](LICENSE)**. Du hast das Recht, den Quellcode zu erhalten: Er liegt unter
  [github.com/CharlysEV/qonecta](https://github.com/CharlysEV/qonecta). Die Abschnitte 15 und 16 der Lizenz
  (Gewährleistungsausschluss und Haftungsbeschränkung) gelten ebenfalls.
- Basiert auf **[headqlink](https://github.com/ryazrm/headqlink)** von **ryazrm**, das seinerseits auf
  **[Open Headunit](https://github.com/andreknieriem/open-headunit)** von **André Rinas (andreknieriem)** und auf der
  ursprünglichen Arbeit von **Michael Reid** ([Copyright-Hinweis](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt)) aufbaut.
  Qonecta+ ist ein unabhängiges Projekt ohne Verbindung zu HeadQLink, Open Headunit oder deren Autoren.
- Protokoll-Engine **QDAuto**: [CharlysEV/qdauto](https://github.com/CharlysEV/qdauto).
- Echte Fahrzeugdaten (Leapmotor-Konto): das Protokoll, portiert von **[LMB10](https://github.com/txurtxil/LPB10)** von
  **txurtxil** (GPL-3.0); die Befehle des Widgets „Auto“ und der Wochenverbrauch auf Grundlage von
  **[leapmotor-api](https://github.com/markoceri/leapmotor-api)** von **markoceri**; der Tank des REEV und der
  Fahrtenverlauf auf Grundlage von **[leapmotor-mate](https://github.com/ProtossBlaster/leapmotor-mate)** von
  **ProtossBlaster** (beide AGPL-3.0).
- 3D-Fahrzeug: **[three.js](https://threejs.org)** (MIT) und das Modell „LEAPMOTORS C10“ von **vinishows-1753d1** (CC BY
  4.0, verändert). Karten: **[Leaflet](https://leafletjs.com)** (BSD 2-Clause).
- Online-Daten: Karten, Straßen und Ladestationen © **OpenStreetMap**-Mitwirkende (ODbL), mit Overpass, OSRM, Photon
  (komoot) und Nominatim; Wetter und Höhendaten von **Open-Meteo.com** (CC BY 4.0) und Open Topo Data; Ladestationen in
  Spanien aus dem Register des MITECO, veröffentlicht von der DGT, und in Deutschland aus dem Ladesäulenregister der
  **Bundesnetzagentur** (CC BY 4.0); Radio von **radio-browser.info**; Orte im Reiseführer aus der **Wikipedia**
  (CC BY-SA); Liedtexte für Karaoke von **LRCLIB**.
- Emulatoren: Nestopia, halfNES, bsnes, ClownMDEmu und PCSX ReARMed, jeweils mit eigener Lizenz. Alles Weitere in
  [NOTICE](NOTICE).
