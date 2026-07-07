package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.service.TileBuildService;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.TileStatusDto;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
public class TileController {

    private final TileBuildService tileBuildService;

    public TileController(TileBuildService tileBuildService) {
        this.tileBuildService = tileBuildService;
    }

    @PostMapping("/api/admin/tiles/rebuild")
    public ResponseEntity<TileStatusDto> rebuild() {
        boolean started = tileBuildService.triggerRebuild();
        return ResponseEntity
                .status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT)
                .body(tileBuildService.getStatus());
    }

    @GetMapping("/api/tiles/status")
    public TileStatusDto status() {
        return tileBuildService.getStatus();
    }

    /**
     * Serves the PMTiles archive. Returning a Resource lets Spring MVC handle HTTP
     * Range requests (206 Partial Content) automatically, which the pmtiles client
     * relies on to fetch individual tiles from the archive.
     */
    @GetMapping("/api/tiles/segments.pmtiles")
    public ResponseEntity<Resource> tiles() throws IOException {
        Path file = tileBuildService.tileFilePath();
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .eTag("\"" + Files.getLastModifiedTime(file).toMillis() + "\"")
                .cacheControl(CacheControl.noCache())
                .body(new FileSystemResource(file));
    }
}
