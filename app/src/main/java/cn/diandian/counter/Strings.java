package cn.diandian.counter;

final class Strings {
    private Strings() { }
    static String strip(String text) {
        int start = 0, end = text.length();
        while (start < end && Character.isWhitespace(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start));
        while (end > start && Character.isWhitespace(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end));
        return text.substring(start, end);
    }
}
