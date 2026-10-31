package it.ddot.core.hostreader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadersConfigTest {

    @Test
    void defaults_enableEverything() {
        ReadersConfig cfg = ReadersConfig.defaults();
        assertThat(cfg.isEnabled("md")).isTrue();
        assertThat(cfg.isEnabled("pptx")).isTrue();
    }

    @Test
    void emptyObject_isDefaults() throws IOException {
        ReadersConfig cfg = ReadersConfig.fromJson("  { }  ");
        assertThat(cfg.getEnabledExtensions()).isEmpty();
        assertThat(cfg.getDisabledExtensions()).isEmpty();
    }

    @Test
    void enabled_restrictsToThose_caseInsensitively() throws IOException {
        ReadersConfig cfg = ReadersConfig.fromJson("{\"enabledExtensions\": [\"MD\", \"adoc\"]}");
        assertThat(cfg.isEnabled("md")).isTrue();
        assertThat(cfg.isEnabled("ADOC")).isTrue();
        assertThat(cfg.isEnabled("java")).isFalse();
    }

    @Test
    void disabled_winsOverEnabled() throws IOException {
        ReadersConfig cfg = ReadersConfig.fromJson("""
                {
                  "enabledExtensions": ["md", "java"],
                  "disabledExtensions": ["java"]
                }
                """);
        assertThat(cfg.isEnabled("md")).isTrue();
        assertThat(cfg.isEnabled("java")).isFalse();
    }

    @Test
    void unknownFields_ofAnyShape_areIgnored() throws IOException {
        ReadersConfig cfg = ReadersConfig.fromJson("""
                {
                  "comment": "a \\"quoted\\" \\u00e4 note",
                  "nested": {"a": [1, 2.5e3, true, null, {"b": []}]},
                  "disabledExtensions": ["pptx"],
                  "flag": false
                }
                """);
        assertThat(cfg.getDisabledExtensions()).containsExactly("pptx");
        assertThat(cfg.isEnabled("md")).isTrue();
    }

    @Test
    void fromFile(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path file = dir.resolve("readers.json");
        Files.writeString(file, "{\"disabledExtensions\": [\"dns\"]}");
        assertThat(ReadersConfig.fromJson(file).isEnabled("dns")).isFalse();
    }

    @Test
    void malformed_isAnIOException() {
        assertThrows(IOException.class, () -> ReadersConfig.fromJson("[]"));
        assertThrows(IOException.class, () -> ReadersConfig.fromJson("{\"enabledExtensions\": \"md\"}"));
        assertThrows(IOException.class, () -> ReadersConfig.fromJson("{\"enabledExtensions\": [1]}"));
        assertThrows(IOException.class, () -> ReadersConfig.fromJson("{\"a\": \"unterminated}"));
        assertThrows(IOException.class, () -> ReadersConfig.fromJson("{} trailing"));
    }

}
