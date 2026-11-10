package win.youhoo.moss;

import org.w3c.dom.Document;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class MinutesDocxTest {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Map<String,Document> parts(byte[] data)throws Exception{
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        Map<String,Document> parts=new HashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(data))){
            for(ZipEntry e;(e=zip.getNextEntry())!=null;)
                parts.put(e.getName(),factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes())));
        }
        return parts;
    }
    public static void main(String[] args)throws Exception{
        String sample="# 试用反馈会议纪要\n\n会议决定先在两个小组试用新版导出流程，下周复核反馈后再扩大范围。\n\n"
                +"## 决定与依据\n\n- 保留 **Markdown**，用于继续编辑或归档。\n- 提供 **Word 文档**，供评审时批注和修订。[会议原声](source:seg_2)\n\n"
                +"## 待办事项\n\n| 工作 | 负责人 | 完成时间 |\n| --- | --- | --- |\n| 汇总两个小组的试用意见 | 项目组 | 周四 |\n| 根据反馈调整导出样式 | 文档组 | 下周一 |\n\n"
                +"## 下次复核\n\n1. 核对待办表在手机和桌面上的显示。\n2. 确认归档文档可搜索、可编辑。";
        String resolved=MinutesFormat.fileText(sample);
        byte[] word=MinutesDocx.encode("备用标题",resolved);
        Map<String,Document> entries=parts(word);Document document=entries.get("word/document.xml");
        String text=document.getDocumentElement().getTextContent();
        check(entries.keySet().containsAll(Arrays.asList("[Content_Types].xml","_rels/.rels","word/styles.xml","word/numbering.xml","word/_rels/document.xml.rels")),"package relationships");
        check(text.startsWith("试用反馈会议纪要")&&!text.contains("备用标题")&&!text.contains("会议原声")&&!text.contains("source:"),"title and no playback reference");
        check(document.getElementsByTagNameNS(W,"tbl").getLength()==1&&document.getElementsByTagNameNS(W,"tc").getLength()==9,"editable table cells");
        check(document.getElementsByTagNameNS(W,"tblHeader").getLength()==1&&document.getElementsByTagNameNS(W,"numPr").getLength()==4,"repeatable header and editable lists");
        check(document.getElementsByTagNameNS(W,"b").getLength()>=2&&!text.contains("**"),"bold formatting");
        String ref="[00:01:23](source:seg_2)",second="[回听](source:second)";
        String annotated="## 结论\r\n\r\n  - 14:30 开会（原文："+ref+"、"+second+"）。\r\n"
                +"  - 期限 [17:00]；见[文档](https://example.org/plan)。 "+ref+"\r\n"
                +"- "+second+"\r\n\r\n"
                +"| 事项 | 原文引用 | 期限 |\r\n| :--- | :---: | ---: |\r\n"
                +"| 复核 A\\|B | "+ref+" | 周五 |\r\n| 开会 | — | 17:00 |\r\n";
        String clean="## 结论\r\n\r\n  - 14:30 开会。\r\n"
                +"  - 期限 [17:00]；见[文档](https://example.org/plan)。\r\n\r\n"
                +"| 事项 | 期限 |\r\n| :--- | ---: |\r\n| 复核 A\\|B | 周五 |\r\n| 开会 | 17:00 |\r\n";
        check(clean.equals(MinutesFormat.fileText(annotated)),"file export removes playback groups, standalone bullets, and citation-only column");
        check(clean.equals(MinutesFormat.fileText(clean)),"file export is idempotent");
        Map<String,Document> exported=parts(MinutesDocx.encode("验收",MinutesFormat.fileText(annotated)));
        Document cleanedDoc=exported.get("word/document.xml");String visible=cleanedDoc.getDocumentElement().getTextContent();
        check(visible.contains("14:30 开会。")&&visible.contains("复核 A|B")&&visible.contains("[17:00]")&&!visible.contains("00:01:23")&&!visible.contains("原文引用"),"Word keeps meeting times and text");
        check(cleanedDoc.getElementsByTagNameNS(W,"gridCol").getLength()==2&&cleanedDoc.getElementsByTagNameNS(W,"hyperlink").getLength()==1,"Word keeps real columns and external link");
        String mixed="| 事项 | 原文 |\n| --- | --- |\n| 甲 | "+ref+" |\n| 乙 | “按原计划执行。” "+second+" |";
        String mixedResult=MinutesFormat.fileText(mixed);
        check(mixedResult.contains("| 原文 |")&&mixedResult.contains("“按原计划执行。”")&&!mixedResult.contains("source:"),"source column with actual quoted material survives");
        String tick=String.valueOf((char)96),fence=tick.repeat(3);
        String literal=tick+ref+tick+"\n"+fence+"text\n"+ref+"\n"+fence;
        check(MinutesFormat.fileText(literal+"\n处理"+ref).equals(literal+"\n处理"),"literal inline and fenced code survive");
        check(MinutesFormat.fileText(fence+"markdown\n# 结论\n通过。"+ref+"\n"+fence).equals(fence+"markdown\n# 结论\n通过。\n"+fence),"clean AI Markdown inside a document fence");
        check(MinutesFormat.fileText("见 [外部录音](https://example.org/audio) 和 [00:01:23]。").equals("见 [外部录音](https://example.org/audio) 和 [00:01:23]。"),"ordinary links and unanchored times survive verbatim");
        String edge="3. A & B <讨论> 😀\n   - 子项\n\n| 字段 | 内容 |\n| --- | --- |\n| 分隔符 | A\\|B |\n\n[资料](https://example.org/a?x=1&y=2)\n"+"控制\u0001字符";
        Map<String,Document> other=parts(MinutesDocx.encode("会议纪要",edge));Document doc=other.get("word/document.xml");
        String preserved=doc.getDocumentElement().getTextContent();
        check(preserved.startsWith("会议纪要")&&preserved.contains("A & B <讨论> 😀")&&preserved.contains("A|B")&&preserved.contains("控制字符"),"plain or pasted text and valid XML");
        check(doc.getElementsByTagNameNS(W,"hyperlink").getLength()==1&&other.get("word/_rels/document.xml.rels").getElementsByTagNameNS("*","Relationship").getLength()==3,"document hyperlink");
        check("3".equals(other.get("word/numbering.xml").getElementsByTagNameNS(W,"startOverride").item(0).getAttributes().getNamedItemNS(W,"val").getNodeValue()),"ordered list retains its start");
        if(args.length==1){Path path=Paths.get(args[0]);Files.write(path,word,StandardOpenOption.CREATE_NEW);}
        System.out.println("PASS Word minutes document, formatting, source and pasted-text checks");
    }
}
