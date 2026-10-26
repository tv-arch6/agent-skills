package com.Cigram.vid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The providers the app ships with. Order of trust: the library first (the owner's own content),
 * then the third-party providers, one adapter each.
 *
 * Status of the four named third-party providers (vidfast.vc, vidnest.fun, videm.xyz, vidbolt.pro):
 * the project contains no integration for them, they publish no API documentation that was
 * available to the person who built this engine, and their sites could not be reached from the
 * build environment. So each one is registered as an adapter that reports NEEDS_API and resolves
 * nothing, until an authorised API (base URL + key, see CigramEngineConfig) is configured for it.
 * No link is guessed or fabricated; the diagnostic report says exactly what is missing.
 */
public final class CigramProviders {
    private CigramProviders() { }

    static final String NOTE = "لا توجد واجهة API موثّقة ومصرّح باستخدامها مرفقة بالمشروع لهذا المزود.";

    /** id, display name, host (for the report only; it is never used to build a link). */
    static final String[][] THIRD_PARTY = {
            {"vidfast", "VidFast", "vidfast.vc"},
            {"vidnest", "VidNest", "vidnest.fun"},
            {"videm", "Videm", "videm.xyz"},
            {"vidbolt", "VidBolt", "vidbolt.pro"},
    };

    public static List<CigramSourceProvider> createDefault(CigramEngineConfig config, CigramLibraryData library) {
        ArrayList<CigramSourceProvider> list = new ArrayList<CigramSourceProvider>();
        list.add(new CigramLibraryProvider(library));
        int priority = 10;
        for (String[] p : THIRD_PARTY) {
            list.add(new CigramJsonProvider(p[0], p[1], priority, config, NOTE + " (" + p[2] + ")"));
            priority += 10;
        }
        sort(list);
        return list;
    }

    public static void sort(List<CigramSourceProvider> list) {
        Collections.sort(list, new Comparator<CigramSourceProvider>() {
            @Override public int compare(CigramSourceProvider a, CigramSourceProvider b) {
                return a.priority() == b.priority() ? a.id().compareTo(b.id()) : (a.priority() < b.priority() ? -1 : 1);
            }
        });
    }

    /** One line per provider for the diagnostic report / settings screen. */
    public static String report(List<CigramSourceProvider> providers) {
        StringBuilder sb = new StringBuilder();
        for (CigramSourceProvider p : providers) {
            String st;
            switch (p.status()) {
                case CigramSourceProvider.INTEGRATED: st = "متكامل"; break;
                case CigramSourceProvider.NEEDS_API: st = "يحتاج API"; break;
                case CigramSourceProvider.DISABLED: st = "متوقف"; break;
                default: st = "غير متاح"; break;
            }
            sb.append(p.displayName()).append(" — ").append(st).append(" — ").append(p.statusNote()).append('\n');
        }
        return sb.toString();
    }
}
