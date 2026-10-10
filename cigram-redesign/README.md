# CIGRAM — redesigned landing site

Static site, no build step. Open `index.html`.

- `index.html` — landing page (all release data lives in the `CONFIG` object at the bottom; JSON-LD in `<head>` must be updated by hand on each release).
- `privacy.html` — privacy policy (content unchanged, restyled).
- `app.html` — redirect stub to `index.html` (was a byte-identical duplicate of the landing page).
- `assets/` — app icon and developer logo, extracted from the old inlined base64.
