# EduPing

**Ustoz bilan tartibli muloqot.** O‘quvchi savol, uy vazifasi, rasm, hujjat yoki ovozli xabarni botga yuboradi. O‘qituvchi shu botda javob beradi. Har murojaatning raqami, sinfi, tarixi va holati bor.

Java 17 · Spring Boot 4.1.2 · PostgreSQL 17 · Flyway · Docker Compose.

## Tayyor imkoniyatlar

- O‘zbekcha va ruscha interfeys: yangi foydalanuvchi til tanlaydi; tanlov saqlanadi. «🌐 Til / Язык» yoki `/language` orqali almashtiriladi. Ustoz va o‘quvchi bildirishnomalarni o‘z tilida oladi.
- Bir o‘qituvchi uchun boshlash; sinflar o‘qituvchiga tegishli, keyinchalik qo‘shimcha o‘qituvchi ID kiritish mumkin.
- Sinf taklif kodi, kodni yangilash, a’zolar ro‘yxati va chiqarish.
- Murojaat ochish, davom ettirish, javob yozish, yopish va sahifalangan tarix.
- Matn, foto, hujjat, video, audio, voice, sticker, animation va video note. Fayl chegarasi **20 MB**.
- Matn va metadata DB’da; faylning o‘zi ham PostgreSQL `bytea` ustunida arxivlanadi, SHA-256 bilan tekshirish mumkin.
- Restartdan keyin yozish rejimi va navbat saqlanadi. Takror Telegram update bir marta ishlanadi.
- Xatoda qayta urinish, Telegram `retry_after`, har chatga soniyasiga ko‘pi bilan bir yuborish, jim bildirishnomalar.
- O‘qituvchi Telegram ID allowlist; har murojaatda alohida ruxsat tekshiruvi. HTTP admin panel yo‘q.

Reja: [PLAN.md](PLAN.md). Buyruqlar: [docs/BOT-CONTRACT.md](docs/BOT-CONTRACT.md). Ishlatish va nosozlik: [docs/OPERATIONS.md](docs/OPERATIONS.md).

## 1. Telegram botni yaratish

Telegram’da tasdiqlangan **[@BotFather](https://t.me/BotFather)** bilan:

1. `/newbot` → nom: **EduPing | O‘quvchi murojaatlari**.
2. Username: `@EduPingBot`. Band bo‘lsa `EduPing_<ism>_bot` kabi noyob nom tanlang; bandligi tekshirilmagan.
3. Tokenni faqat lokal `.env` fayliga kiriting. Uni chatga yoki Git’ga yubormang.
4. `/setuserpic` orqali `assets/logo.png` ni o‘rnating.
5. Tavsif va buyruqlar: [docs/BOTFATHER.md](docs/BOTFATHER.md).

O‘qituvchining **raqamli Telegram ID** raqami kerak. Uni bilsangiz `.env` ga kiriting. Bilmasangiz botni vaqtincha `TEACHER_IDS=0` bilan ishga tushirib, o‘z botingizga `/id` yozing; chiqqan raqamni `.env` ga o‘rnating va `docker compose up -d --force-recreate bot` bilan qayta yarating. `0` hech kimga o‘qituvchi huquqi bermaydi; bu faqat dastlab ID olish uchun. Token va ID ni operatorga xavfsiz kanal bilan bering.

## 2. Serverda ishga tushirish

Docker Engine/Desktop va Docker Compose kerak. Domen va ochiq kirish porti shart emas: bot long polling ishlatadi.

```sh
cp .env.example .env
# .env ni ochib TELEGRAM_BOT_TOKEN, TEACHER_IDS, DATABASE_PASSWORD ni to‘ldiring.
docker compose up -d --build
docker compose logs --tail=100 bot
curl http://127.0.0.1:8080/health/ready
```

Windows PowerShell’da birinchi buyruq: `Copy-Item .env.example .env`. Kuchli, noyob DB paroli tanlang. Docker Compose `.env` dagi `$` belgisini interpolatsiya qilishi mumkin; bunday qiymatni **bitta tirnoq** ichida yozing.

Botni **bir nusxada** ishga tushiring. O‘qituvchi ham, o‘quvchi ham avval botda `/start` bosishi kerak. Eski webhook bor bo‘lsa polling ishlamaydi; Bot API `deleteWebhook` bilan uni o‘chiring, **`drop_pending_updates=true` ishlatmang**.

## 3. Birinchi foydalanish

Hamma narsa tugmalar bilan bajariladi, buyruq yoki raqam terish shart emas.

**Ustoz:** `/start` → «➕ Yangi sinf» → sinf nomini yozadi (masalan `7-A`). Bot taklif havolasini beradi: `https://t.me/EduPingBot?start=KOD`. Uni sinf guruhiga yoki o‘quvchilarga yuboradi.

**O‘quvchi:** havolani bosadi → ism-familiyasini yozadi → «✍️ Yangi savol» → savolini yozadi (rasm, fayl, ovozli xabar ham bo‘ladi) → «✅ Tugatdim».

**Ustoz** tovushli xabar oladi va ostidagi «✍️ Javob berish» ni bosadi, javob yozadi, «✅ Tugatdim». O‘quvchiga javob boradi. Masala hal bo‘lsa ustoz «✅ Yopish» yoki o‘quvchi «✅ Hal bo‘ldi» ni bosadi.

Barcha savollar «📥 Savollar» (ustoz) va «📂 Savollarim» (o‘quvchi) ro‘yxatida turadi. Buyruqlar ro‘yxati: [docs/BOT-CONTRACT.md](docs/BOT-CONTRACT.md). Telefon raqami yoki ustozning Telegram username’i o‘quvchiga ko‘rsatilmaydi.

### Lokal kompyuterda (Docker’siz) ishga tushirish

PostgreSQL’da baza oching va `.env` ga `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` ni yozing. Ilova `.env` ni o‘zi o‘qiydi (IntelliJ’da ham). Loyiha papkasidan ishga tushiring:

```sh
./mvnw -DskipTests package
java -jar target/eduping-1.0.0.jar
```

Bot username boshqacha bo‘lsa, `.env` ga `BOT_USERNAME=...` qo‘shing: taklif havolalari shu nom bilan tuziladi.

## 4. Zaxira va xizmat ko‘rsatish

```sh
sh scripts/backup.sh
sh scripts/restore-check.sh backups/eduping-YYYYMMDDTHHMMSSZ.dump
```

Birinchi buyruq PostgreSQL’ning izchil custom-format dumpini oladi. Ikkinchisi **alohida vaqtinchalik bazaga** tiklab ko‘radi, jonli bazani almashtirmaydi. Fayllar DB ichida bo‘lgani uchun bir dump matn va fayl arxivini birga oladi. Linux yoki Git Bash ishlating; eski PowerShell’da binary dumpni `>` bilan ko‘chirmang.

Backupni kamida har kuni, muhim ish uchun har soatda tashqi shifrlangan saqlovchiga yuborish, 7 kunlik/4 haftalik/12 oylik nusxa saqlash va har oy restore tekshiruvi tavsiya etiladi. **Skriptni qo‘shish avtomatik backup jadvali yoki tashqi nusxa yaratilganini anglatmaydi.** Server egasi jadval va tashqi saqlovni sozlaydi. Bir kunlik backupda disk yo‘qolsa oxirgi backupdan keyingi ma’lumot tiklanmasligi mumkin; kuchliroq talab uchun PostgreSQL WAL/PITR kerak.

## 5. Lokal build va test

JDK 17+ va internet kerak. Maven Wrapper loyihaga qo‘shilgan:

```sh
./mvnw -DskipTests package
# Windows: .\mvnw.cmd -DskipTests package
```

`-DskipTests` faqat build; test o‘tganini bildirmaydi. Test uchun haqiqiy PostgreSQL va lokal Telegram simulyatsiyasi kerak:

```sh
docker compose -f compose.test.yaml up -d --wait
postman simulation run postman/simulations/telegram.sim.yaml --port 4901 --no-history
# Boshqa terminalda:
./mvnw test
```

Testlar faqat nomi `eduping_test` bilan tugaydigan bazani tozalaydi; jonli baza URL’ini bermang. `TEST_DATABASE_URL`, `TEST_DATABASE_USER`, `TEST_DATABASE_PASSWORD`, `TEST_TELEGRAM_BASE_URL` bilan lokal sozlamalarni o‘zgartirish mumkin. Postman CLI 1.77.0 bilan tekshirildi. Testlar Telegram o‘rnidagi HTTP simulyatsiyasi bilan ishlaydi, haqiqiy Telegram akkauntlariga xabar yubormaydi.

HTTP health shartnomasi: `postman/specs/health.yaml`. Collection undan hosil qilingan, umumiy assertions `.resources/definition.yaml` da:

```sh
postman spec lint postman/specs/health.yaml --issueType=syntax
postman collection run postman/collections/EduPing-health
```

## Chegaralar

- Telegram kiruvchi yangilanishlarni cheklangan vaqt saqlaydi; serverni uzoq o‘chirib qo‘yish xavfsiz arxiv o‘rnini bosmaydi.
- Qabul tasdig‘i matn/metadataning saqlanganini bildiradi. Fayl arxivi asinxron; `/view` da `STORED` bo‘lishi kerak. Xatolar `/stats` da.
- Chiqish xabari **kamida bir marta** yetkaziladi: Telegram qabul qilgach DB commitgacha uzilish bo‘lsa takror nusxa bo‘lishi mumkin.
- Tahrir va Telegram’dagi o‘chirishlar arxivga sinxronlanmaydi. Albomlar alohida xabarlar sifatida saqlanadi. Lokatsiya, kontakt, poll va rich-message kabi turlar hozircha murojaat sifatida qabul qilinmaydi.
- Bu versiyada umumiy disk kvotasi va avtomatik retention yo‘q. Fayllar sonini, bazaning o‘sishini va diskni kuzating. Ommaviy foydalanishdan oldin real server, BotFather tokeni bilan matn/fayl/javob/restart sinovi va backup jadvali kerak.
- BotFather’da ro‘yxatdan o‘tkazish, username bandligi, real Telegram va Docker deploy ushbu lokal tekshiruvning bir qismi emas.

## Manbalar

- [Telegram Bot API](https://core.telegram.org/bots/api): `getUpdates`, `sendMessage`, media va `getFile` shartlari.
- [Spring Boot](https://spring.io/projects/spring-boot): runtime va konfiguratsiya.
- [PostgreSQL backup](https://www.postgresql.org/docs/17/backup-dump.html): dump va restore.
