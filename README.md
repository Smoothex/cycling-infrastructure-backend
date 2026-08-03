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
infrastructure, not the infrastructure as it existed when the rides were recorded. In order to be able to assign historical
OSM data at the time of a preference or avoidance event, there is an ohsome enrichment: it queries 
the [ohsome API](https://api.ohsome.org/v1) for historical OSM tag values at the precise timestamp of each event
(see [docs/external-enrichments.md](docs/external-enrichments.md)).

### Key config properties

All properties live in `src/main/resources/application.properties` and can be overridden via environment variables.

| Property | Default | Description |
|---|---|---|
| `graphhopper.osm.file` | `./data/osm/germany-latest.osm.pbf` | OSM source file |
| `graphhopper.osm.download-url` | Geofabrik Germany extract | Where the OSM file is fetched from if missing |
| `pipeline.import.enabled` | `false` | Enable SimRa ride import |
| `pipeline.analysis.enabled` | `true` | Enable detour analysis scheduler |
| `pipeline.enrichment.weather.enabled` | `true` | Enable Open-Meteo enrichment |
| `pipeline.enrichment.traffic.enabled` | `true` | Enable Berlin traffic enrichment |
| `pipeline.enrichment.ohsome.enabled` | `true` | Enable Ohsome OSM enrichment (rate-limited) |
| `pipeline.enrichment.berlin-open-data.enabled` | `true` | Enable VIZ Berlin road-closure enrichment |

## Data Directory Layout

```
data/
├── osm/                      # OSM PBF source files (auto-downloaded)
├── graphhopper-cache/        # Built routing graph (auto-generated)
├── elevation-cache/          # Elevation tiles (auto-downloaded)
├── tiles/                    # Generated PMTiles vector tiles
├── berlinTraffic/cache/      # Monthly traffic measurement archives (auto-downloaded)
├── berlinOpenData/cache/     # VIZ road closures / construction JSON (auto-downloaded)
└── SimRa/                    # SimRa ride CSV files (or mounted volume)
```

## Data Pipeline Overview

The pipeline runs as a set of scheduled background jobs. Each job claims a batch of work, processes it, and marks records done - so the pipeline is resumable and restartable.

```
SimRa CSV files
      │
      ▼
[SimRa Importer]  every 30s
  Parse ride CSV → map-match GPS to road network → store Ride + RidePoints + edge traversals
      │
      ▼
[Detour Analyzer]  every 10s
  Compute shortest path → compare to actual route → create SegmentEvents (AVOIDANCE / PREFERENCE)
      │
      ▼
[Enrichment Schedulers]  every 60s (parallel, independent)
  ├── Weather (Open-Meteo)
  ├── Traffic (Berlin detectors)
  ├── Road closures (Berlin Open Data)
  └── OSM attributes (Ohsome API)
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
