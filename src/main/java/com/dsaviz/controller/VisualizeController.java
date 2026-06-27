package com.dsaviz.controller;

import com.dsaviz.engine.VisualizationService;
import com.dsaviz.model.VisualizeRequest;
import com.dsaviz.model.VisualizeResponse;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*") // fine for local dev; tighten before any real deployment
public class VisualizeController {

    private final VisualizationService visualizationService;

    public VisualizeController(VisualizationService visualizationService) {
        this.visualizationService = visualizationService;
    }

    @PostMapping("/api/visualize")
    public VisualizeResponse visualize(@RequestBody VisualizeRequest request) {
        return visualizationService.visualize(request);
    }
}
