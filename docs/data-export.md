# Data Export

Two mechanisms expose the processed data: a **REST API** for querying individual segments and analytics, and **vector tiles** for rendering large datasets on an interactive map.

---

## REST API

The API serves data for frontends querying specific segments or overview statistics.

CORS is configured via `app.cors.allowed-origins` (defaults: `localhost:4200`, `:25080`, `:8080`).

### Segment endpoints

**`GET /api/segments`**  
Returns segments ranked by avoidance ratio, with optional filtering. Used to surface the most problematic infrastructure.

Params: `minAvoidanceRatio` (default `0.2`), `minSampleSize` (default `10`), `limit` (default `50`, clamped to `[1, 10000]`), `from`/`to` (epoch ms), `rideIntent`, `trafficCondition`, `enrichmentFilters` (repeatable, one or more of `TRAFFIC_ENRICHED`, `WEATHER_ENRICHED`, `OHSOME_ENRICHED`, `TRAFFIC_MEASURED`). The sample size and `totalObservationCount` are `usageCount + avoidanceCount`; preference observations are already included in usage and are not added again. When any event-level filter (`from`/`to`/`rideIntent`/`trafficCondition`/`enrichmentFilters`) is set, a segment qualifies if **at least one** of its events matches *all* of the active filters at once — filters are ANDed within an event, but a segment needs only one matching event, not all of them.

```json
{
    "id": 27074352,
    "streetName": "Mentzelstraße",
    "usageCount": 0,
    "avoidanceCount": 14,
    "avoidanceRatio": 1.0,
    "preferenceCount": 0,
    "preferenceRatio": null,
    "totalObservationCount": 14,
    "gradientPercent": 0.0,
    "traffic": {
        "segmentId": 27074352,
        "trafficEnrichedEventCount": 7,
        "trafficMeasuredEventCount": 0,
        "averageTrafficVolumeKfz": null,
        "averageTrafficSpeedKfz": null,
        "averageTrafficVolumePkw": null,
        "averageTrafficSpeedPkw": null,
        "averageTrafficVolumeLkw": null,
        "averageTrafficSpeedLkw": null,
        "dominantTrafficCondition": null
    },
    "incidentCount": 0,
    "incidentBreakdown": [],
    "externalFactors": [],
    "geometry": null
}
```

**`GET /api/segments/geojson`**  
Returns segments as a GeoJSON FeatureCollection, supporting spatial and metric filters (bounding box, minimum observation count, etc.). Used by the frontend map for on-demand queries.

Params: `minAvoidanceRatio` / `minPreferenceRatio` (default `0.2` each), `minSampleSize` (default `1`), `bbox` (optional `"minLon,minLat,maxLon,maxLat"`; a malformed or inverted bbox returns `400`), `limit` (default `1000`, clamped to `[1, 10000]`), and the same `from`/`to`/`rideIntent`/`trafficCondition`/`enrichmentFilters` event-level filters as `GET /api/segments`, with the same "any matching event qualifies the segment" semantics.

```json
{
    "type": "FeatureCollection",
    "features": [
        {
            "type": "Feature",
            "geometry": {
                "type": "LineString",
                "coordinates": [
                    [13.3578304, 52.5008567],
                    [13.3578701, 52.5008494]
                ]
            },
            "properties": {
                "id": 26540932,
                "streetName": "An der Apostelkirche",
                "usageCount": 0,
                "avoidanceCount": 49,
                "avoidanceRatio": 1.0,
                "preferenceCount": 0,
                "preferenceRatio": null,
                "totalObservationCount": 49,
                "gradientPercent": 0.0,
                "traffic": {
                    "segmentId": 26540932,
                    "trafficEnrichedEventCount": 31,
                    "trafficMeasuredEventCount": 0,
                    "averageTrafficVolumeKfz": null,
                    "averageTrafficSpeedKfz": null,
                    "dominantTrafficCondition": null
                }
            }
        }
    ]
}
```

**`GET /api/segments/{id}`**  
Returns a single segment with usage/avoidance/preference counts and ratios, gradient, nearby incidents, and aggregated traffic statistics from all enriched events.

```json
{
    "id": 27341725,
    "streetName": "Torstraße",
    "usageCount": 0,
    "avoidanceCount": 15,
    "avoidanceRatio": 1.0,
    "preferenceCount": 0,
    "preferenceRatio": null,
    "totalObservationCount": 15,
    "gradientPercent": 42.77,
    "traffic": {
        "segmentId": 27341725,
        "trafficEnrichedEventCount": 12,
        "trafficMeasuredEventCount": 12,
        "averageTrafficVolumeKfz": 311.08,
        "averageTrafficSpeedKfz": 38.83,
        "averageTrafficVolumePkw": 298.67,
        "averageTrafficSpeedPkw": 39.0,
        "averageTrafficVolumeLkw": 12.42,
        "averageTrafficSpeedLkw": 39.5,
        "dominantTrafficCondition": null
    },
    "incidentCount": 1,
    "incidentBreakdown": [
        { "incidentType": "TAILGATING", "count": 1 }
    ],
    "externalFactors": [],
    "geometry": {
        "type": "LineString",
        "coordinates": [
            [13.4101838, 52.5287244],
            [13.4100022, 52.5287526]
        ]
    }
}
```

**`GET /api/segments/{id}/events`**  
Returns individual `SegmentEvent` records — each avoidance or preference observation with its enrichment data. `404` if the segment doesn't exist.

Params: `eventType`, `from`/`to`, `rideIntent`, `trafficCondition`, `enrichmentFilters` (same values as `GET /api/segments`), `limit` (default `100`, clamped to `[1, 1000]`). Always returns a single page starting at offset 0 — there is no cursor/offset parameter, so a segment with more than 1000 matching events cannot be paged through this endpoint.

Example of a fully enriched event (weather + traffic):

```json
{
    "id": "43b763f3-0056-4c1c-9024-3cd0a0f1303d",
    "segmentId": 27424450,
    "rideId": "532a940e-d623-4eba-abe4-d9b72b1d54a0",
    "eventType": "AVOIDANCE",
    "eventTimestamp": 1645804575000,
    "dayOfWeek": "FRIDAY",
    "hourOfDay": 16,
    "rideIntent": "COMMUTE",
    "bikeType": "CITY_TREKKING_BIKE",
    "pathBearingDegrees": 352.50,
    "highway": null,
    "surface": null,
    "smoothness": null,
    "lit": null,
    "cyclewayType": null,
    "cyclewayLocation": null,
    "cyclewaySurface": null,
    "cyclewayWidth": null,
    "bicycleOneway": null,
    "weatherEnriched": true,
    "temperature2m": 5.0,
    "precipitation": 0.1,
    "windSpeed10m": 25.5,
    "windDirection10m": 239.0,
    "weatherCode": 51,
    "relativeWindAngleDegrees": 113.50,
    "windExposure": "CROSSWIND",
    "ohsomeEnriched": false,
    "trafficEnriched": true,
    "trafficEnrichmentStatus": "ENRICHED",
    "trafficCondition": "MODERATE",
    "trafficSourceType": "OLD_DETECTOR",
    "trafficVolumeKfz": 595,
    "trafficSpeedKfz": 48.0,
    "trafficVolumePkw": 574,
    "trafficSpeedPkw": 48.0,
    "trafficVolumeLkw": 21,
    "trafficSpeedLkw": 49.0
}
```

**`GET /api/segments/{id}/factors`**  
Returns `SegmentExternalFactor` records — segment-level conditions with validity time windows. `404` if the segment doesn't exist.

Params: `factorType` (optional), `from`/`to` (optional). The three are mutually exclusive in effect, checked in this order: if **both** `from` and `to` are set, returns factors whose validity window overlaps `[from, to]`; else if `factorType` is set, returns factors of that type only; else returns all factors for the segment. Setting only one of `from`/`to` (not both) does not filter by time at all — it silently falls through to the `factorType`/all-factors branch.

```json
[
    {
        "factorType": "WEATHER",
        "source": "open-meteo",
        "validFrom": 1646024400000,
        "validTo": 1646028000000,
        "metadata": {
            "weather_code": 3,
            "precipitation": 0.0,
            "temperature_2m": -1.6,
            "wind_speed_10m": 6.5,
            "wind_direction_10m": 124.0
        }
    }
]
```

### Map layer endpoints

These three endpoints serve reference/overlay data as plain JSON. They are independent of the PMTiles vector-tile pipeline (see [Vector Tiles](#vector-tiles) below) — the frontend map renders them as separate GeoJSON-ish overlay layers.

**`GET /api/road-closures?from=&to=`**  
Road closures and construction sites from the VIZ Berlin Baustellen/Sperrungen feed (`road_closures` table). The feed is a live snapshot: imports upsert by feed ID and never delete, so this table accumulates history rather than reflecting only current closures. `from`/`to` are optional epoch-ms bounds; a closure is returned when its validity window overlaps them (open-ended closures — no `validTo` — overlap everything after their start). This is a different, simpler mechanism than the VIZ enrichment described in [external-enrichments.md](external-enrichments.md): the enrichment attaches `SegmentExternalFactor` records to nearby events, while this endpoint exposes the raw closure records for map display.

```json
[
    {
        "id": "8f6b1e2a-...",
        "factorType": "CONSTRUCTION",
        "severity": "DIRECTIONAL_CLOSURE",
        "direction": "beide Richtungen",
        "street": "Torstraße",
        "section": "zwischen Rosenthaler Straße und Tucholskystraße",
        "content": "Fahrbahnerneuerung",
        "validFrom": 1735689600000,
        "validTo": null,
        "lon": 13.4013,
        "lat": 52.5289,
        "lines": [[[13.4010, 52.5288], [13.4016, 52.5290]]]
    }
]
```

**`GET /api/incidents/near-misses?from=&to=`**  
Self-reported SimRa incidents flagged `scary=true` with a non-null GPS location — a proxy layer for near-misses. `from`/`to` are optional epoch-ms bounds on the incident timestamp, matching the same convention as the segment endpoints' time filters.

```json
[
    {
        "id": "1a2b3c4d-...",
        "lon": 13.3908,
        "lat": 52.5170,
        "timestamp": 1645804575000,
        "incidentType": "CLOSE_PASS",
        "scary": true,
        "description": "Car overtook too closely",
        "involvedParticipants": ["CAR"]
    }
]
```

**`GET /api/traffic/detectors`**  
All Berlin induction-loop traffic detectors from the Stammdaten import, with their WGS84 positions, plus the configured enrichment match radius so the frontend can render it. Static reference data — no time filtering.

```json
{
    "matchRadiusMeters": 75.0,
    "detectors": [
        {
            "detName": "TE501",
            "detNameNeu": "TE501a",
            "mqName": "MQ501",
            "street": "Torstraße",
            "position": "Höhe Rosenthaler Platz",
            "positionDetail": "stadtauswärts",
            "direction": "Ost",
            "lane": "1",
            "activeFrom": "2015-01-01",
            "activeTo": null,
            "deinstalled": false,
            "lon": 13.4013,
            "lat": 52.5289
        }
    ]
}
```

### Analytics endpoints

**`GET /api/analytics/summary`**  
Overall counts: total rides, processed rides, total events, enrichment coverage per source.

```json
{
    "totalRides": 4753,
    "rideStatusCounts": {
        "PENDING": 2755,
        "ANALYZING": 8,
        "PROCESSED": 1980,
        "SKIPPED": 0,
        "ERROR": 10
    },
    "routeComparisonTypeCounts": {
        "EQUIVALENT_ROUTE": 900,
        "LOCAL_DETOUR": 496,
        "CORRIDOR_ALTERNATIVE": 584
    },
    "totalSegments": 233694,
    "observedSegments": 222964,
    "totalSegmentEvents": 226333,
    "earliestEventTimestamp": 1043747989999,
    "latestEventTimestamp": 1650823968999,
    "segmentEventTypeCounts": {
        "AVOIDANCE": 85709,
        "PREFERENCE": 140624
    },
    "weatherEnrichedEvents": 8839,
    "ohsomeEnrichedEvents": 0,
    "berlinOpenDataEnrichedEvents": 187049,
    "trafficEnrichedEvents": 150575,
    "trafficMeasuredEvents": 4263
}
```

**`GET /api/analytics/pipeline-status`**  
Per-stage pipeline health: ride processing status breakdown and per-enrichment-source pending/done counts.

```json
{
    "rideStatusCounts": {
        "PENDING": 2755,
        "ANALYZING": 8,
        "PROCESSED": 1980,
        "SKIPPED": 0,
        "ERROR": 10
    },
    "totalSegmentEvents": 226333,
    "weatherStatusCounts": {
        "PENDING": 217494,
        "PROCESSING": 0,
        "DONE": 8839,
        "ERROR": 0
    },
    "berlinOpenDataStatusCounts": {
        "PENDING": 29284,
        "PROCESSING": 10000,
        "DONE": 187049,
        "ERROR": 0
    },
    "ohsomeStatusCounts": {
        "PENDING": 226083,
        "PROCESSING": 0,
        "DONE": 0,
        "ERROR": 250
    },
    "trafficStatusCounts": {
        "PENDING": 63494,
        "PROCESSING": 12500,
        "DONE": 150575,
        "ERROR": 0
    }
}
```

**`GET /api/analytics/route-comparisons/calibration.csv`**  
Downloads a deterministic, class-balanced sample for offline route-comparison review. Optional `from` and `to` parameters filter by ride start time; `perType` controls the number of rides per baseline class and is constrained to 1–200. The CSV contains both route geometries as WKT, the continuous comparison metrics, GPS accuracy context, and empty `review_label` and `review_notes` columns. This export is for manual calibration and is not queried by the frontend.

Permitted manual labels are `EQUIVALENT_ROUTE`, `LOCAL_DETOUR`, `CORRIDOR_ALTERNATIVE`, and `AMBIGUOUS_OR_INVALID`. Labels remain in the downloaded file; the endpoint does not write them back to the application.

**`GET /api/analytics/distribution?dimension=HOUR_OF_DAY`**  
Event distribution broken down by a dimension. Returns one entry per dimension value, sorted by total event count. Also accepts `from`, `to`, `eventType`, `rideIntent`, `trafficCondition`, `enrichmentFilters`, and `limit` (default `50`, clamped to `[1, 200]`).

Supported `dimension` values: `EVENT_TYPE`, `HOUR_OF_DAY`, `DAY_OF_WEEK`, `RIDE_INTENT`, `WIND_EXPOSURE`, `CYCLEWAY_TYPE`, `CYCLEWAY_LOCATION`, `HIGHWAY`, `SURFACE`, `SMOOTHNESS`, `LIT`, `WEATHER_CODE`, `TRAFFIC_CONDITION`, plus six synthetic bucket dimensions computed inline by the query rather than stored as columns: `PRECIPITATION_BUCKET`, `TEMPERATURE_BUCKET`, `WIND_SPEED_BUCKET`, `GRADIENT_BUCKET`, `TRAFFIC_VOLUME_BUCKET`, `TRAFFIC_SPEED_BUCKET`. Bucket edges are fixed in the query and not caller-configurable; null values group under the literal string `"UNKNOWN"`.

```json
[
    {
        "dimension": "HOUR_OF_DAY",
        "value": "8",
        "totalCount": 32818,
        "avoidanceCount": 12981,
        "preferenceCount": 19837,
        "avoidanceShare": 0.3955,
        "preferenceShare": 0.6045,
        "averageTemperature2m": 4.50,
        "averagePrecipitation": 0.042,
        "averageWindSpeed10m": 19.87,
        "averageRelativeWindAngleDegrees": 83.95,
        "averageGradientPercent": -0.145,
        "averageTrafficVolumeKfz": 231.89,
        "averageTrafficSpeedKfz": 28.29
    },
    {
        "dimension": "HOUR_OF_DAY",
        "value": "17",
        "totalCount": 25698,
        "avoidanceCount": 9556,
        "preferenceCount": 16142,
        "avoidanceShare": 0.3719,
        "preferenceShare": 0.6281,
        "averageTemperature2m": 13.88,
        "averagePrecipitation": 0.0,
        "averageWindSpeed10m": 5.48,
        "averageRelativeWindAngleDegrees": 65.55,
        "averageGradientPercent": -0.069,
        "averageTrafficVolumeKfz": 295.38,
        "averageTrafficSpeedKfz": 35.04
    }
]
```

**`GET /api/analytics/context`**  
Filter-aware evidence context for the planner analytics tab. Supports `from`, `to`, and `rideIntent`.

```json
{
    "matchingRideCount": 1955,
    "matchingEventCount": 468711,
    "avoidanceEventCount": 171423,
    "preferenceEventCount": 297288,
    "earliestEventTimestamp": 1615463561000,
    "latestEventTimestamp": 1664532378000
}
```

**`GET /api/analytics/corridors?rank=AVOIDANCE`**  
Ranks spatially connected, same-named street corridors by distinct rides carrying the requested signal. Same-named segments are clustered spatially (`ST_ClusterDBSCAN`, 75 m gap tolerance), so a street with a physical gap in coverage becomes two separate corridors. A ride contributes once per corridor, independent of the number of affected GraphHopper edges. Parameters: `rank` (`AVOIDANCE` or `PREFERENCE`, default `AVOIDANCE`), `limit` (default `8`, clamped to `[1, 50]`), `minRideCount` (default `5`, floored at `1` — filters on ride count, not event count), `from`, `to`, `rideIntent`.

`scaryIncidentCount` comes from a separate spatial join against `incidents` within 25 metres of the corridor's unioned geometry (filtered by `scary=true`, the same time window, and the incident's own ride intent) — it is not derived from the corridor's avoidance/preference events. `topSegmentId` is the statistical mode of segment IDs among the corridor's events of the ranked type (the single most-touched segment); `segmentIds` lists every segment ID in the corridor.

```json
[
    {
        "streetName": "Torstraße",
        "avoidanceRideCount": 214,
        "preferenceRideCount": 12,
        "avoidanceEventCount": 631,
        "preferenceEventCount": 18,
        "segmentCount": 9,
        "scaryIncidentCount": 3,
        "minLon": 13.3908,
        "minLat": 52.5286,
        "maxLon": 13.4110,
        "maxLat": 52.5299,
        "topSegmentId": 27424450,
        "segmentIds": [27424448, 27424449, 27424450, 27424451]
    }
]
```

**`GET /api/analytics/corridor-geometry?streetName=&minLon=&minLat=&maxLon=&maxLat=`**  
Resolves the exact GraphHopper edge geometry for a corridor, for map highlighting. Unlike `/corridors`, this reads directly from GraphHopper's in-memory routing graph rather than the database. All five params are required.

Matching is case/whitespace-insensitive on the edge's OSM name tag. The query bbox is buffered by 75 m (the same constant used by the DB-side corridor clustering) to gather candidate edges from GraphHopper's location index, but the final intersection test uses the *unbuffered* bbox — an edge just outside the requested bbox but within the 75 m buffer is only included if it also crosses into the exact bbox. The bbox span is capped at 0.25° (~28 km at Berlin's latitude) in either axis; a larger span returns `400 Bad Request` ("corridor bounds are too large"), as does a malformed/inverted bbox ("corridor bounds are invalid") or non-finite coordinates ("corridor bounds must be finite").

```json
{
    "streetName": "Torstraße",
    "segmentIds": [27424448, 27424449, 27424450, 27424451],
    "geometry": {
        "type": "MultiLineString",
        "coordinates": [
            [[13.3908, 52.5286], [13.3912, 52.5287]],
            [[13.3912, 52.5287], [13.3916, 52.5288]]
        ]
    }
}
```

**`GET /api/analytics/infrastructure-signals?dimension=SURFACE`**  
Compares historical OSM infrastructure categories with the filtered avoidance-signal baseline. Supported dimensions are `SURFACE`, `SMOOTHNESS`, `CYCLEWAY_TYPE`, and `HIGHWAY`; optional parameters are `limit`, `minRideCount`, `from`, `to`, and `rideIntent`. The response includes known-attribute coverage, distinct ride-signal counts, and percentage-point difference from baseline. These are descriptive associations, not exposure-normalized avoidance probabilities.

### Tile management endpoints

**`POST /api/admin/tiles/rebuild`**  
Triggers a tile rebuild asynchronously. Returns `202 Accepted` if started, `409 Conflict` if a build is already in progress.

**`GET /api/tiles/status`**  
Returns the current tile build state (`IDLE`, `RUNNING`, `FAILED`), the timestamp of the last successful build, and the last error message if any.

```json
{
    "state": "IDLE",
    "generatedAt": 1783100286763,
    "lastError": null
}
```

**`GET /api/tiles/segments.pmtiles`**  
Serves the PMTiles archive file. Supports HTTP Range requests (`206 Partial Content`), which the PMTiles client library uses to fetch individual tiles from the archive without downloading the full file.

---

## Vector Tiles

### Why not just query the API per viewport?

One approach would be to call `/api/segments/geojson?bbox=...` whenever the user pans or zooms the map. This breaks down at scale:

- Querying thousands of segments per viewport change is expensive on the database
- Transferring full GeoJSON geometries for every request is wasteful — geometries don't change
- Low zoom levels need simplified/aggregated data, not raw segment-level detail

Vector tiles solve all three problems at once: data is pre-processed into a compact binary format, served statically, and the map client fetches only the tiles it needs at the appropriate zoom level.

### What is PMTiles?

[PMTiles](https://protomaps.com/docs/pmtiles) is a single-file archive format for vector tiles. Unlike a tile server that generates tiles on demand, or a directory of thousands of individual tile files, PMTiles packs all tiles into one file with an index structure that allows the client to fetch any individual tile using HTTP Range requests — no tile server process required.

The archive is served directly from `/api/tiles/segments.pmtiles`. The `pmtiles` JavaScript client issues range requests to fetch only the tiles visible in the current viewport.

### What is Tippecanoe?

[Tippecanoe](https://github.com/felt/tippecanoe) is a command-line tool that converts GeoJSON/GeoJSONSeq features into a vector tile archive. It handles:

- **Zoom level assignment** — features appear at appropriate zoom levels based on their size and importance
- **Simplification** — geometries are simplified at lower zoom levels to reduce tile size
- **Dropping and coalescing** — when too many features fall in a tile, lower-priority ones are dropped or merged with neighbors to keep tile size manageable
- **Projection** — coordinates are converted to tile coordinates

### Two-layer architecture

The tile build produces a single PMTiles archive with two layers at disjoint zoom ranges, built from the **same** per-segment GeoJSON export (`TileExportRepository.exportSegmentFeatures`) — there is no separate street-grouped query:

| Layer | Zoom range | Content | Purpose |
|---|---|---|---|
| `streets` | 6–12 | Individual segments, display-only (not clickable) | City-level overview — shows which streets tend to be avoided |
| `segments` | 13–14 | Individual segments, full metrics, clickable | Detail zoom — exact edge-level data |

The two ranges are **disjoint by design** — see the tile-pipeline constraints in CLAUDE.md for why identical features in overlapping zoom ranges of both layers previously caused `--drop-densest-as-needed` to empty one layer at some zooms. The frontend switches which layer it renders exactly at z13. Each layer is a separate `tippecanoe` invocation over the same input file with `--drop-densest-as-needed --coalesce-densest-as-needed`, which is what keeps the low-zoom `streets` layer visually manageable — features aren't merged by street name in SQL, tippecanoe drops/coalesces the densest ones per tile as needed to fit the size budget.

### The `balance` signal

Both layers include a `balance` property computed per feature, using additive smoothing (mirrors the frontend's `eventBalance()`):

```
total = avoidanceCount + preferenceCount
balance = total > 0
    ? (preferenceCount - avoidanceCount) / (total + BALANCE_PRIOR_STRENGTH)
    : 0
```

`BALANCE_PRIOR_STRENGTH = 5` acts as five phantom neutral events added to the denominator, so the score grows with both the one-sidedness *and* the amount of evidence, approaching but never reaching ±1 — a segment with 2 avoidance events and 0 preference events scores less extreme than one with 40 avoidance events and 0 preference events, even though both have a 100% avoidance ratio.

The `bucket` property discretizes `balance` into named bins for frontend color mapping (mirrors `eventSignalBucket()`):

| Bucket | Balance threshold |
|---|---|
| `NO_EVENTS` | no avoidance or preference events |
| `AVOIDANCE_EXTREME` | ≤ −0.9 |
| `AVOIDANCE_STRONG` | ≤ −0.6 |
| `AVOIDANCE` | ≤ −0.3 |
| `AVOIDANCE_LIGHT` | ≤ −0.1 |
| `BASELINE` | −0.1 to 0.1 |
| `PREFERENCE_LIGHT` | ≥ 0.1 |
| `PREFERENCE` | ≥ 0.3 |
| `PREFERENCE_STRONG` | ≥ 0.6 |
| `PREFERENCE_EXTREME` | ≥ 0.9 |

Every feature also carries an all-time `eventCount`/`balance`/`bucket` plus per-calendar-year variants (`eventCount_<year>`, `bucket_<year>`, from `MIN_EXPORT_YEAR = 2015` onward) computed with the same formula scoped to that year's events, so the map can filter and color by year without a separate overlay. Years with zero events are omitted from the feature's properties (stripped via `jsonb_strip_nulls`) rather than emitted as zero.

### Tile build process

1. PostGIS streams segment features as newline-delimited GeoJSON (`GeoJSONSeq`) directly to a single file — geometry serialization happens in the database via `ST_AsGeoJSON`, not in Java
2. Tippecanoe runs twice over that same file, once per layer, each with its own `-Z`/`-z` zoom range (and a larger `--maximum-tile-bytes` for `segments`)
3. `tile-join` merges the two single-layer builds into one archive
4. The output file is atomically swapped into place (`ATOMIC_MOVE`) so the running tile server never serves a partially-written file

Tile builds are triggered by `POST /api/admin/tiles/rebuild`, and automatically at most once per `tiles.auto-rebuild-check-ms` interval when the pipelines report data changes (segment counts or enrichment data changed since the last export) — see `TileBuildService.markDataChanged()`/`rebuildIfDataChanged()`. Only one build can run at a time; concurrent triggers return `409` for the manual endpoint, or are silently skipped and retried on the next scheduled check for the automatic one.

### Configuration

| Property | Default | Description |
|---|---|---|
| `tiles.directory` | `./data/tiles` | Output directory for the PMTiles file |
| `tiles.tippecanoe-binary` | `tippecanoe` | Path to the Tippecanoe binary |
| `tiles.tile-join-binary` | `tile-join` | Path to the tile-join binary |
| `tiles.build-timeout-minutes` | `30` | Max time before the build is killed |
| `tiles.auto-rebuild-check-ms` | `300000` | How often to check for pending data changes and auto-trigger a rebuild |
