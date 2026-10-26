package com.Cigram.vid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** One season of a series (number 0 = specials). */
public final class CigramSeason {
    public final int number;
    public final String name;
    public final int episodeCount;
    public final String poster;
    public final int year;

    public CigramSeason(int number, String name, int episodeCount, String poster, int year) {
        this.number = Math.max(0, number);
        this.name = CigramMediaRef.nz(name);
        this.episodeCount = Math.max(0, episodeCount);
        this.poster = CigramMediaRef.nz(poster);
        this.year = year;
    }

    public boolean isSpecial() { return number == 0; }

    public String label() {
        if (name.length() > 0) return name;
        return isSpecial() ? "حلقات خاصة" : "الموسم " + number;
    }

    /** 1, 2, 3 ... then specials. */
    public static List<CigramSeason> sorted(List<CigramSeason> in) {
        ArrayList<CigramSeason> out = new ArrayList<CigramSeason>();
        if (in != null) {
            for (CigramSeason s : in) {
                boolean dup = false;
                for (CigramSeason t : out) if (t.number == s.number) { dup = true; break; }
                if (!dup) out.add(s);
            }
        }
        Collections.sort(out, new Comparator<CigramSeason>() {
            @Override public int compare(CigramSeason a, CigramSeason b) {
                int ra = CigramEpisode.seasonRank(a.number), rb = CigramEpisode.seasonRank(b.number);
                return ra == rb ? 0 : (ra < rb ? -1 : 1);
            }
        });
        return out;
    }
}
