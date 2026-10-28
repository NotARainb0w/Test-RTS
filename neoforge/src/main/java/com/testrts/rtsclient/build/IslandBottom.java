package com.testrts.rtsclient.build;

/** The underside of the Island shape. */
public enum IslandBottom {
    POINTED("Pointed", "Hangs to a point in the middle, with drips: the classic floating island", 1.1),
    ROUNDED("Rounded", "A smooth round bowl underneath, no point", 0.7),
    STEPPED("Stepped", "Upside-down terraces: rings that get smaller in steps", 0.9),
    TORN("Torn out", "Ripped out of the ground: ragged dirt and rock, roots, and loose chunks falling away below", 0.9);

    private final String label;
    private final String description;
    /** How deep it hangs for Depth 1-3, times the radius. */
    final double depthScale;

    IslandBottom(String label, String description, double depthScale) {
        this.label = label;
        this.description = description;
        this.depthScale = depthScale;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public IslandBottom next() {
        IslandBottom[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
