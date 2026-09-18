# Cycling Infrastructure Backend

A geospatial analytics backend for identifying unsafe or unattractive cycling infrastructure in Berlin. It ingests ride trajectories from the [SimRa](https://web2.ecdf.tu-berlin.de/forschung/projekte/simra/) community safety app, compares actual routes against shortest paths to detect avoidance behavior, and enriches each event with weather, traffic, and OpenStreetMap data.

The end result is a per-segment dataset answering: *which streets do cyclists actively avoid, when, and under what conditions?*

## Tech Stack

- **Java 25** / Spring Boot 4
- **PostgreSQL 17 + PostGIS 3.4** - spatial queries, geometry storage
- **GraphHopper 11** - OSM routing, map matching
- **Tippecanoe 2.78.0** - vector tile generation (PMTiles)
- **Hibernate** with `ddl-auto=update`; `schema.sql` adds PostgreSQL expression indexes after Hibernate initializes the tables.

## Running the backend

### Start the backend and PostgreSQL

Create `.env` next to `compose.yaml`, replacing the path to the input folder (must contain `Rides` folder) and the 
[HeigIT](https://api.heigit.org/) API key required for the infrastructure enrichment:

```dotenv
SIMRA_HOST_PATH=/path/to/Berlin
HEIGIT_API_KEY=<API_KEY>
```

From the repository directory, create the `./data` cache directory and start the backend and the DB:

```bash
mkdir -p data
docker compose up -d
```

If using another cache folder instead of `./data`, you can set its value in the `.env` as `APP_DATA_PATH`.
Importing is enabled by default. After an import completes, `docker compose restart backend` discovers newly added files.

### Environment variables and pipeline switches

Compose reads `.env` and substitutes its values into `${VARIABLE:-default}` entries
in `compose.yaml`. The backend's `environment` section passes pipeline switches to
Spring, overriding `application.properties`. Set overrides in `.env`, or edit the
defaults in Compose. Shell environment variables take precedence over `.env`.
Adding an arbitrary variable to `.env` does not pass it into the container unless
Compose references it.

| Variable | Compose default | Controls |
| --- | --- | --- |
| `PIPELINE_ENABLED` | `true` | Overall pipeline |
| `PIPELINE_IMPORT_ENABLED` | `true` | Ride import |
| `PIPELINE_ENRICHMENT_ENABLED` | `false` | All enrichment stages |
| `PIPELINE_ENRICHMENT_WEATHER_ENABLED` | `false` | Weather |
| `PIPELINE_ENRICHMENT_OHSOME_ENABLED` | `false` | Historical OSM data |
| `PIPELINE_ENRICHMENT_BERLINOPENDATA_ENABLED` | `false` | Road disruptions |
| `PIPELINE_ENRICHMENT_TRAFFIC_ENABLED` | `false` | Traffic measurements |

After changing `.env` or Compose settings, apply them with `docker compose up -d`;
`restart` alone does not apply changed environment values. For application or JVM
tuning, see [Throughput and resource settings](#throughput-and-resource-settings).

### Network and ports

Compose uses project name `cycling-infrastructure` and automatically creates its
network `cycling-infrastructure_default` and database volume
`cycling-infrastructure_postgres-data`.

Published ports are bound to localhost:

| Service | Host port | Container port | Optional `.env` override |
| --- | --- | --- | --- |
| Backend | 18080 | 8080 | `BACKEND_HOST_PORT` |
| PostgreSQL | 15432 | 5432 | `POSTGRES_HOST_PORT` |
| pgAdmin | 15050 | 80 | `PGADMIN_HOST_PORT` |

pgAdmin is optional:

```bash
docker compose --profile admin up -d pgadmin
```

Set `PGADMIN_PASSWORD` in `.env`. Within pgAdmin, connect
to host `postgres`, port `5432`, database `cyclingdb`, and user `user`.

### Throughput and resource settings

| Where to edit | Current settings | Purpose |
| --- | --- | --- |
| `src/main/resources/application.properties` | `pipeline.import.thread-pool-size=1`, `pipeline.import.batch-size=20`, `pipeline.import.delay-ms=2000` | Concurrent ride workers, files per batch, pause between batches |
| `src/main/resources/application.properties` | `spring.datasource.hikari.maximum-pool-size=8` | Backend database connections |
| `src/main/resources/application.properties` | `pipeline.scheduler.thread-pool-size=6`, `pipeline.enrichment.*` batch sizes and delays | Scheduling and enrichment throughput |
| `compose.yaml` | Backend: `mem_limit: 6g`, `cpus: 1.5`; PostgreSQL: `mem_limit: 1g`, `cpus: 0.5` | Container resource limits |
| `compose.yaml`, PostgreSQL `command` | `max_connections=30`, `shared_buffers=128MB`, `work_mem=4MB`, WAL/checkpoint settings | Database capacity and memory use |
| `Dockerfile`, `ENTRYPOINT` | `-Xms512m`, `-Xmx5g` | JVM initial and maximum heap |

For higher import throughput, first inspect CPU and memory usage with
`docker stats` and the import metrics described in [data-import.md](docs/data-import.md).
Increase import workers gradually as host capacity allows; more workers increase
memory use and database contention. Coordinate CPU limits, heap size and database connections, leaving memory for native
allocations and other processes.

After editing `application.properties` or `Dockerfile`, rebuild with
`docker compose up -d --build backend`. Compose-only changes need
`docker compose up -d`. Optional pgAdmin adds a 256 MiB memory limit.

PostgreSQL always runs with `fsync=off`, `full_page_writes=off`, and
`synchronous_commit=off`, including after the import. These non-durable settings can
leave the database corrupt after a crash and require recreating it from source data.
WAL compression is enabled; `max_wal_size=2GB` is a soft target, not a disk-space cap.
The database volume persists across container replacement; `docker compose down -v`
deletes it.

### Input files and caches

No separate cache variable is required. With the default `APP_DATA_PATH=./data`, caches live in
the repository's `data/` directory on the host, mounted at `/app/data` in the container.

| Data | Host location | Container location |
| --- | --- | --- |
| Read-only ride input | `${SIMRA_HOST_PATH}/Rides/...` | `/app/data/SimRa/Rides/...` |
| OSM extract | `${APP_DATA_PATH}/osm/germany-latest.osm.pbf` | `/app/data/osm/germany-latest.osm.pbf` |
| Routing graph | `${APP_DATA_PATH}/graphhopper-cache/` | `/app/data/graphhopper-cache/` |
| Elevation and enrichment caches | Subdirectories of `${APP_DATA_PATH}` listed below | Subdirectories of `/app/data` |
| Generated tiles | `${APP_DATA_PATH}/tiles/` by default | `/app/data/tiles/` by default |
| Database | Docker volume `cycling-infrastructure_postgres-data` | `/var/lib/postgresql/data` in PostgreSQL |

The Docker image builds Tippecanoe and the Spring Boot JAR. The backend is available at
`http://localhost:18080`. On first boot, it downloads the OSM extract if missing and GraphHopper 
builds the routing graph, including the [Contraction Hierarchy](https://www.graphhopper.com/blog/2014/07/28/the-flexibility-of-graphhopper/) for `bike_shortest`.

GraphHopper caches the profile definitions and encoded access values as part of the routing graph. 
After changing or upgrading the profiles, the cache at `${APP_DATA_PATH}/graphhopper-cache` must be rebuilt with the 
current definitions, even if the previous cache also contained a profile named `bike_shortest`.
The current profiles are documented in [detour-analysis.md](docs/detour-analysis.md#2-shortest-path-computation).

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

### Other German cities

Rides from other German cities can be imported into the same database by placing files in the existing SimRa format and 
folder structure under `simra.data.path`. Filenames must be unique across regional datasets; enable import and restart 
the backend to pick up new files. Route comparison, ohsome, and weather enrichment use the ride or segment coordinates 
and require no city registration. VIZ traffic and road-disruption sources remain Berlin-only.

### Key config properties

These are the defaults in `src/main/resources/application.properties`. Container
environment values override them; the Compose deployment defaults are listed above.

| Property | Default | Description |
|---|---|---|
| `graphhopper.osm.file` | `./data/osm/germany-latest.osm.pbf` | OSM source file |
| `graphhopper.osm.download-url` | Geofabrik Germany extract | Where the OSM file is fetched from if missing |
| `pipeline.import.enabled` | `false` | Enable SimRa ride import |
| `pipeline.enrichment.weather.enabled` | `false` | Enable bulk Open-Meteo enrichment |
| `pipeline.enrichment.weather.batch-size` | `5` | 0.1° grid/year locations per request |
| `pipeline.enrichment.traffic.enabled` | `false` | Enable Berlin traffic enrichment |
| `pipeline.enrichment.ohsome.enabled` | `true` | Enable cached tile/month Ohsome v2 enrichment |
| `ohsome.v2.grid-size-degrees` | `0.1` | Grid cell width and height in longitude/latitude degrees |
| `ohsome.v2.buffer-degrees` | `0.01` | Extra area requested around each cell |
| `pipeline.enrichment.berlin-open-data.enabled` | `false` | Enable VIZ Berlin road-closure enrichment |
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
