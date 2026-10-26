package cn.diandian.counter;

import org.junit.Test;
import static org.junit.Assert.*;

public class CsvTest {
    @Test public void preservesQuotesAndNewlines() { assertEquals("\"阅读,\"\"今天\"\"\n记录\"", Csv.cell("阅读,\"今天\"\n记录")); }
    @Test public void neutralizesSpreadsheetFormulasIncludingLeadingWhitespace() {
        for (String s : new String[]{"=1+1", "+SUM(A1)", "-1+2", "@SUM(1)", "  =HYPERLINK(1)", "\t=1", "\n=1"})
            assertTrue(s, Csv.cell(s).startsWith("\"'"));
    }
    @Test public void keepsOrdinaryChineseAndNull() { assertEquals("\"喝水\"", Csv.cell("喝水")); assertEquals("\"\"", Csv.cell(null)); }
    @Test public void unicodeWhitespaceIsRemovedWithoutBreakingEmoji() { assertEquals("喝水", Strings.strip("　 喝水　")); }
}
