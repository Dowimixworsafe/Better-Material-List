package com.example.util;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stable key for material-list checkboxes.
 *
 * Problem this solves:
 *   The data key used to be a "joined label" of active placements, e.g.
 *   "A, B, C (+2 more)". That label depended on iteration ORDER and on the placement
 *   COUNT, and was computed in two places (write vs read) that diverged. Result: the
 *   same set of schematics produced different keys across openings → data "vanished".
 *
 * Solution:
 *   Key = deterministically sorted names of ENABLED placements. Independent of order
 *   and without the "(+N more)" truncation. For a single placement the key is just its
 *   name, so existing saves keep working without migration.
 */
@Environment(EnvType.CLIENT)
public final class BmlPlacementKeys {

    /** Key used when there is no active placement. */
    public static final String GLOBAL = "global";

    private BmlPlacementKeys() {}

    /** Deterministic checklist key for the set of enabled placements. */
    public static String checklistKey(List<SchematicPlacement> placements) {
        if (placements == null || placements.isEmpty()) return GLOBAL;

        List<String> names = new ArrayList<>();
        for (SchematicPlacement p : placements) {
            if (p != null && p.isEnabled()) {
                names.add(p.getName());
            }
        }
        if (names.isEmpty()) return GLOBAL;

        Collections.sort(names);
        return String.join("|", names);
    }
}
