package com.testrts.rtsclient.build;

/** The form of the Mountain shape (picked with the Inverted button / [I], like an island's underside). */
public enum MountainForm {
    PEAKED("Peaked", "A main peak with lower ones beside it, ridges down the sides, cliffs and a snowy top", 0.9),
    ROUNDED("Rounded", "Soft, rounded hills: smooth slopes, grass all the way up, no cliffs", 0.45),
    MESA("Mesa", "A flat-topped plateau with steep cliff sides in bands of rock (great with the Desert style)", 0.45),
    VOLCANO("Volcano", "A steep cone with a crater on top: dark rock near the rim, magma at the bottom", 0.8),
    JAGGED("Jagged", "A cluster of sharp, narrow spires", 1.1),
    RIDGE("Ridge", "A mountain range: a row of peaks along the line from A to B", 0.6);

    private final String label;
    private final String description;
    /** How high it rises for Height 1-3, times the radius. */
    final double heightScale;

    MountainForm(String label, String description, double heightScale) {
        this.label = label;
        this.description = description;
        this.heightScale = heightScale;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public MountainForm next() {
        MountainForm[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
