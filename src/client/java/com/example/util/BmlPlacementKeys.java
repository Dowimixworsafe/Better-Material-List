package com.example.util;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stabilny klucz dla zaznaczeń (checkboxów) listy materiałów.
 *
 * PROBLEM, który to rozwiązuje:
 *   Dawniej klucz danych był "sklejaną etykietą" aktywnych placementów, np.
 *   "A, B, C (+2 more)". Etykieta zależała od KOLEJNOŚCI iteracji i od LICZBY
 *   placementów, a liczona była w dwóch miejscach (zapis vs odczyt), które się
 *   rozjeżdżały. Skutek: ten sam zestaw schematów dawał różne klucze przy różnych
 *   otwarciach → dane "znikały".
 *
 * ROZWIĄZANIE:
 *   Klucz = posortowane (deterministycznie) nazwy WŁĄCZONYCH placementów. Niezależny
 *   od kolejności i bez obcinania "(+N more)". Dla pojedynczego placementu klucz to po
 *   prostu jego nazwa, więc dotychczasowe zapisy działają dalej bez migracji.
 */
@Environment(EnvType.CLIENT)
public final class BmlPlacementKeys {

    /** Klucz używany, gdy nie ma żadnego aktywnego placementu. */
    public static final String GLOBAL = "global";

    private BmlPlacementKeys() {}

    /** Deterministyczny klucz checklisty dla zestawu włączonych placementów. */
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
