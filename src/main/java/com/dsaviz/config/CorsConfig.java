package com.dsaviz.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS configuration for the DSA Visualizer API.
 *
 * <p>Replaces the per-controller {@code @CrossOrigin(origins = "*")} annotation
 * with a centralized, environment-driven policy:
 *
 * <ul>
 *   <li><strong>Dev (default):</strong> {@code ALLOWED_ORIGINS} is not set →
 *       falls back to {@code "*"} for convenience. The Vite dev server at
 *       localhost:5173 can call the backend freely.</li>
 *   <li><strong>Production (Render):</strong> Set the {@code ALLOWED_ORIGINS}
 *       environment variable to your Vercel frontend URL, e.g.
 *       {@code https://your-app.vercel.app}. Comma-separate multiple origins
 *       if needed.</li>
 * </ul>
 *
 * <p>Note: the controller-level {@code @CrossOrigin(origins = "*")} annotation
 * is still present but this global config takes precedence for preflight
 * OPTIONS requests. Both are intentionally permissive for now — tighten
 * by setting ALLOWED_ORIGINS in Render's environment variables panel.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * Comma-separated list of allowed origins.
     * Example: "https://my-dsa-app.vercel.app,https://my-dsa-app-git-main.vercel.app"
     *
     * Defaults to "*" if not set (fine for local dev; set explicitly in production).
     */
    @Value("${cors.allowed-origins:*}")
    private String allowedOriginsRaw;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = parseOrigins(allowedOriginsRaw);

        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                // allowCredentials must be false when using wildcard origin patterns.
                // Set to true only if you add auth cookies later AND restrict origins.
                .allowCredentials(false)
                .maxAge(3600); // cache preflight for 1 hour
    }

    private String[] parseOrigins(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("*")) {
            return new String[]{"*"};
        }
        // Split on commas, strip whitespace around each entry
        String[] parts = raw.split(",");
        String[] trimmed = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            trimmed[i] = parts[i].trim();
        }
        return trimmed;
    }
}
