# External Data Enrichments

Each segment event (avoidance or preference) can be enriched with contextual data from four sources. Enrichment runs as independent scheduled jobs. Weather, traffic, and Ohsome attributes are stored directly on `SegmentEvent`; road-closure results create `SegmentExternalFactor` records.

The event-driven enrichment jobs generally share the same status pattern:
1. Claim a batch of events with `enrichment_status = PENDING`
2. Fetch or compute the relevant data
3. Persist the result and mark the source-specific status `DONE` (or `ERROR`)

Ohsome uses a specialized segment/month batch described below so that one cached historical snapshot and one deterministic spatial match can serve every event on the same segment in the same month.

---

## Weather — Open-Meteo Archive API

**Source:** `https://archive-api.open-meteo.com/v1/archive`

Fetches hourly historical weather data for the location and timestamp of each event. Segment centroids are permanently assigned to a 0.1° grid, stored as integer latitude/longitude tenths. A usable centroid is accepted even when GraphHopper produced a zero-length LineString with duplicate coordinates. Pending work is grouped by grid location and UTC year; each request covers up to five locations and the minimum/maximum UTC dates needed by the claimed events.

Requests use `timezone=GMT` and `timeformat=unixtime` and return:

- Temperature (°C)
- Precipitation (mm)
- Wind speed (km/h) and direction (°)
- WMO weather code (rain, snow, fog, etc.)

The normalized hourly response is cached in `open_meteo_hourly_weather` by grid location and UTC hour. Cached rows are reused across segments, events, retries, and restarts. Weather is written only to `segment_events`; this pipeline does not create `WEATHER` `SegmentExternalFactor` rows.

**Wind exposure classification** is derived from the smallest angle between the meteorological wind-from direction and the cyclist's bearing:

| Angle | Classification |
|---|---|
| 0–45° (inclusive) | `HEADWIND` |
| >45° and <135° | `CROSSWIND` |
| 135–180° (inclusive) | `TAILWIND` |

The relative angle and classification are both null when either bearing or wind direction is unavailable.

Database work is independently bounded by `event-batch-size`. Each claim receives a UUID, and no claim, weather update, error finalization, or release can affect more than that number of events. The cache is checked by grid/year before event rows are scanned. Cached hours finalize matching events; unresolved hours are then requested and cached. Transient API failures and database query timeouts release only the affected claim to `PENDING` and apply exponential backoff. Non-retryable 4xx and structurally invalid successful responses mark unresolved claimed events `ERROR`; a valid response missing an individual event hour marks only that event `ERROR`.

| Property | Default |
|---|---|
| `pipeline.enrichment.weather.enabled` | `true` |
| `pipeline.enrichment.weather.batch-size` | `5` grid/year locations |
| `pipeline.enrichment.weather.event-batch-size` | `25000` events |
| `pipeline.enrichment.weather.delay-ms` | `60000` |
| `pipeline.enrichment.weather.initial-retry-delay` | `PT1M` |
| `pipeline.enrichment.weather.max-retry-delay` | `PT30M` |
| `pipeline.enrichment.weather.connect-timeout` | `PT30S` |
| `pipeline.enrichment.weather.request-timeout` | `PT2M` |

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

Downloads the VIZ JSON containing road closures and construction zones automatically at startup. For each segment event, a spatial check determines whether the event's location overlaps with any disruption active at the exact event timestamp. Validity bounds are inclusive.

Matching construction, closure, event, hazard, and incident records are persisted as segment external factors. `GET /api/segments/{id}/events` attaches every matching factor to the corresponding event through its `roadDisruptions` array. This helps distinguish infrastructure avoidance from temporary disruptions.

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

Existing databases whose events were already marked as enriched before the
historical archive was loaded require a one-time requeue:

```sql
UPDATE segment_events
SET berlin_open_data_enriched = false,
    berlin_open_data_processing_status = 'PENDING';
```

The scheduler then rebuilds the missing segment-factor correlations in bounded
batches. Existing factors are retained and deduplicated.

---

## OSM Attributes — Ohsome API

**Source:** `https://api.heigit.org/ohsome-api-staging/v2/extraction/features.parquet`

The backend downloads 73 historical GeoParquet snapshots: one at `00:00:00Z` on the first day of every month from January 2019 through January 2025. Each request covers Berlin plus an approximately 10 km buffer (`[12.94, 52.24, 13.91, 52.77]`), uses the filter `type:way and highway=*`, and sets `clip=false` so that complete intersecting road geometries are retained.

This is a deliberate monthly approximation. An event is matched against the snapshot at the start of its UTC calendar month, rather than against an exact event-time API response. The assigned tags can therefore lag an OSM edit made later in that month. Events outside the configured time range or area are finalized without OSM attributes instead of being assigned a misleading snapshot.

### Snapshot cache

Snapshots are stored below `./data/ohsome/v2/berlin-10km-monthly-v1/`, which is excluded from Git and mounted at `/app/data` in Docker. `manifest.json` records the dataset parameters and the checksum, size, retrieval time, and timestamp of each `snapshots/YYYY-MM.parquet` file.

Before claiming Ohsome events, the backend validates the complete cache. Missing snapshots are fetched sequentially with the HeiGIT key from `HEIGIT_API_KEY`, first written as `.parquet.part`, validated and checksummed, and then moved atomically into place. Valid cached files are never fetched again and a complete cache can be used without an API key. There is no Ohsome v1 fallback.

Docker Compose reads `HEIGIT_API_KEY=...` from the repository's `.env` file and passes it into the backend container. When running Spring directly on the host, export the same environment variable before starting the application; Spring does not load the Compose `.env` file itself. The key is never written to the manifest or logs.

### Deterministic segment matching

Ohsome enrichment treats a distinct street-segment/month pair as one work item. The month's road LineStrings are transformed with the GraphHopper segment to EPSG:25833, indexed spatially, and compared using the full segment geometry rather than only its centroid. Normal segments are sampled at most every 5 metres; a candidate must cover at least 80% of those samples within 15 metres and have an undirected local-bearing difference no greater than 45 degrees. Candidates with a conflicting nonblank normalized street name are rejected. Segments shorter than 2 metres use a stricter 5-metre distance rule without an unreliable bearing test. The winner is selected deterministically by coverage, name compatibility, median distance, bearing difference, and OSM ID. Near-ties remain unmatched instead of receiving an unreliable road assignment.

All events for the selected segment/month pair receive the same result in one database batch:

| Outcome | Status | `ohsomeEnriched` |
|---|---|---|
| Reliable feature match | `DONE` | `true` |
| No reliable or unambiguous match | `DONE` | `false` |
| Outside the supported time range or area | `DONE` | `false` |
| Unexpected processing failure | `ERROR` | `false` |

A missing or invalid snapshot prevents any supported Ohsome work from being claimed until the complete 73-file cache is ready. This avoids mixing partial snapshot datasets and leaves the work `PENDING` for a later retry.

The selected OSM way ID and match score are intentionally not added to the database schema. Reproducibility comes from the immutable snapshot manifest and deterministic matching rules; per-event match auditing would require a separate schema extension.

**OSM attributes stored for each matched event:**

Deprecated `cycleway=opposite*` values are normalized to their modern `oneway:bicycle`/`cycleway` equivalents before extraction.

| Tag | Description |
|---|---|
| `surface` | Road surface material (asphalt, cobblestone, etc.) — stored as-is on the segment event |
| `smoothness` | Surface quality (excellent → very_horrible) |
| `highway` | Road classification (primary, residential, etc.) |
| `lit` | Street lighting (yes/no) |
| `cycleway:both` / `cycleway:right` / `cycleway:left` / `cycleway` | Cycleway presence, checked in that priority order; the winning tag's value is mapped to `cyclewayType` and its side to `cyclewayLocation` |
| `cycleway(:*):surface` | Cycleway-specific surface for the matched side; falls back to the road `surface` tag if absent |
| `cycleway(:*):width` | Cycleway width in meters for the matched side |
| `oneway:bicycle` | Whether cyclists must follow the road's one-way direction |

Note: `maxspeed` is **not** fetched or stored despite earlier versions of this doc — there is no corresponding field on `SegmentEvent`.

| Property | Default |
|---|---|
| `pipeline.enrichment.ohsome.enabled` | `true` |
| `pipeline.enrichment.ohsome.batch-size` | `5000` segment/month pairs |
| `pipeline.enrichment.ohsome.delay-ms` | `60000` |
| `ohsome.v2.base-url` | `https://api.heigit.org/ohsome-api-staging/v2` |
| `ohsome.v2.api-key` | `${HEIGIT_API_KEY:}` |
| `ohsome.v2.cache-path` | `./data/ohsome/v2/berlin-10km-monthly-v1` |
| `ohsome.v2.start-month` / `end-month` | `2019-01` / `2025-01` |
| `ohsome.v2.bbox` | `12.94,52.24,13.91,52.77` |
| `ohsome.v2.filter` / `clip` | `type:way and highway=*` / `false` |
| `ohsome.v2.download-interval` | `PT60S` |
| `ohsome.v2.max-retries` | `3` |

---

## `SegmentExternalFactor.factorType` by producer

`segment_external_factors` (see [data-model.md](data-model.md)) has one `factorType` enum shared across sources. Only the road-disruption enrichment currently writes these rows:

| `factorType` | Written by |
|---|---|
| `WEATHER` | Reserved for compatibility; no longer written by weather enrichment |
| `CONSTRUCTION`, `ROAD_CLOSURE`, `EVENT`, `HAZARD`, `INCIDENT` | VIZ Road Closures, via the feed's `subtype` → `factorType` mapping on `RoadClosure` (see the `road_closures` entity in [data-model.md](data-model.md)) |
| `TRAFFIC` | Not currently written by any source. Traffic measurements are stored directly on the `segment_events` traffic fields (see [data-model.md](data-model.md)), not as a `SegmentExternalFactor` — this enum value is reserved but unused today. |

The Ohsome (OSM Attributes) enrichment does not write `SegmentExternalFactor` rows at all; it writes directly onto `segment_events`' OSM infrastructure fields.
