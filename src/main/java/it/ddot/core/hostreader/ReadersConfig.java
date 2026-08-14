package it.ddot.core.hostreader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import it.ddot.core.io.ISourceReader;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>JSON format (all fields optional):
 * <pre>{@code
 * {
 *   "enabledExtensions": ["md", "java", "adoc"],
 *   "disabledExtensions": ["pptx", "dns"]
 * }
 * }</pre>
 *
 * <p>Rules:
 * <ul>
 *   <li>If {@code enabledExtensions} is non-empty, only those extensions are active.</li>
 *   <li>{@code disabledExtensions} always takes precedence (removes from the active set).</li>
 *   <li>Default (empty config) activates all extensions.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ReadersConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private Set<String> enabledExtensions = Set.of();
    private Set<String> disabledExtensions = Set.of();

    /** All extensions enabled, none disabled. */
    public static ReadersConfig defaults() {return new ReadersConfig();}

    /** Load from a JSON file at the given path. */
    public static ReadersConfig fromJson(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), ReadersConfig.class);
    }

    /** Only enabled readers are kept */
    public List<ISourceReader> filter(List<ISourceReader> readers) {
        return readers.stream()
                .filter(reader -> reader.sourceKind().fileExtensions().stream().anyMatch(this::isEnabled))
                .collect(Collectors.toList());
    }

    public Set<String> getDisabledExtensions() {return disabledExtensions;}

    // --- Jackson bean accessors ---

    public Set<String> getEnabledExtensions() {return enabledExtensions;}

    /** Returns {@code true} if a reader should be created for this extension. */
    public boolean isEnabled(String extension) {
        String ext = extension.toLowerCase();
        if (!enabledExtensions.isEmpty() && !enabledExtensions.contains(ext)) return false;
        return !disabledExtensions.contains(ext);
    }

    public void setDisabledExtensions(Set<String> v) {
        disabledExtensions = v.stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
    }

    public void setEnabledExtensions(Set<String> v) {
        enabledExtensions = v.stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
    }

}
