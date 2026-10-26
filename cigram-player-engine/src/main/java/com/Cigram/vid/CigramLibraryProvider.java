package com.Cigram.vid;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cigram's own library as a source provider: the movie / episode links the admin app published
 * (R2 / own backend). Always integrated and always first: it is the owner's own content.
 *
 * For an episode it returns ONLY the link of that season + episode; it never returns the link of a
 * neighbouring episode.
 */
public final class CigramLibraryProvider extends CigramBaseProvider {
    public static final String ID = "library";

    private final CigramLibraryData data;

    public CigramLibraryProvider(CigramLibraryData data) {
        super(ID, "مكتبة Cigram", 0);
        this.data = data;
    }

    @Override public int status() { return INTEGRATED; }

    @Override public String statusNote() { return "المكتبة الداخلية للتطبيق."; }

    @Override public boolean supports(CigramMediaRequest r) {
        return r.libraryItem != null || r.libraryId.length() > 0 || r.tmdbId.length() > 0;
    }

    @Override protected List<CigramPlaybackSource> doResolve(CigramMediaRequest r, CigramCancel cancel) throws CigramEngineException {
        JSONObject target = r.libraryItem;
        // a title opened from the external catalog carries no link; the library may still hold it (same tmdb id)
        if (target == null || CigramLibraryItems.video(target).length() == 0) {
            JSONObject found = lookup(r);
            if (found != null) target = found;
        }
        if (target == null) return Collections.emptyList();

        String video = CigramLibraryItems.video(target);
        // A series item itself carries no episode link; if it does, it must not be used for an episode.
        if (r.isEpisode() && CigramLibraryItems.isSeries(target) && !hasEpisodeIdentity(target)) return Collections.emptyList();

        if (video.length() == 0) {
            // published from the admin as «من المزوّد»: the provider's own player page, opened as an embed
            String embed = CigramLibraryItems.text(target, "embed_url");
            if (!embed.startsWith("https://")) return Collections.emptyList();
            CigramPlaybackSource e = new CigramPlaybackSource();
            e.playbackUri = embed;
            e.markFailed(CigramPlaybackSource.EMBED_ONLY, "مشغّل المزوّد (صفحة تضمين).");
            ArrayList<CigramPlaybackSource> one = new ArrayList<CigramPlaybackSource>();
            one.add(e);
            return one;
        }

        CigramPlaybackSource s = new CigramPlaybackSource();
        s.playbackUri = CigramStreamLink.cleanUrl(video);
        s.headers.putAll(CigramStreamLink.headersOf(video));
        String sub = CigramLibraryItems.subtitle(target);
        if (sub.length() > 0) s.supportedSubtitles.add(new CigramPlaybackSource.Subtitle(sub, "ar", "العربية", true));
        ArrayList<CigramPlaybackSource> out = new ArrayList<CigramPlaybackSource>();
        out.add(s);
        // more direct links of the same movie / episode saved by the admin («من المزوّد»): tried in order when one fails
        for (String line : CigramLibraryItems.text(target, "video_backups").split("\n")) {
            String b = line.trim();
            if (!b.startsWith("https://") && !b.startsWith("http://")) continue;
            CigramPlaybackSource alt = new CigramPlaybackSource();
            alt.playbackUri = CigramStreamLink.cleanUrl(b);
            alt.headers.putAll(CigramStreamLink.headersOf(b));
            alt.displayName = "مصدر احتياطي";
            out.add(alt);
            if (out.size() >= 9) break;
        }
        return out;
    }

    private static boolean hasEpisodeIdentity(JSONObject o) {
        return CigramLibraryItems.text(o, CigramLibraryItems.EPISODE_KEYS).length() > 0;
    }

    private JSONObject lookup(CigramMediaRequest r) {
        JSONObject item = CigramLibraryItems.find(data.items(), r.libraryId, r.tmdbId);
        if (item == null) return null;
        if (!r.isEpisode()) return item;
        List<CigramEpisode> sorted = new ArrayList<CigramEpisode>();
        List<JSONObject> raw = data.episodesOf(item);
        for (int i = 0; i < raw.size(); i++) sorted.add(CigramLibraryItems.toEpisode(raw.get(i), i));
        for (int i = 0; i < raw.size(); i++) {
            CigramEpisode e = sorted.get(i);
            boolean sameNumber = r.episode > 0 && e.number == r.episode && e.season == r.season;
            boolean sameId = r.episodeId.length() > 0 && r.episodeId.equals(e.episodeId);
            if (sameNumber || sameId) return raw.get(i);
        }
        return null;
    }
}
