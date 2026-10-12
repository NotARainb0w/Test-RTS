package com.testrts.rtsclient.storage;

import java.util.List;

/** The storage mods the builder can take items from, in the order it tries them. */
public final class Grids {
    private static final List<GridMod> ALL = List.of(Ae2.GRID);

    private Grids() {
    }

    public static List<GridMod> all() {
        return ALL;
    }
}
