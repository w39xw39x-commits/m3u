# M3U — منظّم قنوات IPTV لرسيفر Tiger (Android 10+)

تطبيق APK يثبت على الرسيفر: يجلب قنوات أي اشتراك IPTV (رابط M3U / Xtream / ملف)، يصنفها تلقائياً في باقات
(4K، رياضة، أخبار، إسلامي، أفلام، مسلسلات، أطفال، طبيعة ووثائقي، منوع + باقات خاصة)، ترتبها بالريموت،
ثم يحفظ ملف `tiger.m3u` لاستيراده في IP Channels.

## بناء الـ APK (مرة واحدة — مجاناً على GitHub)
1. أنشئ حساباً في github.com ثم **New repository** (اجعله Public) باسم `m3u`.
2. اضغط **uploading an existing file** وارفع كل محتوى هذا المجلد (بما فيه مجلد `.github`).
3. من **Settings → Secrets and variables → Actions → New repository secret** أضف:
   - `KEYSTORE_BASE64` = محتوى ملف KEYSTORE_BASE64.txt
   - `KEYSTORE_PASSWORD` = محتوى ملف KEYSTORE_PASSWORD.txt
4. من تبويب **Actions** شغّل **Build M3U APK** (أو أي رفع جديد يبنيه تلقائياً). خلال ~4 دقائق يظهر الإصدار في **Releases**.

رابط التحميل الثابت (لا يتغير مع التحديثات):
`https://github.com/<اسم-حسابك>/m3u/releases/latest/download/M3U.apk`

## كود Downloader
1. افتح aftv.news من الكمبيوتر وألصق الرابط الثابت أعلاه، فيعطيك رقم كود.
2. في الرسيفر: Downloader ← اكتب الكود ← ثبّت.

## الاستخدام
- الاشتراكات: الصق الرابط أو بيانات Xtream أو اختر ملف m3u من الفلاش.
- الباقات: فعّل/أوقف، رتّب (OK ثم ▲▼)، عدّل الاسم/اللون/الكلمات.
- إرسال للرسيفر: يحفظ `tiger.m3u` في الذاكرة الداخلية و Download وفلاش USB، ثم افتح IP Channels واستورده.
- من الكمبيوتر: افتح الرابط الظاهر في الشريط الجانبي (http://IP-الرسيفر:8686) للترتيب بالماوس.
