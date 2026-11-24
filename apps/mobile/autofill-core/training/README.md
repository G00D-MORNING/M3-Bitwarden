# Autofill-Erkennung trainieren

Die Feld-Erkennung besteht aus Regeln (`classify/FieldClassifier.kt`, `classify/Lexicon.kt`) und einem
kleinen gelernten Modell (`classify/LearnedModel.kt`, Gewichte in `classify/TrainedWeights.kt`). Das Modell
läuft komplett auf dem Gerät, braucht kein Netz und wird hier auf dem Mac trainiert.

## Daten

| Datei | Herkunft | Beschriftung |
|---|---|---|
| `chromium.tsv` | 181 echte Formulare aus Chromiums Autofill-Testsammlung (`components/test/data/autofill/heuristics`, BSD-Lizenz, © The Chromium Authors) | erwartete Feldtypen aus den `.out`-Dateien; Passwort-/Benutzerfelder, die Chrome dort bewusst als „unbekannt“ führt, werden nicht bewertet |
| `crawl.tsv` | Login-, Registrierungs- und Checkout-Seiten aus `urls.txt`, mit Headless-Chrome in einem leeren Profil abgerufen (nur Formularstruktur, keine Eingaben) | das gültige `autocomplete`-Attribut der Seite selbst; es wird danach entfernt, damit die Erkennung ohne die Antwort auskommen muss |
| `commonTest/.../corpus/*.kt` | von Hand nachgebaute Formulare (Shops, Banken, Behörden, Apps, Fallen wie Suche/Gutschein) | von Hand |

## Neu trainieren

```bash
# 1. Chromium-Sammlung laden (184 Formulare) und umwandeln
mkdir -p /tmp/chromium-autofill/{input,output}
# … Dateien aus chromium.googlesource.com/chromium/src/+/main/components/test/data/autofill/heuristics/{input,output}
python3 training/convert_chromium.py /tmp/chromium-autofill training/chromium.tsv

# 2. Echte Seiten abrufen (≈3 Minuten, 6 Tabs parallel) und beschriften
node training/crawl.mjs training/urls.txt /tmp/crawl-raw.tsv
python3 training/label_crawl.py /tmp/crawl-raw.tsv training/crawl.tsv

# 3. Trainieren, messen, Gewichte schreiben
M3B_TRAIN=1 ./gradlew :autofill-core:jvmTest --tests "*ModelTrainer*" -i
```

Der Trainer (`jvmTest/.../ModelTrainer.kt`) misst per 5-facher Kreuzvalidierung über die Chrome-Formulare,
wie gut Regeln + Modell auf Formularen sind, die das Modell nie gesehen hat. Er wählt Schwelle und Modus
danach aus und prüft gecrawlte Seiten getrennt. `RealWorldTest` und `CorpusTest` zeigen die Fehler im Detail;
`CorpusTest` schlägt fehl, wenn eine Änderung die Erkennung verschlechtert.

## Stand 24.11.2026

| Messung | Ergebnis |
|---|---|
| Chrome-Formulare, alte Regeln (vor dieser Runde) | 70,7 % |
| Chrome-Formulare, neue Regeln | 85,6 % |
| Chrome-Formulare, Regeln + Modell, Kreuzvalidierung | 87,6 % |
| Gecrawlte Seiten, Regeln + Modell | 91,5 % exakt, 95,3 % beim Ausfüllen richtig |
| eigene Sätze (Training / Prüf / Validierung) | 100 % / 99 % / 100 % |

Die Chrome- und Crawl-Werte sind nach dem Ansehen ihrer Fehler entstanden; die erste, unbeeinflusste Messung
auf frischen Seiten lag bei 93,4 % (eigener Validierungssatz) bzw. 84,6 % (Crawl).
