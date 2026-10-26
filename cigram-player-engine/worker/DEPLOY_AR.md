# نشر وكيل الفهرس (Cloudflare Worker)

الغرض: يبقى مفتاح TMDB سرّاً في الـ Worker، والتطبيق يكلّم الـ Worker فقط.

1. أنشئ Worker جديداً (أو استعمل واحداً موجوداً غير الإدارة) والصق `cigram-catalog-proxy.js` (صيغة module).
2. الأسرار (`wrangler secret put` أو لوحة Cloudflare):
   * `TMDB_READ_TOKEN` — «API Read Access Token» من حسابك في TMDB (أو `TMDB_API_KEY` v3).
   * `APP_KEY` — اختياري: نص تضعه أيضاً في التطبيق (`tmdb.appkey`). هو مجرد عائق أمام الاستعمال العابر، **لا يُعدّ سرّاً** لأنه يسافر داخل التطبيق.
3. أضف قاعدة Rate Limiting من لوحة Cloudflare على النطاق (مثلاً 60 طلباً/دقيقة/IP).
4. في التطبيق: «ابحث في الفهرس العالمي» ← اضغط مطوّلاً على العنوان ← العنوان: `https://<اسم-الـWorker>.workers.dev/3`.
5. اختبر: `curl "https://<worker>/3/search/multi?query=dune&language=ar"` يجب أن يرجع JSON، وأي مسار آخر (`/3/person/1`) يرجع 404.

الوكيل يقبل خمسة مسارات GET فقط ومعاملات محددة (`query`, `language`, `page`, `append_to_response`)، يفرض `include_adult=false`، لا يمرّر بقية استعلام المستدعي، ولا يكشف للتطبيق تفاصيل أخطاء المفتاح (يرجع `502 upstream_auth`).

الاختبار المحلي: `node --test worker/cigram-catalog-proxy.test.mjs` (بلا اعتماديات؛ TMDB مستبدل بـ fetch مزيّف).
