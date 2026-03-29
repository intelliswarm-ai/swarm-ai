package ai.intelliswarm.swarmai.studio.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for serving the SwarmAI Studio single-page application.
 * Serves the static index.html for all /studio routes so that
 * client-side hash routing works correctly.
 */
@RestController
public class StudioPageController {

    private static final Resource INDEX_HTML = new ClassPathResource("static/studio/index.html");

    @GetMapping(value = "/studio", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> studioRoot() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(INDEX_HTML);
    }
}
