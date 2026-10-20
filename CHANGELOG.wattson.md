# Wattson – Änderungen gegenüber BYDMate

Wattson ist eine angepasste Version von [BYDMate](https://github.com/AndyShaman/BYDMate) von AndyShaman
für den BYD Seal U DM-i. Lizenz: PolyForm Noncommercial 1.0.0 (nur nicht-kommerzielle Nutzung).

## 4.0.1.3

- Automatische Sicherung ist jetzt standardmäßig eingeschaltet (täglich, beim ersten Start nach 24 Stunden)
- Jede Sicherung (automatisch und manuell) wird zusätzlich auf die Speicherkarte bzw. den USB-Stick kopiert,
  Ordner Android/data/com.bydmate.app/files/Sicherungen – die letzten 10 automatischen bleiben dort liegen
- Wiederherstellen findet auch die Sicherungen auf der Karte, z. B. nach einem BYD-Update oder Werksreset
- Die Sicherung enthält jetzt auch die Wattson-Einstellungen (Türentriegelungs-Grenze, Kamerakanäle)

## 4.0.1.2

- Totwinkel-Kamera erscheint jetzt standardmäßig schon im Stand (0 km/h), z. B. beim Ausparken
- Beide Kameras (links und rechts) standardmäßig im Kombiinstrument
- Neu: Kamera-Labor (Einstellungen → Anzeige → Toter Winkel): Kamerakanäle durchschalten,
  Front und Heck festlegen, Testaufnahme auf die Speicherkarte (Vorbereitung für die Unfallaufnahme)

## 4.0.1.1

- Deutsche Oberfläche (komplett übersetzt, als erste Sprache in der Auswahl)
- Fahrerfenster: lässt sich jetzt bei jeder Geschwindigkeit öffnen; Beifahrer- und Rückfenster bleiben über 120 km/h gesperrt
- Türen entriegeln: Geschwindigkeitsgrenze einstellbar (10 / 15 / 20 / 30 km/h, Standard 15) unter Einstellungen → Fahrzeugsystem
- Updates kommen aus dem Wattson-Repository statt aus dem Original
