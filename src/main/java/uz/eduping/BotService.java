package uz.eduping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.util.*;

/**
 * Conversation logic. Students and teachers mostly tap buttons; slash commands stay available for people who type them.
 * Every reply is only queued in the outbox, so business writes and replies commit together.
 */
@Service
public class BotService {
    static final int MAX_OPEN_TICKETS = 5;
    private static final int LIST_PAGE = 8, HISTORY_PAGE = 10, MEMBER_PAGE = 15;

    // Reply-keyboard labels; tapping one sends its text, which is routed like the matching command.
    static final String NEW = "✍️ Yangi savol", MY = "📂 Savollarim", MY_CLASSES = "🏫 Sinflarim", HELP = "❓ Yordam",
            DONE = "✅ Tugatdim", CANCEL = "❌ Bekor qilish",
            INBOX = "📥 Savollar", CLASSES = "🏫 Sinflar", ADD_CLASS = "➕ Yangi sinf", STATS = "📊 Holat";
    private static final Map<String, String> BUTTONS = Map.of(NEW, "/new", MY, "/my", MY_CLASSES, "/classes", HELP, "/help",
            DONE, "/done", CANCEL, "/cancel", INBOX, "/inbox", CLASSES, "/classes", ADD_CLASS, "/class", STATS, "/stats");
    // Telegram accepts captions on these media only.
    private static final Set<String> CAPTIONED = Set.of("photo", "video", "document", "audio", "voice", "animation");

    private final JdbcTemplate db;
    private final BotProperties config;
    private final SecureRandom random = new SecureRandom();

    private String language(long actor) {
        var languages = db.queryForList("SELECT language FROM app_user WHERE id=?", String.class, actor);
        return languages.isEmpty() ? null : languages.get(0);
    }

    private String tr(long actor, String text) {
        return Messages.text(language(actor), text);
    }

    private void chooseLanguage(long actor) {
        say(actor, "Tilni tanlang / Выберите язык:", inline(row(
                btn("🇺🇿 O‘zbekcha", "lang:uz"), btn("🇷🇺 Русский", "lang:ru"))));
    }

    private void selectLanguage(long actor, String selected) {
        if (!Set.of("uz", "ru").contains(selected)) {
            chooseLanguage(actor);
            return;
        }
        boolean first = language(actor) == null;
        String invite = db.queryForObject("SELECT pending_invite_code FROM app_user WHERE id=?", String.class, actor);
        db.update("UPDATE app_user SET language=?,pending_invite_code=NULL WHERE id=?", selected, actor);
        api(actor, "setMyCommands", Map.of("scope", Map.of("type", "chat", "chat_id", actor),
                "commands", BotCommands.commands(config.isTeacher(actor), selected)));
        if (config.setupMode()) setup(actor);
        else if (first && invite != null) join(actor, invite);
        else if (first) start(actor, config.isTeacher(actor));
        else {
            // A language change must not discard a draft or an active conversation.
            State current = state(actor);
            Object markup = current == null ? (config.isTeacher(actor) ? teacherMenu(actor) : studentMenu(actor))
                    : switch (current.mode) {
                        case "WRITE" -> writingKeyboard(actor);
                        case "CLASS_NAME" -> keyboard(List.of(List.of(tr(actor, CANCEL))));
                        default -> Map.of("remove_keyboard", true);
                    };
            say(actor, tr(actor, "Til tanlandi."), markup);
        }
    }

    public BotService(JdbcTemplate db, BotProperties config) {
        this.db = db;
        this.config = config;
    }

    // Called only inside the inbox transaction: business writes and queued replies commit together.
    public void handle(JsonNode update) {
        JsonNode callback = update.path("callback_query");
        boolean isCallback = !callback.isMissingNode();
        JsonNode message = isCallback ? callback.path("message") : update.path("message");
        if (!message.path("chat").path("type").asText().equals("private")) return;
        JsonNode from = isCallback ? callback.path("from") : message.path("from");
        long actor = from.path("id").asLong();
        if (actor <= 0 || message.path("chat").path("id").asLong() != actor || from.path("is_bot").asBoolean()) return;
        String name = (from.path("first_name").asText("") + " " + from.path("last_name").asText("")).strip();
        if (name.isEmpty()) name = tr(actor, "O‘quvchi");
        db.update("INSERT INTO app_user(id,display_name) VALUES (?,?) ON CONFLICT (id) DO UPDATE SET display_name=excluded.display_name", actor, cut(name, 200));
        if (isCallback) api(null, "answerCallbackQuery", Map.of("callback_query_id", callback.path("id").asText()));
        try {
            String text = message.path("text").asText("").strip();
            String data = callback.path("data").asText("");
            if (isCallback && data.startsWith("lang:")) {
                selectLanguage(actor, data.substring(5));
                return;
            }
            String cmd = text.split("\\s+", 2)[0].split("@", 2)[0];
            if (!isCallback && (cmd.equals("/language") || cmd.equals("/lang") || text.equals("🌐 Til / Язык"))) {
                chooseLanguage(actor);
                return;
            }
            if (language(actor) == null) {
                // Keep deep-link onboarding intact while the user chooses a language.
                if (!isCallback && (cmd.equals("/start") || cmd.equals("/join"))) {
                    String[] parts = text.split("\\s+", 2);
                    if (parts.length == 2 && parts[1].matches("[A-Za-z0-9_-]{4,32}"))
                        db.update("UPDATE app_user SET pending_invite_code=? WHERE id=?", parts[1], actor);
                } else if (!isCallback && text.matches("[A-Za-z0-9_-]{8,32}")) {
                    db.update("UPDATE app_user SET pending_invite_code=? WHERE id=?", text, actor);
                }
                chooseLanguage(actor);
                return;
            }
            if (config.setupMode()) setup(actor);
            else if (isCallback) button(actor, callback.path("data").asText(""));
            else {
                String routed = BUTTONS.get(text.strip());
                if (routed == null) routed = BUTTONS.entrySet().stream()
                        .filter(e -> Messages.text("ru", e.getKey()).equals(text)).map(Map.Entry::getValue).findFirst().orElse(null);
                if (routed != null) command(actor, routed);
                else if (text.startsWith("/")) command(actor, text);
                else content(actor, message);
            }
        } catch (UserError e) {
            say(actor, e.getMessage());
        } catch (NumberFormatException e) {
            say(actor, tr(actor, "Raqam noto‘g‘ri. «❓ Yordam» da namunalar bor."));
        }
    }

    // ---------------------------------------------------------------- routing

    private void command(long actor, String text) {
        String[] parts = text.strip().split("\\s+", 3);
        String cmd = parts[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1] : "";
        String tail = parts.length > 2 ? parts[2] : "";
        boolean teacher = config.isTeacher(actor);
        switch (cmd) {
            case "/start" -> {
                if (!arg.isBlank()) join(actor, arg);
                else start(actor, teacher);
            }
            case "/help" -> help(actor, teacher);
            case "/menu" -> menu(actor, teacher, tr(actor, "Asosiy menyu."));
            case "/id" -> say(actor, tr(actor, "Sizning Telegram ID: <code>") + actor + "</code>");
            case "/privacy" -> privacy(actor);
            case "/name" -> askName(actor, tr(actor, "Ism va familiyangizni yozing:"));
            case "/done", "/cancel" -> done(actor, teacher);
            case "/class" -> {
                teacher(actor);
                String className = (arg + " " + tail).strip();
                if (className.isBlank()) askClassName(actor);
                else createClass(actor, className);
            }
            case "/classes" -> classes(actor, teacher);
            case "/join" -> {
                if (arg.isBlank()) askCode(actor);
                else join(actor, arg);
            }
            case "/rotate" -> rotate(actor, number(actor, arg));
            case "/members" -> members(actor, number(actor, arg), page(actor, tail));
            case "/remove" -> remove(actor, number(actor, arg), number(actor, tail));
            case "/new" -> newTicket(actor, arg, tail);
            case "/continue", "/reply" -> write(actor, number(actor, arg));
            case "/my", "/inbox" -> tickets(actor, teacher, page(actor, arg));
            case "/view" -> view(actor, number(actor, arg), page(actor, tail));
            case "/close" -> close(actor, number(actor, arg));
            case "/stats" -> stats(actor);
            default -> say(actor, tr(actor, "Bunday buyruq yo‘q. «❓ Yordam» ni bosing."));
        }
    }

    /** Inline buttons carry short codes such as {@code v:12:2}; values are re-checked like typed commands. */
    private void button(long actor, String data) {
        if (data.startsWith("/")) {
            command(actor, data); // buttons sent by earlier versions
            return;
        }
        String[] p = data.split(":");
        boolean teacher = config.isTeacher(actor);
        switch (p[0]) {
            case "n" -> newTicket(actor, p.length > 1 ? p[1] : "", "");
            case "w", "r" -> write(actor, number(actor, arg(p, 1)));
            case "v" -> view(actor, number(actor, arg(p, 1)), page(actor, arg(p, 2)));
            case "c", "x" -> close(actor, number(actor, arg(p, 1)));
            case "my", "ib" -> tickets(actor, teacher, page(actor, arg(p, 1)));
            case "cl" -> classCard(actor, number(actor, arg(p, 1)));
            case "nc" -> {
                teacher(actor);
                askClassName(actor);
            }
            case "jn" -> askCode(actor);
            case "k" -> {
                long id = number(actor, arg(p, 1));
                ownClass(actor, id);
                say(actor, tr(actor, "Hozirgi havola va kod ishlamay qoladi. Sinfdagi o‘quvchilar o‘z joyida qoladi.\n\nYangilaysizmi?"),
                        inline(row(btn(tr(actor, "🔄 Ha, yangilash"), "ky:" + id), btn(tr(actor, "Yo‘q"), "cl:" + id))));
            }
            case "ky" -> rotate(actor, number(actor, arg(p, 1)));
            case "m" -> members(actor, number(actor, arg(p, 1)), page(actor, arg(p, 2)));
            case "rm" -> {
                long classId = number(actor, arg(p, 1)), student = number(actor, arg(p, 2));
                ownClass(actor, classId);
                say(actor, "<b>" + esc(studentName(student)) + tr(actor, "</b> sinfdan chiqarilsinmi?\nUning ochiq savollari yopiladi, tarix saqlanadi."),
                        inline(row(btn(tr(actor, "🚫 Ha, chiqarish"), "rmy:" + classId + ":" + student), btn(tr(actor, "Yo‘q"), "m:" + classId))));
            }
            case "rmy" -> remove(actor, number(actor, arg(p, 1)), number(actor, arg(p, 2)));
            default -> say(actor, tr(actor, "Bu tugma eskirgan. «❓ Yordam» ni bosing."));
        }
    }

    /** Plain messages: either an answer to a question the bot asked, or content for the selected ticket. */
    private void content(long actor, JsonNode message) {
        State state = state(actor);
        String text = message.path("text").asText("").strip();
        if (state != null) {
            switch (state.mode) {
                case "WRITE" -> {
                    accept(actor, state, message);
                    return;
                }
                case "NAME" -> {
                    require(!text.isEmpty(), tr(actor, "Ismingizni matn ko‘rinishida yozing."));
                    setName(actor, text);
                    return;
                }
                case "CLASS_NAME" -> {
                    require(!text.isEmpty(), tr(actor, "Sinf nomini matn ko‘rinishida yozing. Masalan: <b>7-A</b>"));
                    createClass(actor, text);
                    return;
                }
                case "JOIN_CODE" -> {
                    require(!text.isEmpty(), tr(actor, "Ustozingiz bergan sinf kodini yuboring."));
                    join(actor, text);
                    return;
                }
                default -> clearState(actor);
            }
        }
        if (config.isTeacher(actor)) {
            say(actor, tr(actor, "Bu xabar hech kimga yuborilmadi.\nJavob yozish uchun avval savolni tanlang."), inline(row(btn(tr(actor, INBOX), "ib:1"))));
            return;
        }
        if (memberships(actor) == 0) {
            if (text.matches("[A-Za-z0-9_-]{8,32}")) join(actor, text);
            else askCode(actor);
            return;
        }
        var buttons = new ArrayList<List<Map<String, String>>>();
        buttons.add(row(btn(tr(actor, NEW), "n")));
        var open = db.queryForList("SELECT id FROM ticket WHERE student_id=? AND status='OPEN' ORDER BY last_message_at DESC LIMIT 1", Long.class, actor);
        if (!open.isEmpty()) buttons.add(row(btn("↩️ #" + open.get(0) + tr(actor, " savolga qo‘shish"), "w:" + open.get(0))));
        say(actor, tr(actor, "Bu xabar hech kimga yuborilmadi.\nUstozga yozish uchun avval savolni tanlang, so‘ng xabarni qayta yuboring."), inline(buttons));
    }

    // ---------------------------------------------------------------- onboarding

    private void setup(long actor) {
        say(actor, tr(actor, "⚙️ <b>Bot hali sozlanmagan</b>\n\nSizning Telegram ID: <code>") + actor + "</code>\n\n" +
                tr(actor, "Agar siz ustoz bo‘lsangiz, shu raqamni <code>.env</code> faylidagi <code>TEACHER_IDS</code> ga yozing va botni qayta ishga tushiring."));
    }

    private void start(long actor, boolean teacher) {
        clearState(actor);
        if (teacher) {
            say(actor, tr(actor, "👋 <b>Assalomu alaykum, ustoz!</b>\n\nEduPing o‘quvchilaringiz savollarini bir joyga yig‘adi.\n\n") +
                    "1. «" + tr(actor, ADD_CLASS) + tr(actor, "» bilan sinf yarating.\n") +
                    tr(actor, "2. Bot bergan havolani sinf o‘quvchilariga yuboring.\n") +
                    tr(actor, "3. Savol kelganda shu yerda xabar olasiz va «✍️ Javob berish» ni bosasiz."), teacherMenu(actor));
        } else if (memberships(actor) == 0) {
            askCode(actor);
        } else if (fullName(actor) == null) {
            askName(actor, tr(actor, "Ustozingiz sizni tanishi uchun <b>ism va familiyangizni</b> yozing:"));
        } else {
            say(actor, tr(actor, "👋 Xush kelibsiz, <b>") + esc(fullName(actor)) + tr(actor, "</b>!\n\nSavol yuborish uchun «") + tr(actor, NEW) + tr(actor, "» ni bosing."), studentMenu(actor));
        }
    }

    private void askCode(long actor) {
        require(!config.isTeacher(actor), tr(actor, "Siz ustoz sifatida sozlangansiz. Sinflaringiz: «") + tr(actor, CLASSES) + "».");
        setState(actor, "JOIN_CODE", null, null);
        say(actor, tr(actor, "👋 <b>Assalomu alaykum!</b>\n\nEduPing orqali ustozingizga savol, uy vazifasi, rasm yoki ovozli xabar yuborasiz. Javob ham shu yerga keladi.\n\n") +
                tr(actor, "Boshlash uchun ustozingiz bergan <b>havolani bosing</b> yoki <b>sinf kodini</b> shu yerga yuboring."), keyboard(List.of(List.of(tr(actor, HELP)))));
    }

    private void join(long actor, String raw) {
        require(!config.isTeacher(actor), tr(actor, "Siz ustoz sifatida sozlangansiz. Sinflaringiz: «") + tr(actor, CLASSES) + "».");
        String code = raw.strip();
        require(code.matches("[A-Za-z0-9_-]{4,32}"), tr(actor, "Kod noto‘g‘ri ko‘rinishda. Ustozingiz bergan kodni bo‘sh joysiz yuboring."));
        require(count("SELECT count(*) FROM join_attempt WHERE user_id=? AND created_at>now()-interval '10 minutes'", actor) < 20, tr(actor, "Juda ko‘p urinish. 10 daqiqadan keyin qayta urinib ko‘ring."));
        db.update("INSERT INTO join_attempt(user_id) VALUES (?)", actor);
        var rows = db.queryForList("SELECT id,name FROM classroom WHERE invite_code=?", code);
        require(!rows.isEmpty(), tr(actor, "❌ Bunday kod topilmadi.\nKodni tekshiring yoki ustozingizdan yangi havola so‘rang."));
        long id = ((Number) rows.get(0).get("id")).longValue();
        String className = rows.get(0).get("name").toString();
        int added = db.update("INSERT INTO membership(classroom_id,student_id) VALUES (?,?) ON CONFLICT DO NOTHING", id, actor);
        if (added > 0) audit(actor, "JOIN_CLASS", id);
        clearState(actor);
        String joined = added > 0 ? "✅ <b>" + esc(className) + tr(actor, "</b> sinfiga qo‘shildingiz!") : tr(actor, "Siz allaqachon <b>") + esc(className) + tr(actor, "</b> sinfidasiz.");
        if (fullName(actor) == null)
            askName(actor, joined + tr(actor, "\n\nUstozingiz sizni tanishi uchun <b>ism va familiyangizni</b> yozing:"));
        else
            say(actor, joined + tr(actor, "\n\nSavol yuborish uchun «") + tr(actor, NEW) + tr(actor, "» ni bosing."), studentMenu(actor));
    }

    private void askName(long actor, String prompt) {
        setState(actor, "NAME", null, null);
        say(actor, prompt, Map.of("remove_keyboard", true));
    }

    private void setName(long actor, String text) {
        String name = text.replaceAll("\\s+", " ").strip();
        require(name.length() >= 3 && name.length() <= 60 && name.codePoints().anyMatch(Character::isLetter) && !name.startsWith("/"),
                tr(actor, "Ism va familiyani to‘liq yozing, masalan: <b>Ali Valiyev</b>"));
        db.update("UPDATE app_user SET full_name=? WHERE id=?", name, actor);
        clearState(actor);
        boolean teacher = config.isTeacher(actor);
        say(actor, tr(actor, "Rahmat, <b>") + esc(name) + "</b>!" + (teacher ? "" : tr(actor, "\n\nEndi ustozingizga savol yuborishingiz mumkin: «") + tr(actor, NEW) + tr(actor, "» ni bosing.\nMa’lumotlaringiz qanday saqlanishi: /privacy")),
                teacher ? teacherMenu(actor) : studentMenu(actor));
    }

    private void help(long actor, boolean teacher) {
        if (teacher) {
            say(actor, tr(actor, "<b>EduPing · Ustoz uchun</b>\n\n") +
                    tr(actor, "<b>Savollar</b>\nYangi savol kelganda xabar olasiz. «✍️ Javob berish» ni bosing, matn, rasm yoki fayl yuboring, so‘ng «") + tr(actor, DONE) + "».\n" +
                    "«" + tr(actor, INBOX) + tr(actor, "» — ochiq savollar. 🔴 javob kutmoqda, 🟢 javob berilgan.\n") +
                    tr(actor, "Masala hal bo‘lsa «✅ Yopish».\n\n") +
                    tr(actor, "<b>Sinflar</b>\n«") + tr(actor, ADD_CLASS) + tr(actor, "» — sinf yaratish va taklif havolasini olish.\n") +
                    "«" + tr(actor, CLASSES) + tr(actor, "» — havola, o‘quvchilar ro‘yxati, chiqarish va havolani yangilash.\n\n") +
                    tr(actor, "<b>Buyruqlar</b>\n/inbox · /classes · /class 7-A · /view 12 · /reply 12 · /close 12 · /stats · /privacy"), teacherMenu(actor));
        } else {
            say(actor, tr(actor, "<b>EduPing · O‘quvchi uchun</b>\n\n") +
                    tr(actor, "1. Ustoz bergan havolani bosing yoki kodni yuboring.\n") +
                    "2. «" + tr(actor, NEW) + tr(actor, "» ni bosing va savolingizni yozing. Rasm, fayl, ovozli xabar ham bo‘ladi (20 MB gacha).\n") +
                    tr(actor, "3. Tugatgach «") + tr(actor, DONE) + tr(actor, "» ni bosing. Ustoz javob berganda sizga xabar keladi.\n") +
                    tr(actor, "4. Masala hal bo‘lsa «✅ Hal bo‘ldi» ni bosing.\n\n") +
                    "«" + tr(actor, MY) + tr(actor, "» — barcha savollaringiz va javoblar tarixi.\n") +
                    tr(actor, "Bir vaqtda ") + MAX_OPEN_TICKETS + tr(actor, " tagacha ochiq savol bo‘lishi mumkin.\n\n") +
                    tr(actor, "/name — ismni o‘zgartirish · /join — boshqa sinfga qo‘shilish · /privacy — maxfiylik"), studentMenu(actor));
        }
    }

    private void privacy(long actor) {
        say(actor, tr(actor, "<b>Maxfiylik</b>\n\nEduPing ismingiz, Telegram ID, sinf a’zoligi, savol va javoblaringizni, yuborgan fayllaringizni saqlaydi.\n\n") +
                tr(actor, "Savolingizni faqat siz va shu sinf ustozi ko‘radi. Bot serverini yurituvchi texnik mas’ul bazaga kira oladi.\n\n") +
                tr(actor, "Telegram’da xabarni o‘chirsangiz, saqlangan nusxa o‘chmaydi. Ma’lumotni o‘chirish uchun ustozingizga murojaat qiling.\n\n") +
                tr(actor, "Parol, bank kartasi va boshqa maxfiy ma’lumotlarni yubormang."));
    }

    private void menu(long actor, boolean teacher, String text) {
        say(actor, text, teacher ? teacherMenu(actor) : studentMenu(actor));
    }

    private void done(long actor, boolean teacher) {
        State state = state(actor);
        clearState(actor);
        if (state != null && state.mode.equals("WRITE")) {
            if (teacher) menu(actor, true, tr(actor, "👍 Javob yozish tugatildi."));
            else if (state.ticket == null) menu(actor, false, tr(actor, "Savol yuborilmadi."));
            else menu(actor, false, tr(actor, "👍 Yaxshi. Ustoz javob berganda sizga xabar keladi."));
        } else menu(actor, teacher, state == null ? tr(actor, "Asosiy menyu.") : tr(actor, "Bekor qilindi."));
    }

    // ---------------------------------------------------------------- classes (teacher)

    private void askClassName(long actor) {
        setState(actor, "CLASS_NAME", null, null);
        say(actor, tr(actor, "Yangi sinf nomini yozing. Masalan: <b>7-A</b> yoki <b>Ingliz tili 9-sinf</b>"), keyboard(List.of(List.of(tr(actor, CANCEL)))));
    }

    private void createClass(long actor, String raw) {
        teacher(actor);
        String className = raw.replaceAll("\\s+", " ").strip();
        require(!className.isBlank() && className.length() <= 80 && !className.startsWith("/"), tr(actor, "Sinf nomi 1–80 belgidan iborat bo‘lsin. Masalan: <b>7-A</b>"));
        require(count("SELECT count(*) FROM classroom WHERE teacher_id=? AND name=?", actor, className) == 0, tr(actor, "Bu nomli sinf allaqachon bor. Boshqa nom yozing."));
        Long id = db.queryForObject("INSERT INTO classroom(teacher_id,name,invite_code) VALUES (?,?,?) RETURNING id", Long.class, actor, className, invite());
        audit(actor, "CREATE_CLASS", id);
        clearState(actor);
        menu(actor, true, tr(actor, "✅ Sinf yaratildi."));
        classCard(actor, id);
    }

    private void classes(long actor, boolean teacher) {
        if (teacher) {
            var rows = db.queryForList("SELECT c.id,c.name,(SELECT count(*) FROM membership m WHERE m.classroom_id=c.id) members," +
                    "(SELECT count(*) FROM ticket t WHERE t.classroom_id=c.id AND t.status='OPEN') AS open_count FROM classroom c WHERE c.teacher_id=? ORDER BY c.name LIMIT 50", actor);
            if (rows.isEmpty()) {
                say(actor, tr(actor, "Hali sinf yo‘q."), inline(row(btn(tr(actor, ADD_CLASS), "nc"))));
                return;
            }
            var buttons = new ArrayList<List<Map<String, String>>>();
            for (var r : rows)
                buttons.add(row(btn("🏫 " + cut(r.get("name").toString(), 30) + " · 👥 " + r.get("members") + " · 📥 " + r.get("open_count"), "cl:" + r.get("id"))));
            buttons.add(row(btn(tr(actor, ADD_CLASS), "nc")));
            say(actor, tr(actor, "<b>Sinflaringiz</b>\nSinfni tanlang: havola, o‘quvchilar va sozlamalar."), inline(buttons));
        } else {
            var rows = db.queryForList("SELECT c.name FROM classroom c JOIN membership m ON c.id=m.classroom_id WHERE m.student_id=? ORDER BY c.name LIMIT 50", String.class, actor);
            if (rows.isEmpty()) {
                askCode(actor);
                return;
            }
            var text = new StringBuilder(tr(actor, "<b>Sinflaringiz</b>\n"));
            for (var r : rows) text.append("🏫 ").append(esc(r)).append('\n');
            say(actor, text.toString(), inline(row(btn(tr(actor, "➕ Boshqa sinfga qo‘shilish"), "jn"))));
        }
    }

    private void classCard(long actor, long id) {
        ownClass(actor, id);
        var c = db.queryForMap("SELECT name,invite_code,(SELECT count(*) FROM membership m WHERE m.classroom_id=c.id) members," +
                "(SELECT count(*) FROM ticket t WHERE t.classroom_id=c.id AND t.status='OPEN') AS open_count FROM classroom c WHERE id=?", id);
        String code = c.get("invite_code").toString();
        say(actor, "🏫 <b>" + esc(c.get("name").toString()) + tr(actor, "</b>\n👥 O‘quvchilar: ") + c.get("members") + tr(actor, "\n📥 Ochiq savollar: ") + c.get("open_count") + "\n\n" +
                        tr(actor, "<b>Taklif havolasi</b> — o‘quvchilarga yuboring:\n") + inviteLink(code) + tr(actor, "\n\nYoki kod: <code>") + code + "</code>\n\n" +
                        tr(actor, "Havolani faqat shu sinf o‘quvchilariga bering."),
                inline(row(btn(tr(actor, "👥 O‘quvchilar"), "m:" + id), btn(tr(actor, "🔄 Havolani yangilash"), "k:" + id))));
    }

    private void rotate(long actor, long id) {
        ownClass(actor, id);
        db.update("UPDATE classroom SET invite_code=? WHERE id=?", invite(), id);
        audit(actor, "ROTATE_INVITE", id);
        say(actor, tr(actor, "🔄 Havola yangilandi. Eskisi endi ishlamaydi."));
        classCard(actor, id);
    }

    private void members(long actor, long classId, long page) {
        ownClass(actor, classId);
        String className = db.queryForObject("SELECT name FROM classroom WHERE id=?", String.class, classId);
        long total = count("SELECT count(*) FROM membership WHERE classroom_id=?", classId);
        var rows = db.queryForList("SELECT u.id,COALESCE(u.full_name,u.display_name) AS name FROM membership m JOIN app_user u ON u.id=m.student_id " +
                "WHERE m.classroom_id=? ORDER BY lower(COALESCE(u.full_name,u.display_name)),u.id LIMIT ? OFFSET ?", classId, MEMBER_PAGE, (page - 1) * MEMBER_PAGE);
        var text = new StringBuilder("👥 <b>" + esc(className) + "</b> · " + total + tr(actor, " o‘quvchi\n\n"));
        var buttons = new ArrayList<List<Map<String, String>>>();
        long n = (page - 1) * MEMBER_PAGE;
        for (var r : rows) {
            text.append(++n).append(". ").append(esc(r.get("name").toString())).append('\n');
            buttons.add(row(btn("🚫 " + cut(r.get("name").toString(), 30), "rm:" + classId + ":" + r.get("id"))));
        }
        if (rows.isEmpty()) text.append(page == 1 ? tr(actor, "Hali hech kim qo‘shilmagan. Taklif havolasini yuboring.") : tr(actor, "Bu sahifa bo‘sh."));
        else text.append(tr(actor, "\nChiqarish uchun o‘quvchini tanlang."));
        addPager(actor, buttons, page, total, MEMBER_PAGE, "m:" + classId + ":");
        buttons.add(row(btn(tr(actor, "⬅️ Sinfga qaytish"), "cl:" + classId)));
        say(actor, text.toString(), inline(buttons));
    }

    private void remove(long actor, long classId, long student) {
        ownClass(actor, classId);
        int removed = db.update("DELETE FROM membership WHERE classroom_id=? AND student_id=?", classId, student);
        require(removed > 0, tr(actor, "Bu o‘quvchi sinfda yo‘q."));
        int closed = db.update("UPDATE ticket SET status='CLOSED',closed_at=now() WHERE classroom_id=? AND student_id=? AND status='OPEN'", classId, student);
        db.update("DELETE FROM chat_session WHERE user_id=? AND (class_id=? OR ticket_id IN (SELECT id FROM ticket WHERE classroom_id=?))", student, classId, classId);
        audit(actor, "REMOVE_MEMBER", student);
        String className = db.queryForObject("SELECT name FROM classroom WHERE id=?", String.class, classId);
        say(actor, "✅ <b>" + esc(studentName(student)) + tr(actor, "</b> sinfdan chiqarildi.") + (closed > 0 ? tr(actor, " Ochiq savollari (") + closed + tr(actor, ") yopildi.") : "") +
                tr(actor, "\nHavola begonalarga tarqalgan bo‘lsa, uni yangilang."), inline(row(btn(tr(actor, "🔄 Havolani yangilash"), "k:" + classId))));
        say(student, tr(student, "Siz <b>") + esc(className) + tr(student, "</b> sinfidan chiqarildingiz. Avvalgi savollaringiz tarixi «") + tr(student, MY) + tr(student, "» da qoladi."), studentMenu(student));
    }

    // ---------------------------------------------------------------- tickets

    private void newTicket(long actor, String arg, String subject) {
        require(!config.isTeacher(actor), tr(actor, "Savollarni o‘quvchilar ochadi. Kelgan savollar: «") + tr(actor, INBOX) + "».");
        require(count("SELECT count(*) FROM ticket WHERE student_id=? AND status='OPEN'", actor) < MAX_OPEN_TICKETS,
                tr(actor, "Sizda ") + MAX_OPEN_TICKETS + tr(actor, " ta ochiq savol bor. Hal bo‘lganlarini «✅ Hal bo‘ldi» bilan yoping yoki mavjudiga yozing: «") + tr(actor, MY) + "».");
        if (arg.isBlank()) {
            var rows = db.queryForList("SELECT c.id,c.name FROM classroom c JOIN membership m ON c.id=m.classroom_id WHERE m.student_id=? ORDER BY c.name LIMIT 50", actor);
            if (rows.isEmpty()) {
                askCode(actor);
                return;
            }
            if (rows.size() == 1) {
                draft(actor, ((Number) rows.get(0).get("id")).longValue(), rows.get(0).get("name").toString());
                return;
            }
            var buttons = new ArrayList<List<Map<String, String>>>();
            for (var r : rows) buttons.add(row(btn("🏫 " + cut(r.get("name").toString(), 40), "n:" + r.get("id"))));
            say(actor, tr(actor, "Savol qaysi sinf ustoziga?"), inline(buttons));
            return;
        }
        long classId = number(actor, arg);
        requireMember(actor, classId);
        String className = db.queryForObject("SELECT name FROM classroom WHERE id=?", String.class, classId);
        if (subject.isBlank()) {
            draft(actor, classId, className);
            return;
        }
        long id = createTicket(actor, classId, cut(subject, 120));
        setState(actor, "WRITE", id, null);
        say(actor, "✍️ <b>#" + id + " · " + esc(cut(subject, 120)) + tr(actor, "</b>\n\nSavolingizni yozing. Tugatgach «") + tr(actor, DONE) + tr(actor, "» ni bosing."), writingKeyboard(actor));
    }

    /** The ticket is created with the first real message, so pressing the button by mistake leaves nothing behind. */
    private void draft(long actor, long classId, String className) {
        setState(actor, "WRITE", null, classId);
        say(actor, tr(actor, "✍️ <b>Yangi savol · ") + esc(className) + tr(actor, "</b>\n\nSavolingizni yozing. Matn, rasm, fayl yoki ovozli xabar yuborishingiz mumkin (20 MB gacha).\n\n") +
                tr(actor, "Tugatgach «") + tr(actor, DONE) + tr(actor, "» ni bosing."), writingKeyboard(actor));
    }

    private void write(long actor, long id) {
        Ticket t = accessible(actor, id, true);
        require(t.open(), config.isTeacher(actor) ? tr(actor, "Bu savol yopilgan.") : tr(actor, "Bu savol yopilgan. Yangi savol uchun «") + tr(actor, NEW) + tr(actor, "» ni bosing."));
        setState(actor, "WRITE", id, null);
        if (config.isTeacher(actor))
            say(actor, "✍️ <b>" + esc(t.studentName) + "</b> · #" + id + " · " + esc(t.subject) + tr(actor, "\n\nJavobingizni yozing: matn, rasm, fayl yoki ovozli xabar. Tugatgach «") + tr(actor, DONE) + tr(actor, "» ni bosing."), writingKeyboard(actor));
        else
            say(actor, "✍️ <b>#" + id + " · " + esc(t.subject) + tr(actor, "</b>\n\nQo‘shimcha yozing. Tugatgach «") + tr(actor, DONE) + tr(actor, "» ni bosing."), writingKeyboard(actor));
    }

    private void accept(long actor, State state, JsonNode m) {
        boolean teacher = config.isTeacher(actor);
        require(count("SELECT count(*) FROM ticket_message WHERE sender_id=? AND created_at>now()-interval '1 minute'", actor) < 30, tr(actor, "Bir daqiqada 30 tadan ko‘p xabar yuborib bo‘lmaydi. Biroz kuting."));
        String kind = "text";
        JsonNode file = null;
        if (m.path("photo").isArray() && !m.path("photo").isEmpty()) {
            kind = "photo";
            file = m.path("photo").get(m.path("photo").size() - 1);
        } else for (String k : List.of("document", "video", "voice", "audio", "sticker", "video_note", "animation"))
            if (m.has(k)) {
                kind = k;
                file = m.path(k);
                break;
            }
        String body = m.path("text").asText(m.path("caption").asText(""));
        require(file != null || !body.isBlank(), tr(actor, "Bu turdagi xabar qabul qilinmaydi. Matn, rasm, video, fayl, audio yoki ovozli xabar yuboring."));
        if (file != null)
            require(file.path("file_size").asLong() <= TelegramClient.MAX_FILE_BYTES, tr(actor, "Fayl 20 MB dan katta, qabul qilinmadi. Kichraytirib yoki bo‘lib yuboring."));
        String fileId = file == null ? null : file.path("file_id").asText();
        if (file != null) require(fileId != null && !fileId.isBlank(), tr(actor, "Faylni o‘qib bo‘lmadi. Qayta yuboring."));

        Ticket t;
        boolean first = state.ticket == null;
        if (first) {
            requireMember(actor, state.classId);
            require(count("SELECT count(*) FROM ticket WHERE student_id=? AND status='OPEN'", actor) < MAX_OPEN_TICKETS,
                    tr(actor, "Sizda ") + MAX_OPEN_TICKETS + tr(actor, " ta ochiq savol bor. Hal bo‘lganlarini yoping: «") + tr(actor, MY) + "».");
            String subject = body.isBlank() ? label(actor, kind) : cut(body.replaceAll("\\s+", " ").strip(), 60);
            long id = createTicket(actor, state.classId, subject);
            setState(actor, "WRITE", id, null);
            t = accessible(actor, id, true);
        } else {
            t = accessible(actor, state.ticket, true);
            require(t.open(), tr(actor, "Bu savol yopilgan. «") + tr(actor, DONE) + tr(actor, "» ni bosing."));
        }
        Long id = db.queryForObject("INSERT INTO ticket_message(ticket_id,sender_id,kind,body,file_id,file_unique_id,file_name,mime_type,file_size,archive_state) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id", Long.class,
                t.id, actor, kind, body, fileId, file == null ? null : file.path("file_unique_id").asText(), file == null ? null : cut(file.path("file_name").asText(kind), 255), file == null ? null : cut(file.path("mime_type").asText("application/octet-stream"), 150), file == null ? null : file.path("file_size").asLong(), file == null ? "NONE" : "PENDING");
        if (file != null) db.update("INSERT INTO outbox(job_type,message_id) VALUES ('ARCHIVE',?)", id);
        db.update("UPDATE ticket SET last_message_at=now(),last_sender_id=? WHERE id=?", actor, t.id);

        boolean fromStudent = actor == t.student;
        long recipient = fromStudent ? t.teacher : t.student;
        // A subject taken from this very message would just repeat its first words.
        String topic = body.replaceAll("\\s+", " ").strip().startsWith(t.subject) ? "" : "\n<i>" + esc(t.subject) + "</i>";
        String header = fromStudent
                ? "📩 <b>" + esc(t.studentName) + "</b> · " + esc(t.className) + " · #" + t.id + topic
                : tr(recipient, "💬 <b>Ustoz javobi</b> · #") + t.id + topic;
        Object buttons = fromStudent
                ? inline(row(btn(tr(recipient, "✍️ Javob berish"), "r:" + t.id), btn(tr(recipient, "📄 Tarix"), "v:" + t.id)), row(btn(tr(recipient, "✅ Yopish"), "c:" + t.id)))
                : inline(row(btn(tr(recipient, "✍️ Javob yozish"), "w:" + t.id), btn(tr(recipient, "✅ Hal bo‘ldi"), "x:" + t.id)));
        relay(recipient, header, kind, fileId, body, buttons);

        react(actor, m.path("message_id").asLong());
        if (first)
            say(actor, tr(actor, "📨 Savolingiz ustozga yuborildi · <b>#") + t.id + tr(actor, "</b>\nYana qo‘shimcha yozishingiz mumkin. Tugatgach «") + tr(actor, DONE) + tr(actor, "» ni bosing."));
    }

    private void tickets(long actor, boolean teacher, long page) {
        String zone = config.timeZone();
        long total = teacher ? count("SELECT count(*) FROM ticket t JOIN classroom c ON c.id=t.classroom_id WHERE c.teacher_id=? AND t.status='OPEN'", actor)
                : count("SELECT count(*) FROM ticket WHERE student_id=?", actor);
        // Teachers see who has waited longest first; students see their latest activity first.
        var rows = db.queryForList("SELECT t.id,t.subject,t.status,t.student_id,t.last_sender_id,c.name,COALESCE(u.full_name,u.display_name) AS student," +
                "to_char(t.last_message_at AT TIME ZONE ?,'DD.MM HH24:MI') AS at FROM ticket t JOIN classroom c ON c.id=t.classroom_id JOIN app_user u ON u.id=t.student_id WHERE " +
                (teacher ? "c.teacher_id=? AND t.status='OPEN' ORDER BY (t.last_sender_id=t.student_id) DESC NULLS LAST,t.last_message_at"
                        : "t.student_id=? ORDER BY (t.status='OPEN') DESC,t.last_message_at DESC") +
                " LIMIT ? OFFSET ?", zone, actor, LIST_PAGE, (page - 1) * LIST_PAGE);
        var text = new StringBuilder(teacher ? tr(actor, "📥 <b>Ochiq savollar</b> · ") + total + "\n\n" : tr(actor, "📂 <b>Savollaringiz</b>\n\n"));
        var buttons = new ArrayList<List<Map<String, String>>>();
        for (var r : rows) {
            boolean open = "OPEN".equals(r.get("status"));
            boolean waiting = Objects.equals(r.get("last_sender_id"), r.get("student_id"));
            String subject = esc(cut(r.get("subject").toString(), 60));
            if (teacher) {
                text.append(waiting ? "🔴" : "🟢").append(" <b>#").append(r.get("id")).append("</b> · ").append(esc(r.get("student").toString()))
                        .append(" · ").append(esc(r.get("name").toString())).append("\n      ").append(subject).append(" · ").append(r.get("at")).append('\n');
            } else {
                String status = !open ? tr(actor, "⚪️ yopilgan") : waiting ? tr(actor, "🟡 javob kutilmoqda") : tr(actor, "💬 javob keldi");
                text.append("<b>#").append(r.get("id")).append("</b> · ").append(subject).append("\n      ").append(status).append(" · ").append(r.get("at")).append('\n');
            }
            buttons.add(row(btn("#" + r.get("id") + " · " + cut(r.get("subject").toString(), 28), "v:" + r.get("id"))));
        }
        if (rows.isEmpty()) {
            if (page > 1) text.append(tr(actor, "Bu sahifa bo‘sh."));
            else text.append(teacher ? tr(actor, "Hozircha ochiq savol yo‘q 🎉") : tr(actor, "Hali savol yubormagansiz.\n«") + tr(actor, NEW) + tr(actor, "» ni bosing."));
        } else text.append(teacher ? tr(actor, "\n🔴 javob kutmoqda · 🟢 javob berilgan") : "");
        addPager(actor, buttons, page, total, LIST_PAGE, teacher ? "ib:" : "my:");
        say(actor, text.toString(), buttons.isEmpty() ? null : inline(buttons));
    }

    private void view(long actor, long id, long page) {
        Ticket t = accessible(actor, id, false);
        boolean teacher = config.isTeacher(actor);
        long total = count("SELECT count(*) FROM ticket_message WHERE ticket_id=?", id);
        long pages = Math.max(1, (total + HISTORY_PAGE - 1) / HISTORY_PAGE);
        say(actor, "📄 <b>#" + t.id + " · " + esc(t.subject) + "</b>\n" + esc(t.className) + (teacher ? " · " + esc(t.studentName) : "") +
                " · " + (t.open() ? tr(actor, "🟢 ochiq") : tr(actor, "⚪️ yopilgan")) + (pages > 1 ? tr(actor, " · sahifa ") + page + "/" + pages : ""));
        var rows = db.queryForList("SELECT sender_id,kind,body,file_id,to_char(created_at AT TIME ZONE ?,'DD.MM HH24:MI') AS at FROM ticket_message WHERE ticket_id=? ORDER BY id LIMIT ? OFFSET ?",
                config.timeZone(), id, HISTORY_PAGE, (page - 1) * HISTORY_PAGE);
        // Consecutive text messages go out as one chat message; media keep their own message.
        var block = new StringBuilder();
        for (var r : rows) {
            boolean byStudent = ((Number) r.get("sender_id")).longValue() == t.student;
            String who = byStudent ? (teacher ? "👤 " + esc(t.studentName) : tr(actor, "👤 Siz")) : (teacher ? tr(actor, "🧑‍🏫 Siz") : tr(actor, "🧑‍🏫 Ustoz"));
            String head = "<b>" + who + "</b> · " + r.get("at");
            String body = Objects.toString(r.get("body"), "");
            if (r.get("file_id") == null) {
                String entry = head + "\n" + esc(body) + "\n\n";
                if (block.length() + entry.length() > 3800) flush(actor, block);
                block.append(entry);
            } else {
                flush(actor, block);
                relay(actor, head, r.get("kind").toString(), r.get("file_id").toString(), body, null, false);
            }
        }
        if (rows.isEmpty()) block.append(page == 1 ? tr(actor, "Hali xabar yo‘q.") : tr(actor, "Bu sahifada xabar yo‘q."));
        flush(actor, block);
        var buttons = new ArrayList<List<Map<String, String>>>();
        addPager(actor, buttons, page, total, HISTORY_PAGE, "v:" + id + ":");
        if (t.open() && (teacher || memberships(actor, t.classId)))
            buttons.add(row(btn(teacher ? tr(actor, "✍️ Javob berish") : tr(actor, "✍️ Yozish"), "w:" + id), btn(teacher ? tr(actor, "✅ Yopish") : tr(actor, "✅ Hal bo‘ldi"), "c:" + id)));
        if (!buttons.isEmpty()) say(actor, tr(actor, "Amallar:"), inline(buttons));
    }

    private void close(long actor, long id) {
        Ticket t = accessible(actor, id, false);
        require(t.open(), tr(actor, "Bu savol allaqachon yopilgan."));
        boolean teacher = config.isTeacher(actor);
        db.update("UPDATE ticket SET status='CLOSED',closed_at=now() WHERE id=?", t.id);
        var writers = db.queryForList("SELECT user_id FROM chat_session WHERE ticket_id=?", Long.class, t.id);
        db.update("DELETE FROM chat_session WHERE ticket_id=?", t.id);
        audit(actor, "CLOSE_TICKET", t.id);
        // Whoever was writing into this ticket gets the main menu back instead of a stale «Tugatdim» button.
        Object studentMarkup = writers.contains(t.student) ? studentMenu(t.student) : null;
        Object teacherMarkup = writers.contains(t.teacher) ? teacherMenu(t.teacher) : null;
        if (teacher) {
            say(actor, "✅ #" + t.id + tr(actor, " yopildi."), teacherMarkup);
            notify(t.student, tr(t.student, "✅ Ustoz <b>#") + t.id + " · " + esc(t.subject) + tr(t.student, "</b> savolini yopdi.\nYana savol bo‘lsa «") + tr(t.student, NEW) + tr(t.student, "» ni bosing."), studentMarkup, false);
        } else {
            say(actor, "✅ #" + t.id + tr(actor, " yopildi. Rahmat!"), studentMarkup);
            notify(t.teacher, "☑️ <b>" + esc(t.studentName) + "</b> #" + t.id + tr(t.teacher, " savolini yopdi (hal bo‘ldi)."), teacherMarkup, false);
        }
    }

    private void stats(long actor) {
        teacher(actor);
        long open = count("SELECT count(*) FROM ticket t JOIN classroom c ON c.id=t.classroom_id WHERE c.teacher_id=? AND t.status='OPEN'", actor);
        long waiting = count("SELECT count(*) FROM ticket t JOIN classroom c ON c.id=t.classroom_id WHERE c.teacher_id=? AND t.status='OPEN' AND t.last_sender_id=t.student_id", actor);
        long closed = count("SELECT count(*) FROM ticket t JOIN classroom c ON c.id=t.classroom_id WHERE c.teacher_id=? AND t.status='CLOSED'", actor);
        long students = count("SELECT count(DISTINCT m.student_id) FROM membership m JOIN classroom c ON c.id=m.classroom_id WHERE c.teacher_id=?", actor);
        long failed = count("SELECT count(*) FROM outbox WHERE state='FAILED'") + count("SELECT count(*) FROM inbox WHERE state='FAILED'");
        // Operational counters reveal no message content; only allowlisted teachers can see them.
        say(actor, tr(actor, "📊 <b>Holat</b>\n\n👥 O‘quvchilar: ") + students + tr(actor, "\n🔴 Javob kutmoqda: ") + waiting + tr(actor, "\n📥 Ochiq: ") + open + tr(actor, "\n⚪️ Yopilgan: ") + closed +
                tr(actor, "\n\n<b>Texnik</b>\nYuborish navbati: ") + count("SELECT count(*) FROM outbox WHERE state='PENDING' AND job_type='API'") +
                tr(actor, "\nArxivlanmoqda: ") + count("SELECT count(*) FROM ticket_message WHERE archive_state='PENDING'") +
                tr(actor, "\nArxiv xatosi: ") + count("SELECT count(*) FROM ticket_message WHERE archive_state='FAILED'") +
                tr(actor, "\nYetkazish xatosi: ") + failed + (failed > 0 ? tr(actor, " ⚠️ texnik mas’ulga ayting") : ""));
    }

    // ---------------------------------------------------------------- access and state

    /** Teachers reach tickets of their own classes; students their own tickets, and may write only while still in the class. */
    private Ticket accessible(long actor, long id, boolean forWriting) {
        var rows = db.query("SELECT t.id,t.student_id,t.subject,t.status,c.id AS class_id,c.teacher_id,c.name,COALESCE(u.full_name,u.display_name) AS student FROM ticket t JOIN classroom c ON c.id=t.classroom_id JOIN app_user u ON u.id=t.student_id WHERE t.id=?",
                (rs, n) -> new Ticket(rs.getLong("id"), rs.getLong("student_id"), rs.getLong("teacher_id"), rs.getLong("class_id"), rs.getString("subject"), rs.getString("status"), rs.getString("name"), rs.getString("student")), id);
        require(!rows.isEmpty(), tr(actor, "Savol topilmadi yoki unga kirish huquqingiz yo‘q."));
        Ticket t = rows.get(0);
        boolean allowed = config.isTeacher(actor) ? actor == t.teacher : actor == t.student && (!forWriting || memberships(actor, t.classId));
        require(allowed, tr(actor, "Savol topilmadi yoki unga kirish huquqingiz yo‘q."));
        return t;
    }

    private long createTicket(long actor, long classId, String subject) {
        Long id = db.queryForObject("INSERT INTO ticket(classroom_id,student_id,subject) VALUES (?,?,?) RETURNING id", Long.class, classId, actor, subject);
        audit(actor, "CREATE_TICKET", id);
        return id;
    }

    private void requireMember(long actor, Long classId) {
        require(classId != null && memberships(actor, classId), tr(actor, "Siz bu sinfda emassiz. Ustozingiz bergan havola orqali qo‘shiling."));
    }

    private long memberships(long actor) {
        return count("SELECT count(*) FROM membership WHERE student_id=?", actor);
    }

    private boolean memberships(long actor, long classId) {
        return count("SELECT count(*) FROM membership WHERE classroom_id=? AND student_id=?", classId, actor) > 0;
    }

    private void ownClass(long actor, long id) {
        teacher(actor);
        require(count("SELECT count(*) FROM classroom WHERE id=? AND teacher_id=?", id, actor) > 0, tr(actor, "Sinf topilmadi yoki u sizga tegishli emas."));
    }

    private void teacher(long actor) {
        require(config.isTeacher(actor), tr(actor, "Bu amal faqat ustoz uchun."));
    }

    private String fullName(long actor) {
        return db.queryForObject("SELECT full_name FROM app_user WHERE id=?", String.class, actor);
    }

    private String studentName(long id) {
        var rows = db.queryForList("SELECT COALESCE(full_name,display_name) FROM app_user WHERE id=?", String.class, id);
        return rows.isEmpty() ? tr(id, "O‘quvchi") : rows.get(0);
    }

    private record State(String mode, Long ticket, Long classId) {
    }

    private State state(long user) {
        var rows = db.query("SELECT mode,ticket_id,class_id FROM chat_session WHERE user_id=?",
                (rs, n) -> new State(rs.getString("mode"), (Long) rs.getObject("ticket_id"), (Long) rs.getObject("class_id")), user);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void setState(long user, String mode, Long ticket, Long classId) {
        db.update("INSERT INTO chat_session(user_id,mode,ticket_id,class_id) VALUES (?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET mode=excluded.mode,ticket_id=excluded.ticket_id,class_id=excluded.class_id",
                user, mode, ticket, classId);
    }

    private void clearState(long user) {
        db.update("DELETE FROM chat_session WHERE user_id=?", user);
    }

    private void audit(long actor, String action, Long entity) {
        db.update("INSERT INTO audit_log(actor_id,action,entity_id) VALUES (?,?,?)", actor, action, entity);
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(db.queryForObject(sql, Long.class, args));
    }

    private String invite() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String inviteLink(String code) {
        return "https://t.me/" + config.botUsername() + "?start=" + code;
    }

    private long number(long actor, String value) {
        require(!value.isBlank(), tr(actor, "Raqam kiriting. «❓ Yordam» da namunalar bor."));
        long n = Long.parseLong(value);
        require(n > 0, tr(actor, "Raqam musbat bo‘lishi kerak."));
        return n;
    }

    private long page(long actor, String value) {
        long p = value.isBlank() ? 1 : number(actor, value);
        require(p <= 100000, tr(actor, "Sahifa raqami juda katta."));
        return p;
    }

    private static String arg(String[] parts, int index) {
        return parts.length > index ? parts[index] : "";
    }

    private String label(long actor, String kind) {
        return switch (kind) {
            case "photo" -> tr(actor, "Rasm");
            case "video", "video_note" -> tr(actor, "Video");
            case "voice" -> tr(actor, "Ovozli xabar");
            case "audio" -> tr(actor, "Audio");
            case "sticker" -> tr(actor, "Stiker");
            case "animation" -> "GIF";
            default -> tr(actor, "Fayl");
        };
    }

    private static void require(boolean condition, String text) {
        if (!condition) throw new UserError(text);
    }

    static String cut(String s, int length) {
        return s.length() <= length ? s : s.substring(0, s.offsetByCodePoints(0, Math.min(length, s.codePointCount(0, s.length()))));
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------- outgoing messages

    /** Bot's own replies to an action; silent because the person is looking at the chat anyway. */
    private void say(long chat, String html) {
        notify(chat, html, null, false);
    }

    private void say(long chat, String html, Object markup) {
        notify(chat, html, markup, false);
    }

    /** {@code loud} is for news from the other side (a question or an answer), which should ring the phone. */
    private void notify(long chat, String html, Object markup, boolean loud) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("chat_id", chat);
        payload.put("text", html);
        payload.put("parse_mode", "HTML");
        payload.put("link_preview_options", Map.of("is_disabled", true));
        payload.put("disable_notification", !loud);
        if (markup != null) payload.put("reply_markup", markup);
        api(chat, "sendMessage", payload);
    }

    /** Sends user content with a header, keeping it to one chat message where Telegram's limits allow. */
    private void relay(long chat, String header, String kind, String fileId, String body, Object markup) {
        relay(chat, header, kind, fileId, body, markup, true);
    }

    private void relay(long chat, String header, String kind, String fileId, String body, Object markup, boolean loud) {
        String withBody = body.isBlank() ? header : header + "\n\n" + esc(body);
        if (fileId == null) {
            if (withBody.length() <= 4000) notify(chat, withBody, markup, loud);
            else {
                notify(chat, header, null, loud);
                plain(chat, body, markup);
            }
        } else if (CAPTIONED.contains(kind)) {
            if (withBody.length() <= 1000) media(chat, kind, fileId, withBody, markup, loud);
            else {
                media(chat, kind, fileId, header, null, loud);
                plain(chat, body, markup);
            }
        } else {
            notify(chat, withBody, null, loud);
            media(chat, kind, fileId, null, markup, loud);
        }
    }

    private void media(long chat, String kind, String fileId, String captionHtml, Object markup, boolean loud) {
        String method = switch (kind) {
            case "photo" -> "sendPhoto";
            case "video" -> "sendVideo";
            case "voice" -> "sendVoice";
            case "audio" -> "sendAudio";
            case "sticker" -> "sendSticker";
            case "video_note" -> "sendVideoNote";
            case "animation" -> "sendAnimation";
            default -> "sendDocument";
        };
        var payload = new LinkedHashMap<String, Object>();
        payload.put("chat_id", chat);
        payload.put(kind, fileId);
        if (captionHtml != null) {
            payload.put("caption", captionHtml);
            payload.put("parse_mode", "HTML");
        }
        payload.put("disable_notification", !loud);
        if (markup != null) payload.put("reply_markup", markup);
        api(chat, method, payload);
    }

    /** Long user text without formatting, split below Telegram's 4096-character limit. */
    private void plain(long chat, String text, Object markup) {
        while (!text.isEmpty()) {
            int end = text.offsetByCodePoints(0, Math.min(3500, text.codePointCount(0, text.length())));
            var payload = new LinkedHashMap<String, Object>();
            payload.put("chat_id", chat);
            payload.put("text", text.substring(0, end));
            payload.put("disable_notification", true);
            text = text.substring(end);
            if (text.isEmpty() && markup != null) payload.put("reply_markup", markup);
            api(chat, "sendMessage", payload);
        }
    }

    private void flush(long chat, StringBuilder block) {
        if (block.isEmpty()) return;
        say(chat, block.toString().strip());
        block.setLength(0);
    }

    /** A 👍 on the person's own message confirms it was saved without adding another message to the chat. */
    private void react(long chat, long messageId) {
        if (messageId <= 0) return;
        api(chat, "setMessageReaction", Map.of("chat_id", chat, "message_id", messageId, "reaction", List.of(Map.of("type", "emoji", "emoji", "👍"))));
    }

    private void api(Long chat, String method, Object payload) {
        db.update("INSERT INTO outbox(job_type,chat_id,method,payload) VALUES ('API',?,?,?)", chat, method, Json.write(payload));
    }

    // ---------------------------------------------------------------- keyboards

    private Map<String, Object> studentMenu(long actor) {
        return keyboard(List.of(List.of(tr(actor, NEW)), List.of(tr(actor, MY), tr(actor, MY_CLASSES)), List.of(tr(actor, HELP), "🌐 Til / Язык")));
    }

    private Map<String, Object> teacherMenu(long actor) {
        return keyboard(List.of(List.of(tr(actor, INBOX)), List.of(tr(actor, CLASSES), tr(actor, ADD_CLASS)), List.of(tr(actor, STATS), tr(actor, HELP)), List.of("🌐 Til / Язык")));
    }

    private Map<String, Object> writingKeyboard(long actor) {
        return keyboard(List.of(List.of(tr(actor, DONE))));
    }

    private static Map<String, Object> keyboard(List<List<String>> rows) {
        return Map.of("keyboard", rows, "resize_keyboard", true, "is_persistent", true);
    }

    @SafeVarargs
    private static Map<String, Object> inline(List<Map<String, String>>... rows) {
        return inline(List.of(rows));
    }

    private static Map<String, Object> inline(List<List<Map<String, String>>> rows) {
        return Map.of("inline_keyboard", rows);
    }

    @SafeVarargs
    private static List<Map<String, String>> row(Map<String, String>... buttons) {
        return List.of(buttons);
    }

    private static Map<String, String> btn(String text, String data) {
        return Map.of("text", text, "callback_data", data);
    }

    private void addPager(long actor, List<List<Map<String, String>>> buttons, long page, long total, int size, String prefix) {
        var pager = new ArrayList<Map<String, String>>();
        if (page > 1) pager.add(btn(tr(actor, "◀️ Oldingi"), prefix + (page - 1)));
        if (page * size < total) pager.add(btn(tr(actor, "Keyingi ▶️"), prefix + (page + 1)));
        if (!pager.isEmpty()) buttons.add(pager);
    }

    private record Ticket(long id, long student, long teacher, long classId, String subject, String status,
                          String className, String studentName) {
        boolean open() {
            return status.equals("OPEN");
        }
    }

    private static final class UserError extends RuntimeException {
        UserError(String text) {
            super(text);
        }
    }
}
