# External Data Enrichments

Each segment event (avoidance or preference) is enriched with contextual data from four sources. Enrichment runs as independent scheduled jobs that claim batches of un-enriched events and write `SegmentExternalFactor` records.

All enrichment jobs share the same pattern:
1. Claim a batch of events with `enrichment_status = PENDING`
2. Fetch or compute the relevant data
3. Persist a `SegmentExternalFactor` record and mark the event `ENRICHED` (or `FAILED`)

---

## Weather — Open-Meteo Archive API

**Source:** `https://archive-api.open-meteo.com/v1/archive`

Fetches hourly historical weather data for the location and timestamp of each event. The API is queried with the event's GPS coordinates and hour, returning:

- Temperature (°C)
- Precipitation (mm)
- Wind speed (km/h) and direction (°)
- WMO weather code (rain, snow, fog, etc.)

**Wind exposure classification** is derived from the angle between the wind direction and the cyclist's bearing at that edge:

| Angle | Classification |
|---|---|
| 0–30° (tailwind) | `TAILWIND` |
| 30–60° | `DIAGONAL_TAILWIND` |
| 60–120° (crosswind) | `CROSSWIND` |
| 120–150° | `DIAGONAL_HEADWIND` |
| 150–180° (headwind) | `HEADWIND` |

**Rate limiting:** 150 ms delay between API calls (`pipeline.enrichment.weather.delay-between-calls-ms`).

| Property | Default |
|---|---|
| `pipeline.enrichment.weather.batch-size` | `100` |
| `pipeline.enrichment.weather.delay-ms` | `60000` |

---

## Berlin Traffic Detectors

**Source:** [Berlin Verkehrsdetektion](https://api.viz.berlin.de/daten/verkehrsdetektion) — Excel metadata file + monthly measurement archives  

Berlin operates a city-wide network of induction loop traffic detectors. For each segment event, the enrichment finds the nearest detector within **75 meters** that meets quality thresholds, then loads the corresponding hourly traffic measurement.

**Detector matching:**
- Spatial match using GPS coordinates of the event
- Candidate limit: 25 nearest detectors
- Minimum data quality score: 0.75
- Minimum data completeness: 75%

**Traffic data stored per event:**

- Total vehicle count (Kfz), car count (Pkw), truck count (Lkw)
- Average speed (km/h)
- Traffic condition classification:

| Condition | Thresholds |
|---|---|
| `FREE_FLOW` | speed > 30 km/h, volume < 150/h |
| `LIGHT` | volume < 150/h |
| `MODERATE` | volume < 800/h |
| `HEAVY` | volume ≥ 800/h |
| `CONGESTED` | speed ≤ 20 km/h |

The detector metadata (station locations, road names, directions) is downloaded from an Azure Blob Storage URL on first run and cached locally. Monthly traffic archives are cached in `./data/berlinTraffic/cache`.

| Property | Default |
|---|---|
| `pipeline.enrichment.traffic.batch-size` | `2500` |
| `enrichment.traffic.match-radius-meters` | `75` |
| `pipeline.enrichment.traffic.delay-ms` | `60000` |

---

## Berlin Open Data — Road Closures

**Source:** [Baustellen, Sperrungen und sonstige Störungen von besonderem verkehrlichem Interesse](https://daten.berlin.de/datensaetze/baustellen-sperrungen-und-sonstige-storungen-von-besonderem-verkehrlichem-interesse)

Loads a local JSON file from the Berlin Open Data portal containing historical road closures and construction zones. For each segment event, a spatial check determines whether the event's location overlaps with any active closure at the time of the ride.

Events near a closure are flagged with `ExternalFactorType.ROAD_CLOSURE`. This helps distinguish infrastructure avoidance from temporary disruptions.

The file must be downloaded separately and placed at the configured path. It is not fetched automatically.

| Property | Default |
|---|---|
| `pipeline.enrichment.berlin-open-data.batch-size` | `2500` |
| `enrichment.berlin-open-data.file-path` | `./data/berlinOpenData/baustellen_sperrungen.json` |
| `pipeline.enrichment.berlin-open-data.delay-ms` | `60000` |

---

## OSM Attributes — Ohsome API

**Source:** `https://api.ohsome.org/v1`  

Queries historical OpenStreetMap tag values at the precise timestamp of each event, so the infrastructure state at the time of the ride is captured (not current state). This matters for infrastructure that has changed — e.g., a cycle path added after the rides were recorded.

**OSM attributes fetched per event:**

| Tag | Description |
|---|---|
| `surface` | Road surface material (asphalt, cobblestone, etc.) |
| `smoothness` | Surface quality (excellent → very_horrible) |
| `cycleway` | Cycleway type (lane, track, shared, etc.) |
| `cycleway:left` / `cycleway:right` | Side-specific cycleway presence |
| `cycleway:width` | Width in meters |
| `lit` | Street lighting (yes/no) |
| `highway` | Road classification (primary, residential, etc.) |
| `maxspeed` | Posted speed limit |

**Rate limiting:** 500 ms between API calls.

| Property | Default |
|---|---|
| `pipeline.enrichment.ohsome.batch-size` | `250` |
| `pipeline.enrichment.ohsome.delay-between-calls-ms` | `500` |
| `pipeline.enrichment.ohsome.delay-ms` | `60000` |
