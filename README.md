# Cycling Infrastructure Backend

A geospatial analytics backend for identifying unsafe or unattractive cycling infrastructure in Berlin. It ingests ride trajectories from the SimRa community safety app, compares actual routes against shortest paths to detect avoidance behavior, and enriches each event with weather, traffic, and OpenStreetMap data.

The end result is a per-segment dataset answering: *which streets do cyclists actively avoid, when, and under what conditions?*

## Tech Stack

- **Java 25** / Spring Boot 4
- **PostgreSQL 17 + PostGIS 3.4** - spatial queries, geometry storage
- **GraphHopper 11** - OSM routing, map matching
- **Tippecanoe 2.78.0** - vector tile generation (PMTiles)
- **Hibernate** with `ddl-auto=update` (no migration files; schema is auto-managed)

## Local Development

### Create the SimRa network (required once)
```bash
docker network create simra_simra-network
```

### Start PostgreSQL and pg_admin

```bash
docker compose up -d
```

The base configuration is durable and keeps PostgreSQL's recyclable WAL working set near a
2 GB soft target. `archive_mode` is off and the development setup has no replication slots, so
old WAL files are recycled after checkpoints rather than retained as history.

### Non-durable bulk-import database mode

For a database that can be recreated entirely from the source ride files, an opt-in profile
disables PostgreSQL crash durability:

```bash
docker compose -f compose.yaml -f compose.bulk-import.yaml up -d postgres
```

This runs with `fsync=off`, `full_page_writes=off`, and `synchronous_commit=off`. It still
generates WAL for normal inserts and updates, but avoids forcing it to durable storage. A host,
Docker, or PostgreSQL crash can leave the database corrupt; recreate and re-import it rather
than trusting crash recovery.

Switch back to durable mode after a clean shutdown with:

```bash
docker compose -f compose.yaml stop postgres
docker compose -f compose.yaml up -d postgres
```

### Run the backend locally
```bash
./gradlew bootRun
```

### Run the backend in Docker
```bash
docker compose --profile app up -d --build
```


The Docker image is multi-stage: it compiles Tippecanoe from source, then builds the Spring Boot fat JAR, and runs with `-Xms2g -Xmx8g`. The container mounts `./data` for OSM files, the GraphHopper cache, tile output, and traffic cache.

The SimRa ride files are mounted from `/Users/momchil.petrov/Downloads/SimRa` - update the volume path in `compose.yaml` to match your local SimRa data directory.


The app starts on `http://localhost:8080`. On first boot, the OSM extract is downloaded automatically (see below), then GraphHopper builds its routing graph from it - this takes several minutes and produces a cache at `./data/graphhopper-cache`. As part of this, GraphHopper also prepares a Contraction Hierarchy (CH) for the `bike_shortest` profile, which takes roughly 15-20 minutes on the full Germany extract. Both the graph and the CH files are cached to disk, so this cost is paid once - subsequent restarts just load the existing cache (look for `There are no CHs to prepare` in the logs).

GraphHopper caches the profile definitions and encoded access values as part of the routing graph. After changing or upgrading the profiles, delete `./data/graphhopper-cache` before starting the application so GraphHopper imports the OSM data with the current definitions. This profile update requires a rebuild even if the previous cache also contained a profile named `bike_shortest`. The current profiles are documented in [detour-analysis.md](docs/detour-analysis.md#2-shortest-path-computation).

### OSM data

On startup, the backend checks for the OSM extract at `graphhopper.osm.file` and, if missing, downloads it from 
`graphhopper.osm.download-url` (default: the [Geofabrik Germany extract](https://download.geofabrik.de/europe/germany-latest.osm.pbf), 
several GB - expect the first startup to take a while). The download is atomic: an interrupted download is discarded and 
retried on the next startup, never loaded half-written.

This file is required before anything else can run: GraphHopper builds its routing graph from it, and that graph is what 
every pipeline stage depends on: map-matching of SimRa rides, shortest-path computation for detour analysis, and the 
street segment geometry itself.

Geofabrik serves the **latest** state of OpenStreetMap. The road network in the routing graph therefore reflects today's
infrastructure, not the infrastructure as it existed when the rides were recorded. The Ohsome enrichment therefore uses
historical, month-start OSM snapshots from the [ohsome API v2](https://api.heigit.org/ohsome-api-staging/v2/docs).
Snapshots are requested only for buffered 0.1° tiles containing pending events, at the start of each event’s UTC month.
GeoParquet files are checksummed and cached under `./data/ohsome/v2/tiles-v1/{tile-id}/{month}.parquet`;
matching then runs locally for each distinct street-segment/month pair. See
[docs/external-enrichments.md](docs/external-enrichments.md) for the temporal approximation and matching rules.

### Key config properties

All properties live in `src/main/resources/application.properties` and can be overridden via environment variables.

| Property | Default | Description |
|---|---|---|
| `graphhopper.osm.file` | `./data/osm/germany-latest.osm.pbf` | OSM source file |
| `graphhopper.osm.download-url` | Geofabrik Germany extract | Where the OSM file is fetched from if missing |
| `pipeline.import.enabled` | `false` | Enable SimRa ride import |
| `pipeline.enrichment.weather.enabled` | `false` | Enable bulk Open-Meteo enrichment |
| `pipeline.enrichment.weather.batch-size` | `5` | 0.1° grid/year locations per request |
| `pipeline.enrichment.traffic.enabled` | `true` | Enable Berlin traffic enrichment |
| `pipeline.enrichment.ohsome.enabled` | `false` | Enable cached tile/month Ohsome v2 enrichment |
| `ohsome.v2.grid-size-degrees` | `0.1` | Grid cell width and height in longitude/latitude degrees |
| `ohsome.v2.buffer-degrees` | `0.01` | Extra area requested around each cell |
| `pipeline.enrichment.berlin-open-data.enabled` | `true` | Enable VIZ Berlin road-closure enrichment |
| `tiles.auto-rebuild.enabled` | `true` | Automatically rebuild stale tiles after pipeline work becomes idle |
| `tiles.auto-rebuild.quiet-period-ms` | `900000` | Required pipeline idle time before an automatic tile build |

## Data Directory Layout

```
data/
├── osm/                       # OSM PBF source files (auto-downloaded)
├── graphhopper-cache/         # Built routing graph (auto-generated)
├── elevation-cache/           # Elevation tiles (auto-downloaded)
├── tiles/                     # Generated PMTiles vector tiles
├── ohsome/v2/                 # Cached historical GeoParquet snapshots and manifest
├── berlinTraffic/cache/       # Monthly traffic measurement archives (auto-downloaded)
├── berlinOpenData/cache/      # VIZ road closures / construction JSON (auto-downloaded)
├── berlinOpenData/historical/ # Private historical VIZ snapshots, grouped by year
└── SimRa/                     # SimRa ride CSV files (or mounted volume)
```

## Data Pipeline Overview

Ride import performs map matching and detour analysis inline. Enrichment remains a set of scheduled background jobs that claim and update segment events.

```
SimRa CSV files
      │
      ▼
[SimRa Importer]  bounded batches until the source scan is exhausted
  Parse transient GPS trace → map-match → compare shortest path → atomically store finalized Ride + counters + SegmentEvents
      │
      ▼
[Enrichment Schedulers]  every 60s (parallel, independent)
  ├── Weather (Open-Meteo)
  ├── Traffic (Berlin detectors)
  ├── Road closures (Berlin Open Data)
  └── OSM attributes (cached monthly Ohsome v2 snapshots)
      │
      ▼
[Tile Builder]  on demand
  Export GeoJSON from PostGIS → Tippecanoe → PMTiles
```

Deep-dive documentation follows the pipeline order:

- [docs/data-model.md](docs/data-model.md) - entity relationship diagram and field-level reference
- [docs/data-import.md](docs/data-import.md) - SimRa file format, parsing, validation, map matching
- [docs/detour-analysis.md](docs/detour-analysis.md) - routing profiles, shortest-path comparison, avoidance/preference event creation, ride intent classification
- [docs/external-enrichments.md](docs/external-enrichments.md) - weather, traffic, OSM, and road closure enrichment sources
- [docs/data-export.md](docs/data-export.md) - REST API reference, vector tiles, PMTiles, Tippecanoe

### Note:
Artificial intelligence was used to generate the documentation and test suite of this project.
