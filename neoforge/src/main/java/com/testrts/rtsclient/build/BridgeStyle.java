package com.testrts.rtsclient.build;

/**
 * Looks for the Bridge shape. Classic uses the brush (deck) with a matching railing; the others have
 * their own materials, like well known survival builds.
 */
public enum BridgeStyle {
    CLASSIC("Classic", "Deck of the brush block, a matching fence or wall railing and lantern posts"),
    MEDIEVAL("Medieval", "Spruce deck on log beams, fence railing, lantern posts and log pillars down to the river bed"),
    VIADUCT("Viaduct", "Tall stone brick piers down to the bottom with arches between them, walled deck"),
    GARDEN("Garden", "Small humped spruce bridge with a gateway: beam, chains and hanging lanterns, leafy posts"),
    STONE("Stone", "Stone brick arch bridge with a wall parapet and pillar caps"),
    OVERGROWN("Overgrown", "Mossy, cracked stone arch bridge with leaves on the parapet"),
    COVERED("Covered", "Wooden bridge on stone piers with a log frame and a pink cherry gable roof");

    private final String label;
    private final String description;

    BridgeStyle(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public BridgeStyle next() {
        BridgeStyle[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
