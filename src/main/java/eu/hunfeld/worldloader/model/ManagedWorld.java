package eu.hunfeld.worldloader.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public record ManagedWorld(@NotNull String dimension, @NotNull String name, @NotNull WorldTypeMode type) {

    @NotNull
    public String id() {
        return dimension + ':' + name;
    }

    @NotNull
    public String lookupKey() {
        return lookupKey(dimension, name);
    }

    @NotNull
    public static String lookupKey(@NotNull final String dimension, @NotNull final String world) {
        return (dimension + ':' + world).toLowerCase(Locale.ROOT);
    }
}
