package win.youhoo.moss;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** WordprocessingML export for the Markdown used by the minutes editor. */
final class MinutesDocx {
    static final String MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final int BODY_WIDTH = 9360;
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern ITEM = Pattern.compile("^([ \\t]*)(?:([-+*])|([0-9]{1,8})[.)])\\s+(.+)$");
    private static final Pattern INLINE = Pattern.compile("(\u0060[^\u0060\\n]+\u0060)|\\*\\*(.+?)\\*\\*|__(.+?)__|(?<!\\*)\\*([^*]+)\\*|\\[([^\\]\\n]+)\\]\\((https?://[^\\s)]+)\\)");
    private final StringBuilder body = new StringBuilder();
    private final StringBuilder numbers = new StringBuilder();
    private final Map<String,String> links = new LinkedHashMap<>();
    private final int[] listIds = new int[9];
    private final boolean[] ordered = new boolean[9];
    private int nextId;

    static byte[] encode(String fallbackTitle, String markdown) throws IOException {
        MinutesDocx doc = new MinutesDocx();
        doc.parse(fallbackTitle, markdown);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            String contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.";
            part(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + override("document",contentType+"document.main+xml")+override("styles",contentType+"styles+xml")
                    + override("numbering",contentType+"numbering+xml")+"</Types>");
            part(zip, "_rels/.rels", "<Relationships xmlns=\""+PACKAGE+"\">"+relationship("main","officeDocument","word/document.xml",false)+"</Relationships>");
            StringBuilder rels = new StringBuilder("<Relationships xmlns=\""+PACKAGE+"\">")
                    .append(relationship("styles","styles","styles.xml",false)).append(relationship("numbers","numbering","numbering.xml",false));
            for (Map.Entry<String,String> link : doc.links.entrySet())
                rels.append(relationship(link.getValue(),"hyperlink",link.getKey(),true));
            part(zip,"word/_rels/document.xml.rels",rels.append("</Relationships>").toString());
            part(zip,"word/styles.xml",styles());
            part(zip,"word/numbering.xml","<w:numbering xmlns:w=\""+W+"\">"+abstractList(0,false)+abstractList(1,true)+doc.numbers+"</w:numbering>");
            part(zip,"word/document.xml","<w:document xmlns:w=\""+W+"\" xmlns:r=\""+REL+"\"><w:body>"+doc.body
                    +"<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1300\" w:right=\"1273\" w:bottom=\"1300\" w:left=\"1273\" w:header=\"600\" w:footer=\"600\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>");
        }
        return bytes.toByteArray();
    }

    private static void part(ZipOutputStream zip,String name,String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((XML+xml).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
    private static String override(String part,String type) {
        return "<Override PartName=\"/word/"+part+".xml\" ContentType=\""+type+"\"/>";
    }
    private static String relationship(String id,String type,String target,boolean external) {
        return "<Relationship Id=\""+id+"\" Type=\""+REL+"/"+type+"\" Target=\""+escape(target)+"\""+(external?" TargetMode=\"External\"":"")+"/>";
    }
    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp=='&') out.append("&amp;");
            else if (cp=='<') out.append("&lt;");
            else if (cp=='>') out.append("&gt;");
            else if (cp=='"') out.append("&quot;");
            else if (cp==9 || cp==10 || cp==13 || cp>=32 && cp<=0xd7ff || cp>=0xe000 && cp<=0xfffd || cp>=0x10000 && cp<=0x10ffff)
                out.appendCodePoint(cp);
        });
        return out.toString();
    }
    private static String style(String id,String name,String paragraph,String run) {
        return "<w:style w:type=\"paragraph\" w:styleId=\""+id+"\"><w:name w:val=\""+name+"\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr>"
                +paragraph+"</w:pPr><w:rPr>"+run+"</w:rPr></w:style>";
    }
    private static String styles() {
        StringBuilder s = new StringBuilder("<w:styles xmlns:w=\""+W+"\"><w:docDefaults><w:rPrDefault><w:rPr>"
                +"<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Microsoft YaHei\"/><w:sz w:val=\"22\"/><w:lang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/>"
                +"</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"100\" w:line=\"320\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
                +"<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>");
        s.append(style("Title","Title","<w:keepNext/><w:keepLines/><w:spacing w:after=\"240\"/>","<w:b/><w:color w:val=\"000000\"/><w:sz w:val=\"40\"/>"));
        for (int level=1;level<=6;level++)
            s.append(style("Heading"+level,"heading "+level,"<w:keepNext/><w:keepLines/><w:spacing w:before=\"200\" w:after=\"70\"/><w:outlineLvl w:val=\""+(level-1)+"\"/>",
                    "<w:b/><w:sz w:val=\""+(level==1?30:level==2?26:23)+"\"/>"));
        s.append(style("Quote","Quote","<w:ind w:left=\"360\"/>","<w:color w:val=\"434D56\"/>"));
        s.append(style("Code","Code","<w:spacing w:after=\"30\"/>","<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/><w:sz w:val=\"20\"/>"));
        return s.append("</w:styles>").toString();
    }
    private static String abstractList(int id,boolean decimal) {
        StringBuilder s=new StringBuilder("<w:abstractNum w:abstractNumId=\""+id+"\"><w:multiLevelType w:val=\"multilevel\"/>");
        for (int level=0;level<9;level++)
            s.append("<w:lvl w:ilvl=\"").append(level).append("\"><w:start w:val=\"1\"/><w:numFmt w:val=\"")
                    .append(decimal?"decimal":"bullet").append("\"/><w:lvlText w:val=\"").append(decimal?"%"+(level+1)+".":"•")
                    .append("\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"").append(360*(level+1))
                    .append("\" w:hanging=\"260\"/></w:pPr></w:lvl>");
        return s.append("</w:abstractNum>").toString();
    }
    private int list(int level,boolean numbered,int start) {
        Arrays.fill(listIds,level+1,listIds.length,0);
        if (listIds[level]==0 || ordered[level]!=numbered) {
            int id=++nextId;
            listIds[level]=id; ordered[level]=numbered;
            numbers.append("<w:num w:numId=\"").append(id).append("\"><w:abstractNumId w:val=\"").append(numbered?1:0)
                    .append("\"/><w:lvlOverride w:ilvl=\"").append(level).append("\"><w:startOverride w:val=\"")
                    .append(start).append("\"/></w:lvlOverride></w:num>");
        }
        return listIds[level];
    }
    private String run(String value,String properties) {
        return "<w:r>"+(properties.isEmpty()?"":"<w:rPr>"+properties+"</w:rPr>")+"<w:t xml:space=\"preserve\">"+escape(value)+"</w:t></w:r>";
    }
    private String inline(String text,boolean bold) {
        StringBuilder result=new StringBuilder();
        String base=bold?"<w:b/>":"";
        Matcher m=INLINE.matcher(text);int at=0;
        while (m.find()) {
            if (m.start()>at) result.append(run(text.substring(at,m.start()),base));
            if (m.group(1)!=null) result.append(run(m.group(1).substring(1,m.group(1).length()-1),"<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>"+base));
            else if (m.group(2)!=null || m.group(3)!=null) result.append(run(m.group(2)!=null?m.group(2):m.group(3),"<w:b/>"));
            else if (m.group(4)!=null) result.append(run(m.group(4),base+"<w:i/>"));
            else {
                String id=links.computeIfAbsent(m.group(6),url->"link"+(links.size()+1));
                result.append("<w:hyperlink r:id=\"").append(id).append("\">").append(run(m.group(5),base+"<w:color w:val=\"245675\"/><w:u w:val=\"single\"/>")).append("</w:hyperlink>");
            }
            at=m.end();
        }
        if (at<text.length()) result.append(run(text.substring(at),base));
        return result.toString();
    }
    private void paragraph(String properties,String runs) {
        body.append("<w:p>");
        if (!properties.isEmpty()) body.append("<w:pPr>").append(properties).append("</w:pPr>");
        body.append(runs).append("</w:p>");
    }
    private static String use(String style) {return "<w:pStyle w:val=\""+style+"\"/>";}
    private static List<String> cells(String line) {
        List<String> result=MinutesFormat.tableCells(line);
        result.replaceAll(cell->cell.replace("\\|","|"));
        return result;
    }
    private void table(List<List<String>> rows,int columns) {
        int[] weights=new int[columns];Arrays.fill(weights,3);
        for (List<String> row:rows) for (int i=0;i<row.size();i++)
            weights[i]=Math.max(weights[i],Math.min(24,row.get(i).codePointCount(0,row.get(i).length())));
        int total=Arrays.stream(weights).sum(),left=BODY_WIDTH;
        body.append("<w:tbl><w:tblPr><w:tblW w:type=\"dxa\" w:w=\"").append(BODY_WIDTH).append("\"/><w:tblBorders>");
        for (String side:new String[]{"top","left","bottom","right","insideH","insideV"})
            body.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:color=\"CFD7DC\"/>");
        body.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:left w:w=\"90\" w:type=\"dxa\"/><w:right w:w=\"90\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>");
        for (int i=0;i<columns;i++) {
            weights[i]=i==columns-1?left:BODY_WIDTH*weights[i]/total;left-=weights[i];
            body.append("<w:gridCol w:w=\"").append(weights[i]).append("\"/>");
        }
        body.append("</w:tblGrid>");
        for (int i=0;i<rows.size();i++) {
            body.append("<w:tr>");if (i==0) body.append("<w:trPr><w:tblHeader/></w:trPr>");
            for (int j=0;j<columns;j++) {
                body.append("<w:tc><w:tcPr><w:tcW w:type=\"dxa\" w:w=\"").append(weights[j]).append("\"/>");
                if (i==0) body.append("<w:shd w:val=\"clear\" w:fill=\"E8EEF1\"/>");
                body.append("</w:tcPr>");paragraph("<w:spacing w:before=\"60\" w:after=\"60\"/>",inline(j<rows.get(i).size()?rows.get(i).get(j):"",i==0));body.append("</w:tc>");
            }
            body.append("</w:tr>");
        }
        body.append("</w:tbl>");
    }
    private void parse(String fallback,String markdown) {
        String content=markdown.replace("\r\n","\n").replace('\r','\n').trim();
        content=content.replaceFirst("(?is)^\\x60{3}(?:md|markdown)[ \\t]*\\n(.*)\\n\\x60{3}$","$1");
        String[] lines=content.split("\n",-1);int i=0;
        Matcher first=HEADING.matcher(lines[0].trim());
        if (first.matches() && first.group(1).length()==1) {paragraph(use("Title"),inline(first.group(2),false));i++;}
        else paragraph(use("Title"),run(fallback,""));
        boolean code=false;
        while (i<lines.length) {
            String raw=lines[i++],line=raw.trim();
            if (line.startsWith("\u0060\u0060\u0060")) {code=!code;Arrays.fill(listIds,0);continue;}
            if (code) {paragraph(use("Code"),run(raw,""));continue;}
            if (line.isEmpty()) {Arrays.fill(listIds,0);continue;}
            Matcher item=ITEM.matcher(raw);
            if (item.matches()) {
                int level=Math.min(8,item.group(1).replace("\t","    ").length()/2);
                boolean numeric=item.group(3)!=null;int id=list(level,numeric,numeric?Integer.parseInt(item.group(3)):1);
                paragraph("<w:numPr><w:ilvl w:val=\""+level+"\"/><w:numId w:val=\""+id+"\"/></w:numPr>",inline(item.group(4),false));continue;
            }
            Arrays.fill(listIds,0);
            if (line.contains("|") && i<lines.length) {
                List<String> header=cells(line);int columns=header.size();
                if (MinutesFormat.tableDivider(lines[i],columns)) {
                    List<List<String>> rows=new ArrayList<>();rows.add(header);i++;
                    while (i<lines.length && lines[i].contains("|")) {
                        List<String> row=cells(lines[i]);if (row.size()>columns) break;
                        rows.add(row);i++;
                    }
                    table(rows,columns);continue;
                }
            }
            Matcher heading=HEADING.matcher(line);
            if (heading.matches()) paragraph(use("Heading"+heading.group(1).length()),inline(heading.group(2),false));
            else if (line.startsWith(">")) paragraph(use("Quote"),inline(line.substring(1).trim(),false));
            else if (!line.matches("(?:-{3,}|\\*{3,}|_{3,})")) paragraph("",inline(line,false));
        }
    }
}
