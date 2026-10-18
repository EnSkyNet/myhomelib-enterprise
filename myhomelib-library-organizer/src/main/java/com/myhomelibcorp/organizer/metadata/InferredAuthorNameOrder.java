package com.myhomelibcorp.organizer.metadata;

import java.util.Locale;
import java.util.Set;

/**
 * Conservative fallback for creator strings lacking the structured surname boundary.
 * It is never used for FB2 structured names. Ambiguous names remain unchanged and
 * must be confirmed in the Organizer preview rather than silently guessed.
 */
final class InferredAuthorNameOrder {
    private static final Set<String> GIVEN_NAMES = Set.of(
            "алёна", "алена", "влад", "глеб", "даурен", "эрнест", "єрнест", "александр", "александра", "алексей", "алексій", "анастасия", "анастасія", "андрей", "андрій",
            "анна", "антон", "артем", "артём", "артемій", "богдан", "валентин", "валентина", "валерий", "валерій",
            "василий", "василь", "вера", "віра", "виктор", "віктор", "виктория", "вікторія", "виталий", "віталій",
            "владимир", "володимир", "владислав", "вячеслав", "в'ячеслав", "галина", "геннадий", "геннадій",
            "георгий", "георгій", "григорий", "григорій", "даниил", "данило", "дарья", "дарія", "денис",
            "дмитрий", "дмитро", "евгений", "євген", "євгеній", "екатерина", "катерина", "елена", "олена",
            "елизавета", "єлизавета", "иван", "іван", "игорь", "ігор", "илья", "ілля", "ирина", "ірина",
            "кирилл", "кирил", "кири́ло", "кирила", "костянтин", "константин", "лариса", "лев", "леонід",
            "леонид", "лидия", "лідія", "людмила", "марина", "мария", "марія", "максим", "михаил", "михайло",
            "микола", "никита", "микита", "наталья", "наталія", "николай", "олег", "олександр", "олександра", "олексій", "егор", "єгор", "оксана", "ольга", "павел",
            "павло", "петр", "петро", "роман", "руслан", "светлана", "світлана", "семен", "семеній", "сергей",
            "сергій", "софия", "софія", "станислав", "станіслав", "степан", "таисия", "тетяна", "татьяна",
            "тимофей", "тимофій", "федор", "федір", "юлия", "юлія", "юрий", "юрій", "ярослав", "яна",
            "alexander", "alexandra", "alex", "alexey", "andrew", "anna", "anton", "dmitry", "dmitriy", "ivan",
            "john", "james", "george", "michael", "peter", "sergey", "sergei", "vladimir", "victor", "victoria",
            "olga", "maria", "mary", "paul", "robert", "richard", "david", "william", "thomas", "charles"
    );

    private InferredAuthorNameOrder() { }

    static NormalizedAuthor fromGivenFirst(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf(',') >= 0) return null;
        String[] parts = raw.strip().split("\\s+");
        if (parts.length != 2 && parts.length != 3) return null;
        String first = parts[0].toLowerCase(Locale.ROOT);
        String family = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        if (!GIVEN_NAMES.contains(first) || GIVEN_NAMES.contains(family)) return null;
        if (!simpleWord(parts[0]) || !simpleWord(parts[parts.length - 1])) return null;
        String patronymic = parts.length == 3 ? parts[1] : "";
        if (parts.length == 3 && !looksLikePatronymic(patronymic)) return null;
        String familyName = parts[parts.length - 1];
        return new NormalizedAuthor(familyName + " " + parts[0] + (patronymic.isBlank() ? "" : " " + patronymic),
                parts[0], patronymic, familyName, true);
    }

    /** An explicit known given name in the second position also makes a two/three-part
     *  surname-first creator safe to preserve. This does not reverse already-correct names. */
    static NormalizedAuthor fromSurnameFirst(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf(',') >= 0) return null;
        String[] parts = raw.strip().split("\\s+");
        if (parts.length != 2 && parts.length != 3) return null;
        if (GIVEN_NAMES.contains(parts[0].toLowerCase(Locale.ROOT))
                || !GIVEN_NAMES.contains(parts[1].toLowerCase(Locale.ROOT))
                || !simpleWord(parts[0]) || !simpleWord(parts[1])) return null;
        if (parts.length == 3 && !looksLikePatronymic(parts[2])) return null;
        String middle = parts.length == 3 ? parts[2] : "";
        return new NormalizedAuthor(raw.strip(), parts[1], middle, parts[0], true);
    }

    private static boolean simpleWord(String text) {
        return text.length() >= 2 && text.codePoints().allMatch(cp -> Character.isLetter(cp) || cp == '-');
    }

    private static boolean looksLikePatronymic(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.endsWith("ович") || lower.endsWith("евич") || lower.endsWith("йович")
                || lower.endsWith("івич") || lower.endsWith("ич") || lower.endsWith("овна")
                || lower.endsWith("евна") || lower.endsWith("івна") || lower.endsWith("ївна")
                || lower.endsWith("ична") || lower.endsWith("инична");
    }
}
