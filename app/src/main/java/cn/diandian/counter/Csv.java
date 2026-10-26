package cn.diandian.counter;

/** Spreadsheet-safe quoting: user text cannot become an Excel/Sheets formula. */
public final class Csv {
    private Csv() { }
    public static String cell(String value) {
        if (value == null) return "\"\"";
        String safe = value;
        String trimmed = Strings.strip(value);
        if ((!trimmed.isEmpty() && "=+-@\t\r\n".indexOf(trimmed.charAt(0)) >= 0)
                || value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")) safe = "'" + value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
