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
| `pipeline.enrichment.weather.enabled` | `true` |
| `pipeline.enrichment.weather.batch-size` | `100` |
| `pipeline.enrichment.weather.delay-ms` | `60000` |
| `pipeline.enrichment.weather.delay-between-calls-ms` | `150` |

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
| `pipeline.enrichment.traffic.enabled` | `true` |
| `pipeline.enrichment.traffic.batch-size` | `2500` |
| `enrichment.traffic.match-radius-meters` | `75` |
| `pipeline.enrichment.traffic.delay-ms` | `60000` |

---

## VIZ — Road Closures & Construction Sites

**Source:** VIZ (Verkehrsinformationszentrale Berlin) live dataset `https://api.viz.berlin.de/daten/baustellen_sperrungen_viz.json` (dataset description: [Baustellen, Sperrungen und sonstige Störungen von besonderem verkehrlichem Interesse](https://daten.berlin.de/datensaetze/baustellen-sperrungen-und-sonstige-storungen-von-besonderem-verkehrlichem-interesse))

Downloads the VIZ JSON containing road closures and construction zones automatically at startup. For each segment event, a spatial check determines whether the event's location overlaps with any active closure at the time of the ride.

Events near a closure are flagged with `ExternalFactorType.ROAD_CLOSURE`. This helps distinguish infrastructure avoidance from temporary disruptions.

Each successful download refreshes a local cache file; if the API is unreachable at startup, the cached copy from the previous run is used. If neither is available, road-closure enrichment is disabled for that run.

Private historical VIZ snapshots can be placed below
`./data/berlinOpenData/historical/2024/` and
`./data/berlinOpenData/historical/2025/`. They remain local because the complete
`data/` directory is Git-ignored. At startup the importer reads the legacy
ISO-8859-1 GeoJSON files, collapses repeated daily revisions by source id and
validity start, and inserts the normalized occurrences before importing the live
feed. A later `Beendet` revision closes the preceding occurrence at its `tstore`
timestamp; it does not remove the historical closure.

The available snapshots cover 2024-01-01 through 2025-07-03. There is no snapshot
for 2024-09-17.

The feed also has its own subtype mapping (`RoadClosureImportService.mapSubtype`) that assigns `ExternalFactorType.CONSTRUCTION`, `ROAD_CLOSURE`, `EVENT`, or `HAZARD`/`INCIDENT` per entry — see the `road_closures` entity in [data-model.md](data-model.md) and the factor-type table below.

Property names here use `berlin-open-data` for historical reasons — they configure this VIZ closures feed specifically, not a generic "Berlin Open Data" source.

| Property | Default |
|---|---|
| `pipeline.enrichment.berlin-open-data.enabled` | `true` |
| `pipeline.enrichment.berlin-open-data.batch-size` | `2500` |
| `enrichment.berlin-open-data.url` | `https://api.viz.berlin.de/daten/baustellen_sperrungen_viz.json` |
| `enrichment.berlin-open-data.cache-file` | `./data/berlinOpenData/cache/baustellen_sperrungen_viz.json` |
| `pipeline.enrichment.berlin-open-data.delay-ms` | `60000` |
| `enrichment.road-closures.refresh-ms` | `86400000` (import/refresh cadence, separate from the enrichment batch scheduler above) |

---

## OSM Attributes — Ohsome API

**Source:** `https://api.ohsome.org/v1`  

Queries historical OpenStreetMap tag values at the precise timestamp of each event, so the infrastructure state at the time of the ride is captured (not current state). This matters for infrastructure that has changed — e.g., a cycle path added after the rides were recorded.

**OSM attributes fetched per event:**

Deprecated `cycleway=opposite*` values are normalized to their modern `oneway:bicycle`/`cycleway` equivalents before extraction.

| Tag | Description |
|---|---|
| `surface` | Road surface material (asphalt, cobblestone, etc.) — stored as-is on the segment |
| `smoothness` | Surface quality (excellent → very_horrible) |
| `highway` | Road classification (primary, residential, etc.) |
| `lit` | Street lighting (yes/no) |
| `cycleway:both` / `cycleway:right` / `cycleway:left` / `cycleway` | Cycleway presence, checked in that priority order; the winning tag's value is mapped to `cyclewayType` and its side to `cyclewayLocation` |
| `cycleway(:*):surface` | Cycleway-specific surface for the matched side; falls back to the road `surface` tag if absent |
| `cycleway(:*):width` | Cycleway width in meters for the matched side |
| `oneway:bicycle` | Whether cyclists must follow the road's one-way direction |

Note: `maxspeed` is **not** fetched or stored despite earlier versions of this doc — there is no corresponding field on `SegmentEvent`.

**Rate limiting:** 500 ms between API calls.

| Property | Default |
|---|---|
| `pipeline.enrichment.ohsome.enabled` | `true` |
| `pipeline.enrichment.ohsome.batch-size` | `250` |
| `pipeline.enrichment.ohsome.delay-between-calls-ms` | `500` |
| `pipeline.enrichment.ohsome.delay-ms` | `60000` |

---

## `SegmentExternalFactor.factorType` by producer

`segment_external_factors` (see [data-model.md](data-model.md)) has one `factorType` enum shared across sources; only two of the four enrichment jobs above actually write `SegmentExternalFactor` rows:

| `factorType` | Written by |
|---|---|
| `WEATHER` | Weather (Open-Meteo) — always this value |
| `CONSTRUCTION`, `ROAD_CLOSURE`, `EVENT`, `HAZARD`, `INCIDENT` | VIZ Road Closures, via the feed's `subtype` → `factorType` mapping on `RoadClosure` (see the `road_closures` entity in [data-model.md](data-model.md)) |
| `TRAFFIC` | Not currently written by any source. Traffic measurements are stored directly on the `segment_events` traffic fields (see [data-model.md](data-model.md)), not as a `SegmentExternalFactor` — this enum value is reserved but unused today. |

The Ohsome (OSM Attributes) enrichment does not write `SegmentExternalFactor` rows at all; it writes directly onto `segment_events`' OSM infrastructure fields.
