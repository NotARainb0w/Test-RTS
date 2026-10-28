package com.testrts.rtsclient.build;

/** What the Rocks shape makes (picked with the Style button). */
public enum RockForm {
    BOULDERS("Boulders", "A scattered group of rounded boulders, half sunk into the ground"),
    ARCH("Arch", "A natural stone arch on two rough legs"),
    STACKS("Sea stacks", "Tall, tapering rock columns standing apart"),
    OUTCROP("Outcrop", "One big lumpy rock with a rough top");

    private final String label;
    private final String description;

    RockForm(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public RockForm next() {
        RockForm[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
