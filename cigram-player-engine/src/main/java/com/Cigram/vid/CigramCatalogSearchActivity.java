package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Search by name: type a movie / series name, see every match with poster, kind and year, pick the
 * right one, and land on the app's own details page (the same page the library titles use).
 *
 * The details page never plays anything by itself: its «شاهد الآن» button goes through
 * CigramWatchNow, which resolves a validated source for exactly the movie / episode that was chosen.
 *
 * Series: every season and episode is loaded from the catalog before the details page opens, so the
 * page already has all of them, in numeric order.
 */
public class CigramCatalogSearchActivity extends Activity {
    private static final long DEBOUNCE_MS = 450L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CigramEngine engine;
    private EditText input;
    private TextView statusView;
    private TextView retryView;
    private TextView attribution;
    private ProgressBar spinner;
    private ListView list;
    private final ArrayList<CigramMediaRef> results = new ArrayList<CigramMediaRef>();
    private ResultsAdapter adapter;
    private Runnable pending;
    private boolean opening;
    private boolean destroyed;
    private String lastQuery = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = CigramEngine.get(this);
        try { getWindow().setStatusBarColor(CigramDesign.BG_APP); getWindow().setNavigationBarColor(CigramDesign.BG_APP); } catch (Throwable ignored) { }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CigramDesign.BG_APP);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(16), dp(12), dp(16), 0);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("→", 24, CigramDesign.TEXT, true);
        back.setPadding(dp(4), dp(4), dp(12), dp(4));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        top.addView(back);
        TextView heading = text("بحث عن فيلم أو مسلسل", 18, CigramDesign.TEXT, true);
        heading.setOnLongClickListener(new View.OnLongClickListener() {          // owner / developer: where the catalog lives
            @Override public boolean onLongClick(View v) { showCatalogSettings(); return true; }
        });
        top.addView(heading);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        input = new EditText(this);
        input.setHint("اكتب اسم الفيلم أو المسلسل");
        input.setHintTextColor(CigramDesign.TEXT_3);
        input.setTextColor(CigramDesign.TEXT);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setTypeface(font());
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setBackground(round(CigramDesign.SURFACE_HI, 14, CigramDesign.GHOST_STROKE));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.topMargin = dp(12);
        root.addView(input, ip);

        statusView = text("", 13, CigramDesign.TEXT_3, false);
        statusView.setPadding(0, dp(12), 0, dp(4));
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        retryView = text("إعادة المحاولة", 14, Color.WHITE, true);
        retryView.setPadding(dp(18), dp(10), dp(18), dp(10));
        retryView.setBackground(round(CigramDesign.ACCENT, 14, 0));
        retryView.setVisibility(View.GONE);
        retryView.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runSearch(input.getText().toString()); } });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(retryView, rp);

        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(32), dp(32));
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.topMargin = dp(8);
        root.addView(spinner, sp);

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        adapter = new ResultsAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) { if (pos >= 0 && pos < results.size()) open(results.get(pos)); }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        attribution = text("This product uses the TMDB API but is not endorsed or certified by TMDB.", 10, CigramDesign.TEXT_3, false);
        attribution.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        attribution.setPadding(0, dp(6), 0, dp(8));
        attribution.setVisibility(View.GONE);
        root.addView(attribution, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) { schedule(s.toString()); }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                if (action == EditorInfo.IME_ACTION_SEARCH) { runSearch(input.getText().toString()); hideKeyboard(); return true; }
                return false;
            }
        });
        String q = getIntent() == null ? null : getIntent().getStringExtra("q");
        if (q != null && q.trim().length() > 0) input.setText(q);
        else showHint();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ================================================================== search

    private void schedule(final String q) {
        if (pending != null) main.removeCallbacks(pending);
        pending = new Runnable() { @Override public void run() { runSearch(q); } };
        main.postDelayed(pending, DEBOUNCE_MS);
    }

    private void runSearch(String raw) {
        final String q = raw == null ? "" : raw.trim();
        lastQuery = q;
        retryView.setVisibility(View.GONE);
        if (q.length() < 2) { results.clear(); adapter.notifyDataSetChanged(); showHint(); return; }
        spinner.setVisibility(View.VISIBLE);
        statusView.setText("جاري البحث…");
        engine.catalog.search(q, new CigramMediaCatalog.Callback<CigramMediaCatalog.SearchResult>() {
            @Override public void onResult(CigramMediaCatalog.SearchResult r) {
                if (destroyed) return;
                spinner.setVisibility(View.GONE);
                results.clear();
                results.addAll(r.results);
                adapter.notifyDataSetChanged();
                boolean tmdb = false;
                for (CigramMediaRef m : r.results) if (CigramMediaRef.ORIGIN_TMDB.equals(m.origin)) tmdb = true;
                attribution.setVisibility(tmdb ? View.VISIBLE : View.GONE);
                if (r.results.isEmpty() && !r.warnings.isEmpty()) {
                    statusView.setText("تعذر البحث في الفهرس: " + r.warnings.get(0));
                    retryView.setVisibility(View.VISIBLE);
                } else if (r.results.isEmpty()) {
                    statusView.setText("لا توجد نتائج لـ «" + r.query + "». جرّب كتابة الاسم بطريقة أخرى أو أضف سنة الإصدار.");
                } else {
                    String msg = r.results.size() + " نتيجة" + (r.ambiguous() ? " — بعض الأعمال متشابهة الاسم، اختر الصحيح بالسنة والملصق." : "");
                    if (!r.warnings.isEmpty()) msg += "\n" + r.warnings.get(0);
                    statusView.setText(msg);
                }
            }
            @Override public void onError(CigramEngineException e) {
                if (destroyed) return;
                spinner.setVisibility(View.GONE);
                results.clear();
                adapter.notifyDataSetChanged();
                statusView.setText(e.kind == CigramEngineException.NOT_CONFIGURED ? "البحث في الفهرس غير مفعّل بعد." : e.getMessage());
                retryView.setVisibility(e.retryable() ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void showHint() {
        spinner.setVisibility(View.GONE);
        attribution.setVisibility(View.GONE);
        statusView.setText(engine.config.tmdbConfigured()
                ? "اكتب اسم الفيلم أو المسلسل (ويمكنك إضافة السنة، مثل: Dune 2021)."
                : "اكتب اسماً للبحث في مكتبة التطبيق. (البحث في الفهرس العالمي يحتاج ضبط مفتاح قاعدة البيانات.)");
    }

    // ================================================================== catalog settings (owner / developer)

    /**
     * Where the metadata comes from. A backend that proxies TMDB (so the key never ships inside the app) is
     * the right setup for a published app: put its address in "العنوان". A direct read token is for testing only.
     * Both are kept in the app's private preferences and are never logged.
     */
    private void showCatalogSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText base = new EditText(this);
        base.setHint("العنوان (مثال: https://api.themoviedb.org/3 أو خادمك الوسيط)");
        base.setText(engine.config.get("tmdb.base", ""));
        base.setSingleLine(true);
        base.setTextDirection(View.TEXT_DIRECTION_LTR);
        final EditText token = new EditText(this);
        token.setHint(engine.config.tmdbToken().length() > 0 ? "المفتاح محفوظ — اتركه فارغاً للإبقاء عليه" : "Read Access Token (للاختبار فقط)");
        token.setSingleLine(true);
        token.setTextDirection(View.TEXT_DIRECTION_LTR);
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(base);
        box.addView(token);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("إعداد الفهرس")
                .setMessage(engine.providerReport())
                .setView(box)
                .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        engine.config.put("tmdb.base", base.getText().toString().trim());
                        String t = token.getText().toString().trim();
                        if (t.length() > 0) engine.config.put("tmdb.token", t);
                        engine.catalog.clearCache();
                        showHint();
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    // ================================================================== open the details page

    private void open(final CigramMediaRef ref) {
        if (opening) return;                  // a second tap while the first is loading
        opening = true;
        hideKeyboard();
        if (!ref.isSeries()) { launchDetails(ref, null); return; }
        spinner.setVisibility(View.VISIBLE);
        statusView.setText("جاري تحميل المواسم والحلقات…");
        engine.catalog.allEpisodes(ref, new CigramMediaCatalog.Callback<List<CigramEpisode>>() {
            @Override public void onResult(List<CigramEpisode> eps) {
                if (destroyed) return;
                spinner.setVisibility(View.GONE);
                if (eps.isEmpty()) {
                    opening = false;
                    statusView.setText("لا توجد حلقات متاحة لهذا المسلسل في الفهرس.");
                    return;
                }
                launchDetails(ref, eps);
            }
            @Override public void onError(CigramEngineException e) {
                if (destroyed) return;
                opening = false;
                spinner.setVisibility(View.GONE);
                statusView.setText("تعذر تحميل حلقات المسلسل: " + e.getMessage());
                retryView.setVisibility(View.VISIBLE);
                retryView.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { retryView.setVisibility(View.GONE); open(ref); }
                });
            }
        });
    }

    private void launchDetails(CigramMediaRef ref, List<CigramEpisode> episodes) {
        try {
            Intent intent = new Intent(this, Class.forName(getPackageName() + ".DetailsActivity"));
            JSONObject json = ref.toDetailsJson();
            CigramStage1InitProvider.putMinimalDetailsExtras(intent, json, ref.title);      // id + type (and warms the details cache)
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = json.optString(k, "");
                if (v.length() > 0 && !"id".equals(k) && !"type".equals(k)) intent.putExtra(k, v);
            }
            if (episodes != null) intent.putExtra("series_episodes_json", episodesJson(ref, episodes));
            startActivity(intent);
        } catch (Throwable t) {
            CigramLog.w("search", "details launch failed", t);
            Toast.makeText(this, "تعذر فتح صفحة التفاصيل", Toast.LENGTH_SHORT).show();
        }
        opening = false;
    }

    /** The extra travels in an Intent (two screens deep), so it must stay far below the 1 MB binder limit. */
    static final int MAX_EPISODES_JSON = 150000;

    /**
     * Episodes the way the details page and the player read them: flat array, one object per episode, no video link.
     * A very long series is shrunk in steps (episode pictures first, then titles) instead of being cut, so every
     * season and episode number always survives.
     */
    static String episodesJson(CigramMediaRef ref, List<CigramEpisode> eps) {
        String out = buildEpisodesJson(ref, eps, true, true);
        if (out.length() > MAX_EPISODES_JSON) out = buildEpisodesJson(ref, eps, false, true);
        if (out.length() > MAX_EPISODES_JSON) out = buildEpisodesJson(ref, eps, false, false);
        return out;
    }

    private static String buildEpisodesJson(CigramMediaRef ref, List<CigramEpisode> eps, boolean pictures, boolean titles) {
        JSONArray arr = new JSONArray();
        // series_title / series_id are not repeated per episode: the details page and the player take them from the series
        for (int i = 0; i < eps.size() && i < 3000; i++) {
            CigramEpisode e = eps.get(i);
            try {
                JSONObject o = new JSONObject();
                o.put("season", String.valueOf(e.season));
                if (e.number > 0) o.put("episode", String.valueOf(e.number));
                o.put("episode_id", e.episodeId);
                if (titles) o.put("title", e.title.length() > 0 ? e.title : e.label());
                if (pictures && e.still.length() > 0) o.put("backdrop", e.still);
                arr.put(o);
            } catch (Throwable ignored) { }
        }
        return arr.toString();
    }

    // ================================================================== rows

    private final class ResultsAdapter extends BaseAdapter {
        @Override public int getCount() { return results.size(); }
        @Override public Object getItem(int i) { return results.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View convert, ViewGroup parent) {
            Row row;
            if (convert == null) { row = new Row(); convert = row.root; convert.setTag(row); } else row = (Row) convert.getTag();
            CigramMediaRef r = results.get(i);
            row.title.setText(r.title);
            String kind = r.isSeries() ? "مسلسل" : "فيلم";
            StringBuilder meta = new StringBuilder(kind);
            if (r.year > 0) meta.append(" • ").append(r.year);
            if (r.rating > 0) meta.append(" • ★ ").append(String.format(java.util.Locale.US, "%.1f", r.rating));
            if (r.originalTitle.length() > 0 && !r.originalTitle.equals(r.title)) meta.append("\n").append(r.originalTitle);
            row.meta.setText(meta.toString());
            row.badge.setVisibility(CigramMediaRef.ORIGIN_LIBRARY.equals(r.origin) ? View.VISIBLE : View.GONE);
            row.overview.setText(r.overview);
            row.overview.setVisibility(r.overview.length() > 0 ? View.VISIBLE : View.GONE);
            row.poster.setImageDrawable(null);
            if (r.poster.length() > 0) {
                try { AdsMedia.loadInto(CigramCatalogSearchActivity.this, row.poster, r.poster, dp(120), false); } catch (Throwable ignored) { }
            }
            return convert;
        }
    }

    private final class Row {
        final LinearLayout root = new LinearLayout(CigramCatalogSearchActivity.this);
        final ImageView poster = new ImageView(CigramCatalogSearchActivity.this);
        final TextView title = text("", 15, CigramDesign.TEXT, true);
        final TextView meta = text("", 12, CigramDesign.TEXT_2, false);
        final TextView badge = text("متوفر في مكتبة Cigram", 11, Color.WHITE, true);
        final TextView overview = text("", 12, CigramDesign.TEXT_3, false);

        Row() {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);          // physical order: text on the left, poster on the right
            root.setPadding(0, dp(8), 0, dp(8));
            LinearLayout col = new LinearLayout(CigramCatalogSearchActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            col.setPadding(0, 0, dp(12), 0);
            title.setGravity(Gravity.RIGHT);
            meta.setGravity(Gravity.RIGHT);
            overview.setGravity(Gravity.RIGHT);
            overview.setMaxLines(3);
            overview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            badge.setBackground(round(CigramDesign.ACCENT, 8, 0));
            col.addView(title);
            col.addView(meta);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.topMargin = dp(4);
            bp.gravity = Gravity.RIGHT;
            col.addView(badge, bp);
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
            op.topMargin = dp(4);
            col.addView(overview, op);
            root.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(round(CigramDesign.SURFACE, 10, 0));
            root.addView(poster, new LinearLayout.LayoutParams(dp(70), dp(104)));
        }
    }

    // ------------------------------------------------------------------ helpers

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Typeface font() {
        try { return CigramDesign.font(this); } catch (Throwable t) { return Typeface.DEFAULT; }
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font(), bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private GradientDrawable round(int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && input != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Throwable ignored) { }
    }
}
