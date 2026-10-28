# Ishlatish va tiklash

## Saqlash yo‘li

Telegram → `inbox` va `app_state` bitta tranzaksiyada → biznes yozuvlar va `outbox` bitta tranzaksiyada → Telegram javobi / fayl baytlari → SENT / STORED.

PostgreSQL durable inbox qayta ishga tushganda davom etadi. `update_id` primary key takror biznes yozuviga yo‘l qo‘ymaydi. Bir foydalanuvchining kechiktirilgan yangilanishi o‘zidan keyingisini ushlab turadi; boshqalarning xabarlari davom etadi. Sinf va murojaat xabarlari saqlanadi, muvaffaqiyatli ishlangan texnik inbox payloadi tozalanadi; processed ID saqlanadi. Qo‘llanmagan xabar turi tushuntirish bilan rad etiladi.

`outbox` chat ichidagi tartibni saqlaydi. Retry eksponensial, maksimum bir soat oraliq, maksimum 12 urinish. Telegram 429 qaytargan `retry_after` bundan katta bo‘lsa ham bajariladi. 400/401/403/404/413 doimiy xato sifatida FAILED. `/stats` xato sonlarini beradi. FAILED yozuvni operator tekshirgandan keyin qayta navbatga qo‘yish mumkin; muvaffaqiyatli xabar takror yuborilishi ehtimolini hisobga oling.

Bir polling instance. PostgreSQL advisory lock bir paytda ikki `getUpdates` so‘rovini cheklaydi, ammo bir nechta bot worker replica ishlatish ushbu versiyaning qo‘llanadigan joylashtirish usuli emas.

## Kundalik tekshiruv

- `/health/live` process ishlayotganini, `/health/ready` bazaga ulanishni bildiradi. **Telegram bilan aloqa sog‘lomligini tasdiqlamaydi.**
- `/stats`: FAILED > 0 yoki PENDING uzoq vaqt kamaymasa tekshiring.
- Server diskini va `pg_database_size` ni kuzating; disk to‘lishi kiruvchi xabarlarni saqlashni to‘xtatadi.
- Backup yoshi va tashqi nusxani kuzating. Log fayllar rotatsiyasi Compose’da bor.
- Telegram uzilishida foydalanuvchiga tasdiq kelmasligi mumkin. Telegram update saqlash muddati sabab uzoq uzilish uchun operatorga alohida tashqi health monitoring o‘rnating.

SQL tekshiruvlar (operator konsolida, bazani internetga ochmang):

```sql
SELECT state, count(*), min(created_at) FROM inbox GROUP BY state;
SELECT state, error_code, count(*), min(created_at) FROM outbox GROUP BY state,error_code;
SELECT archive_state, count(*) FROM ticket_message GROUP BY archive_state;
SELECT pg_size_pretty(pg_database_size(current_database()));
```

## Nosozliklar

**401:** token bekor qilingan yoki noto‘g‘ri. BotFather’da yangilang, `.env` ga yozing va `docker compose up -d --force-recreate bot`.

**409:** eski webhook yoki boshqa polling jarayoni. Avval boshqa jarayonni to‘xtating. `deleteWebhook` so‘rovini kerak bo‘lsagina yuboring; pending updatelarni tashlamang.

**403:** foydalanuvchi botni bloklagan yoki hali /start bosmagan. Undan botni ochishni so‘rang. Xabar DB’dan yo‘qolmaydi. `/view` tarixni qayta ko‘rsatadi.

**Fayl FAILED:** `outbox.error_code`, hajm va Telegram ulanishini tekshiring. `file_id`, original nom, MIME saqlangan; `/view` media nusxasini Telegram’dagi file_id orqali ko‘rsatadi. Mahalliy arxiv baytlari `ticket_message.file_bytes` da, zaxiradan ham olinadi. Bot tokeni almashsa eski file_id bilan qayta yuborish Telegram chekloviga uchrashi mumkin; arxivni operator bazadan eksport qiladi. SQL baytlarini begona chatga yubormang.

Tanlangan jobni qayta urinish (faqat muammo tuzatilgach, haqiqiy ID bilan; bot worker’ni avval to‘xtating):

```sql
BEGIN;
UPDATE ticket_message SET archive_state='PENDING'
WHERE id=(SELECT message_id FROM outbox WHERE id=123 AND job_type='ARCHIVE' AND state='FAILED');
UPDATE outbox SET state='PENDING',attempts=0,available_at=now(),error_code=NULL
WHERE id=123 AND state='FAILED';
COMMIT;
```

Inbox FAILED bo‘lsa uning sababini tuzating. `UPDATE inbox SET state='PENDING',attempts=0,available_at=now(),error_code=NULL WHERE update_id=... AND state='FAILED'` bilan faqat o‘sha yozuv qayta ishlanadi. Keyingi buyruqlar allaqachon bajarilgan bo‘lishi mumkin; tarix va sessionni tekshiring.

## Backup va haqiqiy tiklash

`scripts/backup.sh` va `restore-check.sh` ni README bo‘yicha ishlating. Docker volume backup hisoblanmaydi. Dump faylida shaxsiy ma’lumotlar bor; fayl ruxsatini cheklang, tashqi nusxani shifrlang. Saqlash jadvali server operatoriga bog‘liq.

Favqulodda tiklashda: botni to‘xtating, hozirgi bazadan imkon bo‘lsa alohida dump oling, tanlangan backupni yangi bo‘sh bazaga `pg_restore --exit-on-error --no-owner` bilan tiklang. Tekshirilgan yangi bazaga `DATABASE_URL` ni o‘tkazing. Ticket/message/file_bytes soni va nazorat fayllari SHA-256 qiymatini tekshiring. Birinchi navbatda test Telegram akkauntida kirish va javobni sinang. Backupdagi offset va navbat eskiroq bo‘lgani uchun o‘sha vaqtdan keyingi ayrim Telegram xabarlari qayta kelishi yoki allaqachon Telegram navbatidan chiqib ketgan bo‘lishi mumkin.

## Ma’lumotlarni saqlash siyosati

Avtomatik o‘chirish yo‘q: maktab/o‘qituvchi saqlash muddatini va kim operatorligini o‘quvchiga bildiradi. O‘chirish so‘rovida shaxsni tekshiring, kerakli sinf doirasini belgilang, botni to‘xtating, tegishli navbat, session, ticket_message, ticket, membership va shundan keyin kerak bo‘lsa app_user yozuvlarini bitta tranzaksiyada o‘chiring. Inbox payloadlari, audit actor_id va outbox payloadlarida ham ID yoki matn bo‘lishi mumkin; ularni scope bilan tekshiring. Backuplarda nusxa belgilangan muddatgacha qolishi, eski backup tiklansa o‘chirishni qayta bajarish zarurligini qayd eting. SQL’dagi umumiy DELETE namunasini tasdiqsiz jonli bazada qo‘llamang.

## Hajm va keyingi kengayish

Bitta o‘qituvchi uchun DB ichidagi fayl arxivi backupni izchil qiladi. Disk kvotasi, avtomatik retention, tashqi fayl storage, web admin panel va o‘qituvchini o‘zi ro‘yxatdan o‘tkazish hozir yo‘q. O‘qituvchilarni qo‘shish — `TEACHER_IDS` ro‘yxatiga ID qo‘shish va botni qayta yaratish. Har o‘qituvchi faqat o‘z sinflaridagi ticketlarni ochadi; `/stats` dagi operatsion navbat soni tizim bo‘yicha umumiy.

O‘qituvchini almashtirishda allowlistdan olib tashlashning o‘zi yetmaydi: mavjud classroom.teacher_id yo‘nalishini va eski o‘qituvchiga pending outbox yozuvlarini ham o‘zgartirish kerak. Botni to‘xtatib, sinflarni yangi ID ga operator orqali ko‘chiring, pending yuborishlarni tekshiring, eski teacher sessionlarini o‘chiring va keyin yangi allowlist bilan qayta ishga tushiring. O‘qituvchilar uchun mustaqil self-service obuna/ro‘yxatdan o‘tish yo‘q.
