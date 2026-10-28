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
    COVERED("Covered", "Wooden bridge on stone piers with a log frame and a pink cherry gable roof"),
    ROPE("Rope", "Sagging plank bridge on rope between two log towers, chain handrails and lanterns"),
    NETHER("Nether", "Polished blackstone arch bridge with a wall parapet and soul lanterns"),
    SANDSTONE("Sandstone", "Desert arch bridge of smooth sandstone with a red sandstone path and chiseled piers"),
    MODERN("Modern", "Straight smooth quartz deck, glass railings, sea lantern posts and slim quartz pylons"),
    JAPANESE("Japanese", "Steep red drum bridge: dark oak deck, mangrove railings with a handrail, capped posts and lanterns at the ends"),
    BAMBOO("Bamboo", "Bamboo mosaic deck on bamboo beams, bamboo fence railing and bamboo pillars with lanterns"),
    SUSPENSION("Suspension", "Two tall red towers with cables curving down to the deck, chain hangers and a road with a centre line"),
    TRESTLE("Trestle", "Railway trestle: spruce deck with a minecart track, log bents down to the ground every 3 blocks"),
    TRUSS("Truss", "Green steel (waxed copper) truss bridge with diagonals, overhead bracing and stone piers"),
    DWARVEN("Dwarven", "Deepslate viaduct with deep arches, buttressed chiseled piers and lanterns"),
    OCEAN("Ocean", "Prismarine arch bridge with a wall parapet and sea lanterns"),
    END("End", "End stone brick arch bridge with a purpur path, purpur pillar caps and end rods");

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
