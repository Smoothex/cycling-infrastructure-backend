# Data Export

Two mechanisms expose the processed data: a **REST API** for querying individual segments and analytics, and **vector tiles** for rendering large datasets on an interactive map.

---

## REST API

The API serves data for frontends querying specific segments or overview statistics.

CORS is configured via `app.cors.allowed-origins` (defaults: `localhost:4200`, `:25080`, `:8080`).

### Segment endpoints

**`GET /api/segments`**  
Returns segments ranked by avoidance ratio, with optional filtering. Used to surface the most problematic infrastructure.

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
Returns individual `SegmentEvent` records — each avoidance or preference observation with its enrichment data. Example of a fully enriched event (weather + traffic):

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
Returns `SegmentExternalFactor` records — segment-level conditions with validity time windows.

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

### Analytics endpoints

**`GET /api/analytics/summary`**  
Overall counts: total rides, processed rides, total events, enrichment coverage per source.

```json
{
    "totalRides": 4753,
    "rideStatusCounts": {
        "PENDING": 2755,
        "ANALYZING": 8,
        "PROCESSED": 1396,
        "ALTERNATIVE_ROUTE": 584,
        "SKIPPED": 0,
        "ERROR": 10
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
        "PROCESSED": 1396,
        "ALTERNATIVE_ROUTE": 584,
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

**`GET /api/analytics/distribution?dimension=HOUR_OF_DAY`**  
Event distribution broken down by a dimension (e.g. `HOUR_OF_DAY`, `DAY_OF_WEEK`, `RIDE_INTENT`, `BIKE_TYPE`, `TRAFFIC_CONDITION`, `CYCLEWAY_TYPE`). Returns one entry per dimension value, sorted by total event count.

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

**`GET /api/analytics/time-series`**  
Avoidance and preference event counts aggregated by month or week over a time range.

```json
[
    {
        "bucketStartEpochMillis": 1640991600000,
        "label": "Jan 2022",
        "totalCount": 9318,
        "avoidanceCount": 3899,
        "preferenceCount": 5419,
        "avoidanceShare": 0.4184,
        "preferenceShare": 0.5816
    },
    {
        "bucketStartEpochMillis": 1643670000000,
        "label": "Feb 2022",
        "totalCount": 55904,
        "avoidanceCount": 22668,
        "preferenceCount": 33236,
        "avoidanceShare": 0.4055,
        "preferenceShare": 0.5945
    },
    {
        "bucketStartEpochMillis": 1646089200000,
        "label": "Mar 2022",
        "totalCount": 97629,
        "avoidanceCount": 36922,
        "preferenceCount": 60707,
        "avoidanceShare": 0.3782,
        "preferenceShare": 0.6218
    }
]
```

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

The tile build produces a single PMTiles archive with two layers at different zoom ranges:

| Layer | Zoom range | Content | Purpose |
|---|---|---|---|
| `streets` | 6–12 | Segments grouped by street name, merged into (Multi)LineStrings, balance averaged across segments | City-level overview — shows which streets tend to be avoided |
| `segments` | 9–14 | Individual segments with full metrics | Detail zoom — clickable, shows exact edge-level data |

The zoom ranges overlap at 9–12: at these levels both layers exist in the tile, and the frontend can choose which to render based on the current interaction state.

**Why group by street name for the overview layer?**  
A street like "Hauptstraße" may consist of dozens of GraphHopper edge IDs. Rendering each edge as a separate feature at zoom 10 creates visual noise and makes the color signal harder to read. Grouping merges them into one feature with an observation-weighted average balance score, giving a cleaner overview signal.

### The `balance` signal

Both layers include a `balance` property computed per feature:

```
balance = preference_ratio - avoidance_ratio
```

When both ratios are present, this gives a value from −1 (pure avoidance) to +1 (pure preference). When only counts are available (no ratios yet), a count-based approximation is used:

```
balance = (preference_count - avoidance_count) / (preference_count + avoidance_count)
```

The `bucket` property discretizes balance into named bins for frontend color mapping:

| Bucket | Balance threshold |
|---|---|
| `AVOIDANCE_STRONG` | ≤ −0.6 |
| `AVOIDANCE` | ≤ −0.3 |
| `AVOIDANCE_LIGHT` | ≤ −0.1 |
| `BASELINE` | −0.1 to 0.1 |
| `PREFERENCE_LIGHT` | ≥ 0.1 |
| `PREFERENCE` | ≥ 0.3 |
| `PREFERENCE_STRONG` | ≥ 0.6 |
| `NO_EVENTS` | no avoidance or preference events |

### Tile build process

1. PostGIS streams segment features as newline-delimited GeoJSON (`GeoJSONSeq`) directly to disk — geometry serialization happens in the database via `ST_AsGeoJSON`, not in Java
2. Two files are written: one for the `streets` layer (grouped), one for the `segments` layer (per-edge)
3. Tippecanoe is invoked as a subprocess with both files as layer inputs and zoom range parameters
4. The output file is atomically swapped into place (`ATOMIC_MOVE`) so the running tile server never serves a partially-written file

Tile builds are triggered manually via `POST /api/admin/tiles/rebuild`. Only one build can run at a time; concurrent requests return `409`.

### Configuration

| Property | Default | Description |
|---|---|---|
| `tiles.directory` | `./data/tiles` | Output directory for the PMTiles file |
| `tiles.tippecanoe-binary` | `tippecanoe` | Path to the Tippecanoe binary |
| `tiles.build-timeout-minutes` | `30` | Max time before the build is killed |
