package uz.eduping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Telegram command descriptions follow the explicitly selected chat language. */
final class BotCommands {
    private BotCommands() {}

    static List<Map<String,String>> commands(boolean teacher, String language) {
        boolean ru = "ru".equals(language);
        String[][] items = teacher ? new String[][] {
                {"inbox", "Kelgan savollar", "Входящие вопросы"},
                {"classes", "Sinflar", "Классы"}, {"class", "Yangi sinf", "Новый класс"},
                {"stats", "Holat", "Статистика"}, {"help", "Yordam", "Помощь"}
        } : new String[][] {
                {"start", "Boshlash", "Начать"}, {"new", "Yangi savol", "Новый вопрос"},
                {"my", "Savollarim", "Мои вопросы"}, {"classes", "Sinflarim", "Мои классы"},
                {"name", "Ismni o‘zgartirish", "Изменить имя"}, {"help", "Yordam", "Помощь"},
                {"privacy", "Maxfiylik", "Конфиденциальность"}
        };
        var commands = new ArrayList<Map<String,String>>();
        for (var item : items) commands.add(Map.of("command", item[0], "description", item[ru ? 2 : 1]));
        commands.add(Map.of("command", "language", "description", ru ? "Выбрать язык" : "Tilni tanlash"));
        return List.copyOf(commands);
    }
}
