package com.dsaviz.controller;

import com.dsaviz.engine.ParsedSignature;
import com.dsaviz.engine.SignatureParser;
import com.dsaviz.engine.VisualizationService;
import com.dsaviz.model.ParseSignatureRequest;
import com.dsaviz.model.ParseSignatureResponse;
import com.dsaviz.model.VisualizeRequest;
import com.dsaviz.model.VisualizeResponse;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * REST API controller for the DSA Visualizer.
 *
 * Two endpoints:
 *   POST /api/visualize          — full compile + trace pipeline
 *   POST /api/parse-signature    — lightweight signature extraction for
 *                                   dynamic frontend input generation
 *
 * Input validation is done here at the controller level (fail fast with
 * clear messages) rather than letting nulls propagate deep into the engine.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // fine for local dev; tighten before any real deployment
public class VisualizeController {

    private final VisualizationService visualizationService;

    public VisualizeController(VisualizationService visualizationService) {
        this.visualizationService = visualizationService;
    }

    /**
     * Full compile-and-trace pipeline.
     * Validates inputs before delegating to the service layer.
     */
    @PostMapping("/visualize")
    public VisualizeResponse visualize(@RequestBody VisualizeRequest request) {
        // --- Input validation (fail fast with clear messages) ---
        if (request.getSolutionCode() == null || request.getSolutionCode().isBlank()) {
            return VisualizeResponse.failure("input", "Solution code is required.");
        }
        if (request.getMethodName() == null || request.getMethodName().isBlank()) {
            return VisualizeResponse.failure("input", "Method name is required.");
        }
        if (request.getArgs() == null) {
            request.setArgs(List.of()); // no args = zero-parameter method, valid
        }

        return visualizationService.visualize(request);
    }

    /**
     * Lightweight endpoint: parses the method signature from pasted code
     * and returns the parameter types and names. The React frontend uses
     * this to dynamically generate the correct number of argument input
     * fields with type-appropriate placeholders.
     *
     * This is intentionally separate from /visualize — the frontend calls
     * this on code-change (debounced) to update the input form, before the
     * user ever clicks "Run".
     */
    @PostMapping("/parse-signature")
    public ParseSignatureResponse parseSignature(@RequestBody ParseSignatureRequest request) {
        if (request.getSolutionCode() == null || request.getSolutionCode().isBlank()) {
            return ParseSignatureResponse.failure("Solution code is required.");
        }
        if (request.getMethodName() == null || request.getMethodName().isBlank()) {
            return ParseSignatureResponse.failure("Method name is required.");
        }

        try {
            ParsedSignature sig = SignatureParser.extractMethodSignature(
                    request.getSolutionCode(), request.getMethodName());

            List<ParseSignatureResponse.ParamInfo> params = new ArrayList<>();
            for (int i = 0; i < sig.paramTypes.size(); i++) {
                params.add(new ParseSignatureResponse.ParamInfo(
                        sig.paramTypes.get(i),
                        sig.paramNames.get(i)));
            }

            return ParseSignatureResponse.success(sig.methodName, sig.returnType, params);

        } catch (IllegalArgumentException e) {
            return ParseSignatureResponse.failure(e.getMessage());
        }
    }
}
