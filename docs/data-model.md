# Data Model

## Entities

### `rides`

The central entity. One record per imported SimRa ride file.

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `status` | enum | Processing lifecycle state (see below) |
| `bikeType` | enum | `CITY_TREKKING_BIKE`, `ROAD_RACING_BIKE`, `E_BIKE`, `FREIGHT_BICYCLE`, `MOUNTAIN_BIKE`, `RECUMBENT_BICYCLE`, `TANDEM_BICYCLE`, `OTHER` |
| `rideIntent` | enum | `COMMUTE`, `LEISURE`, `UNKNOWN` — set by classifier after analysis |
| `childTransport` | boolean | Rider had a child seat |
| `trailerAttached` | boolean | Trailer attached |
| `phoneLocation` | enum | `POCKET`, `HANDLEBAR`, `JACKET_POCKET`, `HAND`, `BASKET`, `BAG`, `OTHER` |
| `startTime` / `endTime` | epoch ms | Ride start and end timestamps |
| `gpsPointCount` | long | Number of parsed GPS rows in the source file |
| `medianGpsAccuracy` | double | Continuous median of non-null GPS accuracy values |
| `trajectory` | LineString (4326) | Map-matched GPS trajectory |
| `shortestPath` | LineString (4326) | GraphHopper shortest path between start and end |
| `actualDistance` | double | Distance of the map-matched trajectory in meters |
| `shortestPathDistance` | double | Distance of the shortest path in meters |
| `isDetour` | boolean | False for the within-tolerance `EQUIVALENT_ROUTE`; true for both detour comparison types |
| `overlapRatio` | double | Fraction of shortest-path length inside the configured metric buffer around the actual route |
| `routeComparisonType` | enum | `EQUIVALENT_ROUTE`, `LOCAL_DETOUR`, or `CORRIDOR_ALTERNATIVE` after successful analysis |
| `originalFilename` | string | Source CSV filename |

`EQUIVALENT_ROUTE` is the persisted name for the distance-tolerance outcome, not a claim that the route geometries are identical. With the default policy, a ride remains in this class when its signed excess distance is no greater than `min(10% of shortestPathDistance, 500 m)`. Exceeding either allowance produces a detour; overlap then separates `LOCAL_DETOUR` from `CORRIDOR_ALTERNATIVE`. Only local detours generate preference and avoidance events.

**Ride status lifecycle:**

Clean imports persist only final statuses:

- `PENDING` / `ANALYZING` — retained enum values for response compatibility; not persisted by the inline import flow
- `PROCESSED` — route comparison completed successfully; the analytical outcome is stored in `routeComparisonType`
- `SKIPPED` — too short, no traversed edges, or shortest-path routing failed
- `ERROR` — retained for response compatibility; an inline failure rolls back instead of persisting this status

---

### Transient ride trace

GPS samples are represented during import as `RideTracePoint(location, timestamp)` values. The same chronologically sorted valid list is passed through map matching and detour analysis, then discarded. There is no `RidePoint` entity or `ride_points` table in a fresh schema; the SimRa CSV remains the authoritative raw trace archive.

---

### `incidents`

Safety incidents self-reported by the rider during the ride.

| Field | Type | Description |
|---|---|---|
| `incidentType` | enum | `CLOSE_PASS`, `PULLING_IN_OUT`, `NEAR_HOOK`, `HEAD_ON`, `TAILGATING`, `NEAR_DOORING`, `DODGING`, `OTHER`, `NOTHING` |
| `location` | Point (4326) | Where the incident occurred |
| `timestamp` | epoch ms | When it occurred |
| `scary` | boolean | Rider flagged it as scary |
| `description` | text | Free text description |

Incidents also have a `incident_participants` collection table with `ParticipantType` values: `BUS`, `CYCLIST`, `PEDESTRIAN`, `DELIVERY_VAN`, `TRUCK`, `MOTORCYCLE`, `CAR`, `TAXI`, `SCOOTER`, `OTHER`.

---

### `street_segments`

One record per GraphHopper road network edge. The `id` is the GraphHopper edge ID — segments are created on demand the first time a ride traverses or avoids them.

| Field | Type | Description |
|---|---|---|
| `id` | long (GraphHopper edge ID) | Primary key |
| `streetName` | string | OSM name tag at time of graph build |
| `geometry` | LineString (4326) | Edge geometry |
| `usageCount` | int | Times a ride traversed this segment |
| `avoidanceCount` | int | Times a ride avoided this segment (it was on the shortest path but bypassed) |
| `preferenceCount` | int | Times a ride chose this segment (it was not on the shortest path) |
| `avoidanceRatio` | double | `avoidanceCount / (avoidanceCount + usageCount)` |
| `preferenceRatio` | double | `preferenceCount / usageCount`; preferred traversals are a subset of usage |
| `gradientPercent` | double | Elevation gradient derived from DEM data |

Ratios are recomputed in-place on every increment — they are always consistent with the counts.

No secondary indices beyond the primary key — lookups are by `id` (the GraphHopper edge ID) or via `segment_events`/`segment_external_factors`, which are themselves indexed on `segment_id`.

---

### `segment_events`

One record per avoidance or preference observation. This is the primary analytical table — each row connects a ride to a segment with full contextual data attached.

**Core fields:**

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `segmentId` | long (FK) | The `street_segments` row this event belongs to |
| `rideId` | UUID (FK) | The `rides` row this event was generated from |
| `eventType` | enum | `AVOIDANCE` or `PREFERENCE` |
| `eventTimestamp` | epoch ms | When the rider was near this edge |
| `dayOfWeek` | enum | Pre-computed from `eventTimestamp` in Berlin timezone |
| `hourOfDay` | int | Pre-computed hour (0–23) in Berlin timezone |
| `rideIntent` | enum | Copied from the ride at event creation time |
| `pathBearingDegrees` | double | Compass direction the cyclist was heading on this edge |

Note: `bikeType` appears in the REST API's event JSON (see [data-export.md](data-export.md)) but is not a `segment_events` column — it's read from the joined `Ride` at serialization time.

Indexed on `segment_id`, `eventTimestamp`, `eventType`, and `cyclewayType` — the first three back the common per-segment/time-range/type lookups;
the `cyclewayType` index backs the infrastructure-signals analytics query. Composite weather and Ohsome status/timestamp/segment indices support their set-based work queues.

**Enrichment status fields** (one pair per source):

Each source tracks its own boolean flag and processing status independently, so partial enrichment is possible and failed sources can be retried without re-processing others.

For Ohsome, `DONE` means that the segment/month pair was evaluated successfully; it does not necessarily mean that attributes were found. 
`ohsomeEnriched=true` is reserved for a reliable historical road match. A supported snapshot with no reliable match, or an event outside the configured snapshot area/time range, ends as `DONE` with `ohsomeEnriched=false`. A missing snapshot leaves work `PENDING`, while an unexpected processing failure produces `ERROR`.

| Source | Flag field | Status field | Values |
|---|---|---|---|
| Weather | `weatherEnriched` | `weatherProcessingStatus` | `PENDING` → `DONE` / `ERROR` |
| Traffic | `trafficEnriched` | `trafficProcessingStatus` | `PENDING` → `DONE` / `ERROR` |
| OSM (Ohsome) | `ohsomeEnriched` | `ohsomeProcessingStatus` | `PENDING` → `DONE` / `ERROR` |
| Road closures | `berlinOpenDataEnriched` | `berlinOpenDataProcessingStatus` | `PENDING` → `DONE` / `ERROR` |

**Weather fields** (populated after Open-Meteo enrichment):

`temperature2m`, `precipitation`, `windSpeed10m`, `windDirection10m`, `weatherCode`, `relativeWindAngleDegrees`, `windExposure` (`HEADWIND`, `CROSSWIND`, `TAILWIND`)

`open_meteo_segment_grid` stores the permanent segment-centroid assignment as integer latitude/longitude tenths. `open_meteo_hourly_weather` stores the five raw weather fields with a composite primary key of `(latitude_tenths, longitude_tenths, valid_from)`. The nullable `weatherProcessingBatchId` isolates each bounded weather claim and is cleared when the claim finishes or is released. These tables and the batch identifier are internal restart-safe processing state; API weather data remains on `segment_events`.

**Traffic fields** (populated after Berlin traffic detector enrichment):

`trafficCondition` (`LIGHT`, `MODERATE`, `HEAVY`, `CONGESTED`), `trafficSourceType`, `trafficEnrichmentStatus`, `trafficVolumeKfz`, `trafficSpeedKfz`, `trafficVolumePkw`, `trafficSpeedPkw`, `trafficVolumeLkw`, `trafficSpeedLkw`

**OSM infrastructure fields** (populated after Ohsome enrichment):

`surface`, `smoothness`, `lit`, `highway`, `cyclewayType` (`TRACK`, `LANE`, `SHARED_LANE`, `SHARE_BUSWAY`, `SEPARATE`, `NO`), `cyclewayLocation` (`LEFT`, `RIGHT`, `BOTH`, `NONE`), `cyclewaySurface`, `cyclewayWidth`, `bicycleOneway`

---

### `segment_external_factors`

Stores segment-level road disruptions with a validity time window. This is separate from `segment_events` because these factors apply to a segment over a time range rather than to a single ride observation. `WEATHER` and `TRAFFIC` remain enum values for compatibility but are not written by their current enrichment pipelines.

| Field | Type | Description |
|---|---|---|
| `factorType` | enum | `WEATHER`, `CONSTRUCTION`, `ROAD_CLOSURE`, `TRAFFIC`, `EVENT`, `HAZARD`, `INCIDENT` |
| `source` | string | Origin identifier, currently `"berlin-open-data"` |
| `validFrom` / `validTo` | epoch ms | Time window when this factor was active |
| `affectedArea` | Geometry (4326) | Optional spatial extent (e.g. construction site polygon) |
| `metadata` | jsonb | Source-specific attributes without a fixed schema |

A unique constraint on `(segment_id, factorType, source, validFrom)` prevents duplicate factor records. Indexed on `segment_id`, `factorType`, and `(validFrom, validTo)` — the last backs the `/api/segments/{id}/factors` overlap query.

The segment-event API derives its `roadDisruptions` array from these rows at
read time. A factor is attached when it belongs to the event's segment and its
inclusive validity range contains the exact `eventTimestamp`; no event-factor
join table is stored.

---

### `road_closures`

One normalized occurrence from either the private historical VIZ snapshots or the VIZ Berlin Baustellen/Sperrungen (construction/closures) live feed, imported by `RoadClosureImportService`. Historical snapshots are inserted once into a database; live imports retain their existing upsert behavior. This is distinct from `segment_external_factors`: the enrichment pipeline reads this table to attach `SegmentExternalFactor` rows to nearby segment events, while `GET /api/road-closures` (see [data-export.md](data-export.md)) exposes these rows directly for map display.

| Field | Type | Description |
|---|---|---|
| `feedId` | string (unique) | Live feed identifier (e.g. `"8/2025"`) or historical occurrence key `historical:<source-id>:<validFrom-epoch>` |
| `lmsId` | string | Feed-internal LMS identifier |
| `factorType` | enum | Mapped from the feed's `subtype` — see below |
| `severity` | enum | `NO_CLOSURE`, `FULL_CLOSURE`, `DIRECTIONAL_CLOSURE`, `UNKNOWN` |
| `direction` | string | Affected direction, if applicable |
| `street` | string | Street name |
| `section` | text | Description of the affected stretch |
| `content` | text | Free-text description from the feed |
| `validFrom` / `validTo` | epoch ms | Validity window; `validTo` may be null (open-ended) |
| `geometry` | Geometry (4326) | Usually a `GeometryCollection` of one label `Point` plus affected-stretch `LineString`s |
| `tstore` | epoch ms | The feed's own last-modified timestamp for this entry |
| `firstSeenAt` / `lastSeenAt` | epoch ms | When this import service first/last saw the entry |

`factorType` is derived from the feed's `subtype` property: `"Baustelle"`/`"Bauarbeiten"` → `CONSTRUCTION`, `"Sperrung"` → `ROAD_CLOSURE`, `"Störung"` → `EVENT`, `"Gefahr"` → `HAZARD`, `"Unfall"` → `INCIDENT`; anything else (including a missing `subtype`) falls back to `ROAD_CLOSURE`.

---

### `traffic_detectors`

Reference table for Berlin's induction loop traffic sensor network. Populated once from the Berlin Verkehrsdetektion Excel file and used by the traffic enrichment scheduler to find the nearest sensor to a segment event.

| Field | Type | Description |
|---|---|---|
| `detId15` | string | Internal 15-min detector ID |
| `detNameAlt` | string | Alternative name (unique) |
| `mqKurzname` | string | Measurement queue short name |
| `street` | string | Street name |
| `position` / `positionDetail` | string | Position description |
| `direction` | string | Traffic direction |
| `lane` | string | Lane identifier |
| `location` | Point (4326) | Sensor location |
| `activeFrom` / `activeTo` | date | Operational period |
| `deinstalled` | boolean | Whether the sensor has been removed |

Indexed on `detId15`, `detNameAlt` (also unique), `mqKurzname`, and `street`.

---

## Collection Tables

These are `@ElementCollection` tables that store multi-valued fields of `rides`. They have no entity class of their own.

| Table | Key | Value | Purpose |
|---|---|---|---|
| `ride_edges` | `ride_id` | `edge_id` (int) | All GraphHopper edge IDs traversed by the ride |
| `ride_shortest_path_edges` | `ride_id` | `edge_id` (int) | Edge IDs of the computed shortest path |
| `ride_edge_bearings` | `ride_id`, `edge_id` | `bearing_degrees` (double) | Compass bearing per traversed edge (from map matching) |
| `ride_edge_timestamps` | `ride_id`, `edge_id` | `timestamp` (epoch ms) | Timestamp when the rider was on each traversed edge |

The bearing and timestamp maps drive event creation during detour analysis: avoided-edge bearings come from the shortest-path geometry, chosen-edge bearings come from `ride_edge_bearings`.

`ride_edges.ride_id` is indexed for per-ride edge lookup. `ride_edge_bearings` and `ride_edge_timestamps` don't need a separate index - their composite primary key `(ride_id, edge_id)` already supports fast lookups by `ride_id` alone.
