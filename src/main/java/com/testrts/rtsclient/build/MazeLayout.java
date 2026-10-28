package com.testrts.rtsclient.build;

/** How the Maze shape's paths run (picked with the Inverted button / [I]). */
public enum MazeLayout {
    CLASSIC("Classic", "One way through, long winding paths and dead ends"),
    LOOPS("Loops", "Many ways round: dead ends knocked through"),
    CHAMBERS("Chambers", "Open rooms along the way and a plaza in the middle"),
    ROUND("Round", "A circular maze"),
    RANDOM("Random", "A different layout every design");

    private final String label;
    private final String description;

    MazeLayout(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public MazeLayout next() {
        MazeLayout[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
