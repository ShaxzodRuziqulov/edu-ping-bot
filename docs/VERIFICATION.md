# Tekshiruv natijalari

## 2026-10-29 — O‘zbekcha va ruscha interfeys (V4)

`mvnw.cmd -o verify`: **37 test, 0 failure, 0 error, 0 skipped; BUILD SUCCESS**. Yakuniy `target/eduping-1.0.0.jar` yig‘ildi.

Tekshiruv alohida, vaqtinchalik PostgreSQL 17.4 klasteridagi `eduping_test` bazasi va mavjud `postman/mocks/telegram/fixture.js` HTTP fixture’i bilan bajarildi. Postman CLI simulyatsiyasi dataset-daemon `0xc0000008` xatosi bilan ishga tushmadi; shu fixture Node orqali, JSON body va `/telegram` route prefiksi adapteri bilan xizmat qildi. Bu natija Postman simulation run muvaffaqiyati sifatida hisoblanmaydi. `postman skills status` tarmoqdagi `ENOTFOUND` tufayli bajarilmadi. Cloudga hech narsa yuborilmadi.

Yangi qamrov: birinchi kirishda majburiy til tanlash, noto‘g‘ri tilni rad etish, taklif havolasi va oddiy kodni eslab qolish, til tanlovining service qayta yaratilganda saqlanishi, yozish qoralamasi/ism/sinf kiritish holatini yo‘qotmasdan almashtirish, ruscha menyu va xatolar, yordam/maxfiylik/tarix, har ikki tomonning mustaqil tilidagi bildirishnomalar, foydalanuvchi matnini tarjima qilmaslik hamda `setMyCommands` ning lokal HTTP orqali yetkazilishi.

V4 migratsiya vaqtinchalik jadvalda ham tekshirildi: mavjud foydalanuvchiga `uz` yozildi, keyin yaratilgan foydalanuvchida til tanlanmaguncha `NULL` qoldi. Tekshiruv tranzaksiyasi rollback qilindi. Jonli baza va `.env` o‘zgartirilmadi. Haqiqiy Telegram interfeysida sinov va ishlab turgan botni qayta ishga tushirish bajarilmadi.

## 2026-10-28 — EduPing: tugmali oqim va navbat tuzatishlari (V3)

Lokal Windows, Java 17.0.15, Temp papkada vaqtinchalik PostgreSQL 17.4 klasteri (`ustozquti_test`), Postman Telegram simulyatsiyasi. Foydalanuvchining `teacher_box` bazasiga tegilmadi; vaqtinchalik klaster tekshiruvdan keyin o‘chirildi.

```text
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
```

Yangi qamrov: havola orqali qo‘shilish va ism so‘rash; kodni matn sifatida yuborish; tugmalar bilan savol, javob va yopish (raqam terilmaydi); ustoz menyudan sinf yaratib havola olishi; eski slash-buyruqlar ishlashi; bo‘sh savol yaratilmasligi; savol tanlanmagan xabar saqlanmasligi va bu aytilishi; o‘quvchi o‘z savolini yopishi; chiqarish va havola yangilash tasdiq so‘rashi; chiqarilgan o‘quvchi tarixini ko‘ra olishi, lekin yozolmasligi; HTML ekranlash; sozlash rejimi; buzuq outbox yozuvi navbatni to‘xtatmasligi; reaksiya xatosi `DROPPED` bo‘lishi; ustoz ro‘yxatida javob kutayotganlar birinchi turishi; fayl arxivi alohida ishchida.

Haqiqiy Telegram’da sinalmagan: HTML formatlash, `setMessageReaction`, `setMyCommands`, doimiy menyu ko‘rinishi. Bular Bot API hujjatiga ko‘ra yozilgan.

## Dastlabki versiya (UstozQuti)

2026-10-28, lokal Windows, Java 17.0.15, PostgreSQL 17.4, Spring Boot 4.1.2, Postman CLI 1.77.0.

## Build va PostgreSQL integratsiyasi

`mvn verify` haqiqiy alohida `ustozquti_test` bazasi va lokal Postman Telegram simulyatsiyasi bilan bajarildi. Flyway V1 va V2 bajarildi.

```text
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Qamrov: o‘quvchi → murojaat → o‘qituvchi javobi → yopish; restartdan keyingi session; rol va egasiga qarab ruxsat; takror update; invalid batch rollback; taklif kodini almashtirish; a’zolikni bekor qilish; fayl baytlari va SHA-256; 20 MB cheklovi; 403 FAILED; 429 retry_after; 500/502 retry; chat ichida va foydalanuvchi inboxida tartib; ochiq ticket limiti va qo‘llanmagan xabar turi.

## Ishlayotgan JAR

`target/ustozquti-1.0.0.jar` haqiqiy worker’lar bilan lokal simulyatsiyaga yo‘naltirib ishga tushirildi. Soxta update `50000`: `inbox=DONE`, `next_offset=50001`, o‘sha chatning javobi `outbox=SENT`. Simulyatsiya logida real `getUpdates` va `sendMessage` HTTP so‘rovlari ko‘rindi.

## Postman health collection

Target: lokal ishlayotgan JAR, `http://127.0.0.1:8080`. Collection run exit code 0. Quyidagi natija CLI chiqishidan:

```text
UstozQuti-health

Folder health
Sub-folder Check application process

  GET http://127.0.0.1:8080/health/live [200 OK, 169 B, 131 ms]
  Pass  HTTP 200
  Pass  Status UP
  Pass  JSON response

Sub-folder Check database connectivity

  GET http://127.0.0.1:8080/health/ready [200 OK, 177 B, 9 ms]
  Pass  HTTP 200
  Pass  Status UP
  Pass  JSON response

----------------------------------------------------------------------
|                         |            executed |             failed |
--------------------------+---------------------+---------------------
|              iterations |                   1 |                  0 |
--------------------------+---------------------+---------------------
|                requests |                   2 |                  0 |
--------------------------+---------------------+---------------------
|            test-scripts |                   0 |                  0 |
--------------------------+---------------------+---------------------
|      prerequest-scripts |                   0 |                  0 |
--------------------------+---------------------+---------------------
|              assertions |                   6 |                  0 |
----------------------------------------------------------------------
| total run duration: 525 ms                                         |
----------------------------------------------------------------------
| total data received: 30 B (approx)                                 |
----------------------------------------------------------------------
| average response time: 70 ms [min: 9 ms, max: 131 ms, s.d.: 61 ms] |
----------------------------------------------------------------------
```

OpenAPI syntax lint: `No issues found.` Collection lint: `Scanned: 2 | Errors: 0 | Warnings: 0`. Governance lint hisobga kirishni so‘radi; syntax lint bilan tekshirildi. Postman owner mock qidiruvi autentifikatsiya yo‘qligi sabab bajarilmadi; fixture rasmiy Telegram hujjatidan yozildi. Cloudga hech narsa publish qilinmadi.

## Backup va restore

PostgreSQL `pg_dump -Fc -f` bilan sun’iy test bazasidan dump olindi, `pg_restore --exit-on-error --no-owner` bilan yangi `ustozquti_restorecheck` bazasiga tiklandi. Manbadagi sinov faylining `00ff1020deadbeef` baytlari tiklangan bazada aynan bir xil chiqdi. Ikkala Flyway migration yozuvi ham tiklandi.

Bu PostgreSQL dump/restore tekshiruvi; Docker Compose backup skriptlarining `bash -n` sintaksis tekshiruvi o‘tdi. Docker mavjud bo‘lmagani uchun ularning host/container ishga tushirilishi tekshirilmagan. Lokal test JAR, PostgreSQL va Telegram simulyatsiyasi tekshiruvdan keyin to‘xtatildi.

## Hali jonli tekshirilmagan

Haqiqiy Telegram tokeni, username bandligi, Telegram’dagi barcha media turlari, serverdagi Docker deploy, haqiqiy foydalanuvchilar bilan qabul sinovi, tashqi shifrlangan backup jadvali, disk to‘lishi va serverning uzoq uzilishi. Logo built-in ImageGen bilan yaratilib `assets/logo.png` ga ko‘chirildi va ko‘rildi. Lokal muvaffaqiyat jonli infratuzilma sozlanganini anglatmaydi.
