package org.bukkit.util;

import org.jetbrains.annotations.NotNull;

/**
 * AGC compatibility utility representing a ray in world-space coordinates.
 * <p>
 * Bukkit exposes ray trace result objects, but not a standalone immutable ray type.
 * AGC keeps this small value object in the API so server-side optimization and
 * compatibility test code can share the same method signatures without leaking
 * NMS internals to plugins.
 */
public class Ray {
    private final Vector origin;
    private final Vector direction;

    public Ray(final @NotNull Vector origin, final @NotNull Vector direction) {
        if (origin == null) {
            throw new IllegalArgumentException("origin cannot be null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction cannot be null");
        }
        if (direction.lengthSquared() == 0.0D) {
            throw new IllegalArgumentException("direction cannot be zero-length");
        }
        this.origin = origin.clone();
        this.direction = direction.clone().normalize();
    }

    public @NotNull Vector getOrigin() {
        return this.origin.clone();
    }

    public @NotNull Vector getDirection() {
        return this.direction.clone();
    }

    public @NotNull Vector pointAt(final double distance) {
        return this.origin.clone().add(this.direction.clone().multiply(distance));
    }
}
