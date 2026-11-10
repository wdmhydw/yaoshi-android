package win.youhoo.moss;
import org.json.JSONObject;

public final class LivePreviewTest {
    private static int checked;
    private static void check(boolean ok){checked++;if(!ok)throw new AssertionError("case "+checked);}
    private static JSONObject draft(String text)throws Exception{return LivePreview.draft(text,30,24,1);}
    public static void main(String[] args)throws Exception{
        check(draft("")==null);
        check(draft("[0.00]")==null);
        check(draft("[0.00][S01]")==null);
        check(draft("[0.00][S01]   ")==null);
        JSONObject row=draft("[1.25][S02]今天讨论一下");
        check(row.getString("text").equals("今天讨论一下"));
        check(row.getString("speaker").equals("片段2-S02"));
        check(row.getDouble("start")==31.25);
        check(row.getBoolean("provisional")&&!row.has("end"));
        check(draft("[1.25][S02]今天讨论一下[2.").getString("text").equals("今天讨论一下"));
        check(draft("[1.25][S02]今天讨论一下[2.50]")==null);
        check(draft("[1][S01]第一句[2][2.1][S02]第二句").getString("text").equals("第二句"));
        check(draft("[1][S01]第一句[2][2.1][S02]")==null);
        check(draft("[25][S01]超出片段")==null);
        check(draft("[-1][S01]负数")==null);
        check(draft("[1][invalid]未知")==null);
        check(draft("[0] [S01]他说：\"你好\"\n下一行").getString("text").equals("他说：\"你好\"\n下一行"));
        check(draft("[.25][S01]😀").getDouble("start")==30.25);
        System.out.println("LivePreview: "+checked+" provisional-text boundary assertions passed.");
    }
}
