package com.semime.nasepub;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** EPUB 2/3 metadata extraction and safe local extraction. No Android dependencies. */
public final class EpubParser {
    private EpubParser() { }
    public static final class BookInfo {
        public String title = "名称未設定";
        public String author = "";
        public String cover = "";
        public final List<String> chapters = new ArrayList<>();
    }
    private static final long MAX_EXPANDED_BYTES = 350L * 1024 * 1024;
    private static final int MAX_ENTRIES = 8000;
    public static BookInfo extractAndParse(File sourceEpub, File destination) throws Exception {
        if (!destination.exists() && !destination.mkdirs())
            throw new IllegalStateException("展開先を作成できません");
        String destPrefix = destination.getCanonicalPath() + File.separator;
        byte[] buf = new byte[32768];
        long expanded = 0;
        int entryCount = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(sourceEpub))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES) throw new IllegalArgumentException("EPUBのファイル数が多すぎます");
                File out = new File(destination, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(destPrefix)) throw new IllegalArgumentException("不正なEPUB内パス");
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("フォルダを作成できません");
                } else {
                    File parent = out.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) throw new IllegalStateException("フォルダを作成できません");
                    try (OutputStream fout = new FileOutputStream(out)) {
                        int count;
                        while ((count = zin.read(buf)) != -1) {
                            expanded += count;
                            if (expanded > MAX_EXPANDED_BYTES) throw new IllegalArgumentException("EPUBが大きすぎます");
                            fout.write(buf, 0, count);
                        }
                    }
                }
                zin.closeEntry();
            }
        }
        if (entryCount == 0) throw new IllegalArgumentException("EPUBのZIPデータを読み取れません");
        return parseExtracted(destination);
    }
    public static BookInfo parseExtracted(File destination) throws Exception {
        Document container = readXml(new File(destination, "META-INF/container.xml"));
        NodeList roots = container.getElementsByTagNameNS("*", "rootfile");
        if (roots.getLength() == 0) throw new IllegalArgumentException("EPUBのcontainer.xmlにrootfileがありません");
        String opfPath = ((Element) roots.item(0)).getAttribute("full-path");
        File opf = resolveInside(destination, destination, opfPath);
        Document packageDoc = readXml(opf);
        BookInfo info = new BookInfo();
        NodeList titles = packageDoc.getElementsByTagNameNS("*", "title");
        if (titles.getLength() > 0) {
            String title = titles.item(0).getTextContent().trim();
            if (!title.isEmpty()) info.title = title;
        }
        NodeList creators = packageDoc.getElementsByTagNameNS("*", "creator");
        if (creators.getLength() > 0) info.author = creators.item(0).getTextContent().trim();
        final class Item {
            String href;
            String type;
            String props;
            Item(String h, String t, String p) { href=h; type=t; props=p; }
        }
        Map<String, Item> items = new HashMap<>();
        String coverId = "";
        NodeList meta = packageDoc.getElementsByTagNameNS("*", "meta");
        for (int i=0; i<meta.getLength(); i++) {
            Element e = (Element) meta.item(i);
            if ("cover".equalsIgnoreCase(e.getAttribute("name"))) coverId = e.getAttribute("content");
        }
        File contentRoot = opf.getParentFile();
        NodeList manifests = packageDoc.getElementsByTagNameNS("*", "manifest");
        if (manifests.getLength() == 0) throw new IllegalArgumentException("EPUBのmanifestがありません");
        NodeList entries = ((Element) manifests.item(0)).getElementsByTagNameNS("*", "item");
        for (int i=0; i<entries.getLength(); i++) {
            Element e = (Element) entries.item(i);
            String id = e.getAttribute("id");
            String href = e.getAttribute("href");
            if (id.isEmpty() || href.isEmpty()) continue;
            Item item = new Item(href, e.getAttribute("media-type"), e.getAttribute("properties"));
            items.put(id, item);
            if (item.props.contains("cover-image")) coverId = id;
        }
        Item cover = items.get(coverId);
        if (cover == null) {
            for (Map.Entry<String, Item> pair : items.entrySet()) {
                Item it = pair.getValue();
                String lower = it.href.toLowerCase(java.util.Locale.ROOT);
                if (it.type.startsWith("image/") && lower.contains("cover")) { cover = it; break; }
            }
        }
        if (cover != null) {
            try {
                File c = resolveInside(destination, contentRoot, cover.href);
                if (c.isFile() && !cover.type.equals("image/svg+xml")) info.cover = relativize(destination, c);
            } catch (IllegalArgumentException ignored) { }
        }
        NodeList spines = packageDoc.getElementsByTagNameNS("*", "spine");
        if (spines.getLength() > 0) {
            NodeList refs = ((Element) spines.item(0)).getElementsByTagNameNS("*", "itemref");
            for (int i=0; i<refs.getLength(); i++) {
                Element e = (Element) refs.item(i);
                Item item = items.get(e.getAttribute("idref"));
                if (item == null || !("application/xhtml+xml".equals(item.type) || "text/html".equals(item.type))) continue;
                File chapter = resolveInside(destination, contentRoot, item.href);
                if (chapter.isFile()) info.chapters.add(relativize(destination, chapter));
            }
        }
        if (info.chapters.isEmpty()) throw new IllegalArgumentException("EPUBに表示できる本文がありません");
        return info;
    }
    private static File resolveInside(File root, File baseDir, String href) throws Exception {
        String clean = href.split("[#?]", 2)[0];
        String decoded = URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8");
        File f = new File(baseDir, decoded).getCanonicalFile();
        String allowed = root.getCanonicalPath() + File.separator;
        if (!f.getPath().startsWith(allowed)) throw new IllegalArgumentException("EPUB内パスが不正です");
        return f;
    }
    private static String relativize(File root, File child) throws Exception {
        String base = root.getCanonicalPath() + File.separator;
        String path = child.getCanonicalPath();
        if (!path.startsWith(base)) throw new IllegalArgumentException("EPUB内パスが不正です");
        return path.substring(base.length()).replace(File.separatorChar, '/');
    }
    private static Document readXml(File file) throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("必要なEPUBファイルがありません: " + file.getName());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (InputStream in = new FileInputStream(file)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }
}
