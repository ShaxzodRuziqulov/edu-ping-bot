# EduPing — amalga oshirish rejasi

2026-10-28. Maqsad: o‘quvchi murojaatlarini o‘qituvchining shaxsiy yozishmalaridan ajratish.

## Qabul qilingan qarorlar

- Hozir bir o‘qituvchi; kelajakda har bir sinf o‘z o‘qituvchisiga tegishli bo‘ladi.
- O‘qituvchi Telegram ID allowlist orqali belgilanadi. O‘quvchi bir martalik emas, aylantirish mumkin bo‘lgan tasodifiy sinf taklif kodi bilan kiradi.
- Shaxsiy bot chatlari. Guruh xabarlari qayta ishlanmaydi.
- O‘quvchi sinf tanlaydi, /new orqali murojaat ochadi, keyingi xabarlar shu murojaatga yoziladi. /done yozishni tugatadi. /my va /view orqali tarixni ko‘radi.
- O‘qituvchi /inbox, /view, /reply, /close orqali ishlaydi. Yetkazish xabarlari jim yuboriladi; sinf va murojaat raqami bilan ajratiladi.
- Matn, foto, video, hujjat, audio, ovozli xabar, sticker va video note. Har bir fayl ko‘pi bilan 20 MB. Qo‘llanmagan tur rad etiladi va izoh beriladi.
- PostgreSQL: durable inbox, transactional outbox, update_id deduplication, fayl baytlari, Flyway migratsiyalari, cheklangan retry va xato navbati.
- Kirish (Telegram update) bazaga tushmasdan polling offset oldinga siljimaydi. Biznes amal va javob navbati bitta tranzaksiyada.
- Yetkazish kamida bir marta: Telegram muvaffaqiyati bilan DB commit orasida uzilish bo‘lsa takror xabar mumkin. Exactly-once va mutlaq yo‘qolmaslik va’da qilinmaydi.
- Ishga tushirish: Java 17+, Spring Boot 4.1.2, PostgreSQL 17, Docker Compose. Faqat bir polling instance, PostgreSQL advisory lock bilan cheklanadi.
- Bot API tokeni va DB paroli faqat muhitdan. Ochiq HTTP admin interfeys yo‘q.

## Bosqichlar va tekshiruv

1. Bot buyruqlari va health HTTP shartnomasini yozish.
2. SQL migratsiya va atomar qabul/navbatni qurish.
3. Sinf, a’zolik, murojaat, javob, yopish va ruxsat tekshiruvlari.
4. Telegram HTTP adapteri, fayl arxivi, retry, rate limit.
5. Nom, logo, BotFather matnlari va foydalanish yo‘riqnomasi.
6. PostgreSQL bilan integratsion testlar; Telegram stand-in bilan haqiqiy HTTP almashinuvi; build.
7. Docker, backup/restore, monitoring va amaliy ishga tushirish yo‘riqnomasi.

## Foydalanuvchi kiritadigan sozlamalar

BotFather tokeni, o‘qituvchi Telegram ID, kuchli DB paroli, ishlab turadigan server. Ularni chatda yuborish shart emas. Telegram ro‘yxatdan o‘tkazish va haqiqiy serverga deploy ushbu lokal build bilan avtomatik bajarilmaydi.

## Bajarilgan holat

1–7-bosqichlarning lokal kod, shartnoma, brend va yo‘riqnoma qismlari tayyor. 17 integratsion test, 6 Postman assertion, worker’li JAR smoke testi va alohida bazaga binary backup/restore tekshiruvi muvaffaqiyatli. Batafsil: docs/VERIFICATION.md. Docker ushbu muhitda yo‘q; Docker deploy, real Telegram va avtomatik tashqi backup server sozlangandan keyin tekshiriladi.
