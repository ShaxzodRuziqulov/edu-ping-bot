# Bot shartnomasi

Bot faqat shaxsiy chatda ishlaydi. Foydalanuvchi `update.from.id` orqali aniqlanadi. Ustoz huquqi faqat `TEACHER_IDS` ro‘yxati orqali beriladi.

Asosiy ish **tugmalar** bilan bajariladi: pastdagi doimiy menyu va xabarlar ostidagi tugmalar. Slash-buyruqlar tajribali foydalanuvchilar va eski tugmalar uchun saqlangan.

## Til tanlash

- Yangi foydalanuvchiga avval «🇺🇿 O‘zbekcha» va «🇷🇺 Русский» tugmalari ko‘rsatiladi. Tanlovdan oldin biznes amallar bajarilmaydi.
- Tanlov `app_user.language` da saqlanadi va restartdan keyin ham qoladi. Mavjud foydalanuvchilar V4 migratsiyada o‘zbekchada qoladi.
- `/language`, `/lang` yoki «🌐 Til / Язык» orqali til almashtiriladi; joriy yozish rejimi va qoralama saqlanadi.
- Taklif havolasi yoki kod bilan kirish til tanlanganidan keyin davom etadi.
- Menyu, yordam, xatolar, tarix sarlavhalari va bildirishnomalar qabul qiluvchining tilida. Ustoz va o‘quvchi mustaqil til tanlaydi. Ismlar, sinf nomlari va yozishmalar tarjima qilinmaydi.
- Avval yuborilgan xabarlar va navbatdagi tayyor xabarlar qayta tarjima qilinmaydi. Eski o‘zbekcha va ruscha menyu tugmalari ishlashda davom etadi.
- Telegram «/» menyusi ham tanlangan tilga moslanadi. HTTP health shartnomasi o‘zgarmaydi.

## O‘quvchi yo‘li

1. Ustoz bergan havolani bosadi (`t.me/<bot>?start=KOD`) yoki `/start` dan keyin kodni yuboradi.
2. Bot ism-familiyasini so‘raydi. Ustoz ro‘yxatlarda shu ismni ko‘radi (`/name` bilan o‘zgartiriladi).
3. «✍️ Yangi savol» → sinf tanlanadi (bitta sinf bo‘lsa avtomatik) → yozish rejimi.
4. Birinchi xabar savolni yaratadi. Mavzu shu xabarning boshidan olinadi (60 belgi), media bo‘lsa «Rasm», «Fayl» va hokazo. Har qabul qilingan xabarga bot 👍 reaksiya qo‘yadi.
5. «✅ Tugatdim» yozish rejimidan chiqaradi. Hech narsa yozilmagan bo‘lsa savol yaratilmaydi.
6. Ustoz javobi tovush bilan keladi. Ostida [✍️ Javob yozish] [✅ Hal bo‘ldi] tugmalari bo‘ladi.

## Ustoz yo‘li

1. «➕ Yangi sinf» → sinf nomi → taklif havolasi va kodli sinf kartasi.
2. Yangi savol tovush bilan keladi. Ostida [✍️ Javob berish] [📄 Tarix] [✅ Yopish] tugmalari bo‘ladi.
3. «✍️ Javob berish» → yozish rejimi → matn yoki fayllar → «✅ Tugatdim».
4. «📥 Savollar» ochiq savollarni ko‘rsatadi. Avval javob kutayotganlar (🔴, eng uzoq kutgani birinchi), keyin javob berilganlar (🟢).
5. Sinf kartasida [👥 O‘quvchilar] va [🔄 Havolani yangilash] tugmalari bor. O‘quvchini chiqarish va havolani yangilash tasdiq so‘raydi.

## Menyular

| Kim | Doimiy menyu |
| --- | --- |
| O‘quvchi | ✍️ Yangi savol · 📂 Savollarim · 🏫 Sinflarim · ❓ Yordam |
| Ustoz | 📥 Savollar · 🏫 Sinflar · ➕ Yangi sinf · 📊 Holat · ❓ Yordam |
| Yozish rejimida | ✅ Tugatdim |
| Sinf nomini kiritishda | ❌ Bekor qilish |

Telegram’dagi «/» menyusi ishga tushganda `setMyCommands` bilan avtomatik o‘rnatiladi: o‘quvchi buyruqlari hamma uchun, ustoz buyruqlari faqat ustoz chatida.

## Buyruqlar

| Kim | Buyruq | Natija |
| --- | --- | --- |
| Hamma | /start, /help, /menu | rolga mos boshlash, yordam, menyu |
| Hamma | /id, /privacy, /name | Telegram ID, maxfiylik, ismni o‘zgartirish |
| Hamma | /done, /cancel | joriy rejimdan chiqish |
| O‘quvchi | /start KOD, /join [KOD] | sinfga qo‘shilish |
| O‘quvchi | /new [SINF_ID [mavzu]] | yangi savol |
| O‘quvchi | /continue RAQAM | ochiq savolga yana yozish |
| O‘quvchi | /my [sahifa] | o‘z savollari, sahifada 8 ta |
| Egasi | /view RAQAM [sahifa] | tarix, sahifada 10 ta xabar |
| Egasi | /close RAQAM | savolni yopish (o‘quvchi ham, ustoz ham) |
| Ustoz | /class [nom] | sinf yaratish (nomsiz bo‘lsa so‘raydi) |
| Ustoz | /classes | sinflar ro‘yxati va kartalari |
| Ustoz | /members SINF_ID [sahifa] | o‘quvchilar, sahifada 15 ta |
| Ustoz | /remove SINF_ID OQUVCHI_ID | chiqarish (tasdiqsiz, tugma esa tasdiq so‘raydi) |
| Ustoz | /rotate SINF_ID | taklif havolasini yangilash |
| Ustoz | /inbox [sahifa] | ochiq savollar |
| Ustoz | /reply RAQAM | javob yozish rejimi |
| Ustoz | /stats | o‘quvchilar, savollar va texnik xatolar soni |

## Qoidalar

- Savol tanlanmagan holda yuborilgan xabar hech kimga yetkazilmaydi va saqlanmaydi. Bot buni aytadi va [✍️ Yangi savol] / [↩️ #N savolga qo‘shish] tugmalarini beradi.
- Sinfga hali qo‘shilmagan o‘quvchi yuborgan matn kodga o‘xshasa, u kod sifatida tekshiriladi.
- O‘quvchi o‘z savolini yopishi mumkin. Yopilgan savolga yozilmaydi, tarix saqlanadi.
- Sinfdan chiqarilgan o‘quvchining ochiq savollari yopiladi va unga xabar beriladi. U o‘z tarixini ko‘ra oladi, lekin yoza olmaydi.
- Ruxsat yo‘q bo‘lsa savol mavjudligi ham ochilmaydi.
- Chegaralar: daqiqasiga 30 ta xabar, bir o‘quvchida 5 ta ochiq savol, 10 daqiqada 20 ta kod urinishi, fayl 20 MB gacha.
- `TEACHER_IDS` bo‘sh yoki `0` bo‘lsa, bot sozlash rejimida ishlaydi: har qanday xabarga faqat yozuvchining Telegram ID raqamini aytadi.

## Bildirishnomalar

Faqat ikkinchi tomondan kelgan yangilik **tovush bilan** keladi: o‘quvchining savoli ustozga, ustozning javobi o‘quvchiga. Botning tugma va buyruqlarga javoblari jim yuboriladi.

Matnlar Telegram HTML formatida. Foydalanuvchi kiritgan har qanday matn (ism, sinf nomi, savol) ekranlanadi.

## Yetkazish va arxiv

Matn va metadata bazaga commit qilingach javoblar navbatga qo‘yiladi. Fayl arxivi alohida ishchi bilan yuklanadi: PENDING → STORED yoki FAILED. 👍 reaksiya va tugma tasdig‘i (`answerCallbackQuery`) yetkazilmasa, ular `DROPPED` holatiga o‘tadi va xato hisobiga kirmaydi. Doimiy xatolar ustozning «📊 Holat» hisobotida ko‘rinadi.
