-- Hibernate creates the tables first (defer-datasource-initialization=true).
-- Index the geography expression used by the 25 m incident/corridor join.
CREATE INDEX IF NOT EXISTS idx_incidents_location_geography
    ON incidents USING gist ((location::geography))
    WHERE location IS NOT NULL;

-- Filtered map tiles first select geometry in the tile, then matching events.
CREATE INDEX IF NOT EXISTS idx_street_segments_geometry
    ON street_segments USING gist (geometry);
CREATE INDEX IF NOT EXISTS idx_event_segment_timestamp
    ON segment_events (segment_id, event_timestamp);

-- Filter indices
CREATE INDEX IF NOT EXISTS idx_event_ohsome_tile
    ON segment_events (segment_id, event_timestamp) WHERE ohsome_enriched;
CREATE INDEX IF NOT EXISTS idx_event_weather_tile
    ON segment_events (segment_id, event_timestamp) WHERE weather_enriched;
CREATE INDEX IF NOT EXISTS idx_event_traffic_measured_tile
    ON segment_events (segment_id, event_timestamp) WHERE traffic_enrichment_status = 'ENRICHED';
