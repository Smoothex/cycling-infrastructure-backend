# Data Import

## Overview

The only data source currently imported is **SimRa** — a community cycling safety app that records GPS trajectories and safety incidents. Raw ride files are picked up from a local directory, parsed, validated, map-matched to the road network, and stored in the database.

Import runs in bounded batches during one backend run. It keeps scheduling batches until the source scan is exhausted, then stops scanning until the backend is restarted.

For `.env` setup, startup commands, cache paths, networking, and throughput settings,
see [Running the backend](../README.md#running-the-backend).

---

## SimRa File Format

Each SimRa ride is a single CSV file named `VM<timestamp>` (e.g. `VM2024-03-15_12-34-56`). Files live under a `Rides/` subdirectory within the configured SimRa data path.

The file has two sections separated by a `======` line:

```
key,lat,lon,ts,bike,childCheckBox,trailerCheckBox,pLoc,incident,i1,...,i10,scary,desc
0,,,,1,0,0,1,-5,,,,,,,,,,,,
1,52.512,13.393,1710498000000,,,,,2,0,1,0,0,0,0,0,1,0,0,0,true,Close pass at intersection
======
lat,lon,X,Y,Z,timeStamp,acc,a,b,c
52.512,13.393,-0.42,0.11,9.82,1710498000000,3.5,0.01,-0.02,0.00
52.513,13.394,-0.40,0.09,9.80,1710498001000,3.2,...
```

**Section 1 — Incidents and ride metadata:**
- Row 0 typically contains ride-level metadata: bike type, child transport, trailer, phone location
- Subsequent rows are incident observations (one per row)
- Incident type codes: 0=nothing, 1=close pass, 2=pulling in/out, 3=near hook, 4=head-on, 5=tailgating, 6=near dooring, 7=dodging, 8=other
- Participant flags `i1`–`i10` map to: bus, cyclist, pedestrian, delivery van, truck, motorcycle, car, taxi, other, scooter
- Rows with `incident=-5` (dummy placeholder) are discarded

**Section 2 — GPS track:**
- One row per GPS sample with position, accelerometer (X/Y/Z), gyroscope (a/b/c), GPS accuracy, and timestamp (epoch ms)
- Sensor columns remain accepted, but only location, timestamp, point count, and the GPS-accuracy median are used by the backend

---

## Import Pipeline

### Step 1 — File Discovery

The loader scans the SimRa data path recursively for files that:
- Are in a `Rides/` directory
- Have filenames starting with `VM`
- Have not already been imported (checked against `rides.original_filename`)
- Have not been attempted in the current run (in-memory dedup to avoid retrying known-bad files)

Up to `pipeline.import.batch-size` (configured default: 20) files are processed per cycle.

If a scan returns fewer files than the batch limit, the source tree was exhausted and the importer stops immediately after that final batch completes. If a scan returns exactly the limit, another scan determines whether another batch remains. Once complete, later scheduled callbacks return without querying the database or walking the source volume. A backend restart starts a new import run, allowing newly added files and previously failed files to be considered again.

### Step 2 — Parsing

`SimRaFileParser` reads the file and produces a `ParsedRide`: a persistent `Ride` aggregate plus a transient list of `RideTracePoint` values.

1. Splits the stream on the `======` separator
2. Finds the header row in each section (starts with `key,` or `lat,`)
3. Pads short rows to match the header column count (some SimRa versions omit trailing fields)
4. Parses both sections via OpenCSV bean mapping
5. Extracts ride metadata (bike type, etc.) from the first incident row
6. Builds an in-memory trace containing only JTS `Point` geometries (SRID 4326) and timestamps
7. Builds `Incident` objects with participant sets
8. Sets `startTime` / `endTime` from the first and last point timestamps
9. Stores the number of parsed GPS rows and the median of non-null GPS accuracies on `Ride`

The raw trace is not attached to the JPA aggregate and is never persisted. The original source order still determines `startTime`, `endTime`, and the initial trajectory, matching the previous parser behavior. Before validation and map matching, valid locations with timestamps are sorted chronologically once; that same canonical list is reused for map matching, edge timestamps, shortest-path endpoints, and detour timestamps.

### Step 3 — Validation

Before map matching, two checks run:

- **Empty track** — rides with zero GPS points are skipped
- **Germany bounding box** — all points must fall within lat 47.2–55.1 / lon 5.8–15.1; rides with any point outside are skipped (rejects test data and rides from other regions)

### Step 4 — Map Matching

`MapMatchingService` snaps the raw GPS trajectory to the OSM road network using GraphHopper's Hidden Markov Model map matching algorithm. The `bike_match_neutral` profile respects directional bicycle access and excludes inaccessible and private edges. It uses a constant speed of 20 km/h and does not prefer cycleways or any other infrastructure class, so infrastructure assumptions do not bias reconstruction of the observed route. The complete profile definition is documented in [detour-analysis.md](detour-analysis.md#2-shortest-path-computation).

1. GPS points are converted to `Observation` objects and passed to GraphHopper
2. GraphHopper returns a sequence of `EdgeMatch` objects — one per road segment traversed
3. The snapped coordinates are assembled into a new `LineString` (the cleaned `trajectory`)
4. Per-edge data is prepared on the in-memory ride:
   - **Edge IDs** — the GraphHopper edge IDs of all traversed segments
   - **Bearings** — compass direction (0–360°) per edge, computed from the edge geometry in traversal direction
   - **Timestamps** — epoch ms per edge, estimated by finding the GPS point closest to the edge midpoint
5. Repeated edge traversals are grouped into occurrence-count usage deltas; missing zero-count `StreetSegment` reference rows are created in short independent transactions
6. Shortest-path and detour analysis runs immediately using the same canonical trace
7. A final transaction flushes the finalized `Ride`, locks the union of usage/avoidance/preference segment IDs in ascending order, applies all counters, and saves segment events

Rides below the configured origin-destination distance are finalized as `SKIPPED` without usage. If shortest-path routing fails after successful map matching, the ride is finalized as `SKIPPED` while retaining prepared usage. If map matching, detour preparation, or final persistence throws, no ride, counter, or event state is committed; an unused zero-count segment reference may remain. The source file is suppressed for the rest of the current backend run and becomes eligible again after restart because its filename was not committed.

Metrics accumulate across every batch. After the source is exhausted and all inline detour and final-persistence work has completed, one final structured summary reports:

- Source files selected, attempted, rejected, invalid, and failed
- Committed rides split into `PROCESSED` and `SKIPPED`
- Route-comparison and ride-intent classifications, plus imported safety incidents
- Usage observations and per-ride segment counter updates
- Total segment events split into `AVOIDANCE` and `PREFERENCE`
- Map-matching outcomes and success rate
- Total, average, maximum, and sample count for every processing phase
- End-to-end duration and committed-ride throughput

A file counts as committed only after the final transaction succeeds. If the final summary reports failures, restarting the backend makes those source files eligible for retry.

### Step 5 — Parallel Execution

Files within a batch use a `ForkJoinPool` sized to `pipeline.import.thread-pool-size` (configured default: 1). Increasing this value allows parallel ride processing; increasing the batch size alone does not. See [Throughput and resource settings](../README.md#throughput-and-resource-settings) before tuning it.

---

## Configuration

| Property |  Description                            |
|---|-----------------------------------------|
| `pipeline.import.enabled` |  Must be set to `true` to enable import |
| `simra.data.path` |  Root directory to scan for SimRa files |
| `pipeline.enabled` |  Master switch for all pipeline jobs    |
| `pipeline.import.batch-size` |  Max files per import cycle             |
| `pipeline.import.thread-pool-size` |  Parallel import threads                |
| `pipeline.import.delay-ms` |  Delay between non-final import batches (ms) |

The SimRa directory must contain a `Rides/` subdirectory with files named `VM*`. In Docker, the directory is mounted as a volume (see `compose.yaml`).
