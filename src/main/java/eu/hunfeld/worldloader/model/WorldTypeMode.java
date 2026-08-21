package eu.hunfeld.worldloader.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;

public enum WorldTypeMode {
    AIR,
    FLAT,
    NORMAL;

    @NotNull
    public static Optional<WorldTypeMode> parse(@NotNull final String value) {
        try {
            return Optional.of(valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @NotNull
    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
