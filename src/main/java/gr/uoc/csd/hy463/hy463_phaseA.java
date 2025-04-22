package gr.uoc.csd.hy463;

import gr.uoc.csd.hy463.NXMLFileReader;
import mitos.stemmer.Stemmer;
import org.tartarus.snowball.ext.englishStemmer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * Phase A implementation with block-based partial indexing and merging,
 * using Snowball EnglishStemmer for English tokens and Mitos Stemmer for others,
 * and a single PostingFile with pointers and VocabularyFile offsets.
 */
public class hy463_phaseA {
    private static final int BLOCK_SIZE = 100;
    private static final englishStemmer englishStemmer = new englishStemmer();

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java -cp <jar> gr.uoc.csd.hy463.hy463_phaseA <nxmlFolderPath>");
            System.exit(1);
        }
        File root = new File(args[0]);
        if (!root.isDirectory()) {
            System.err.println("Invalid folder: " + args[0]);
            System.exit(1);
        }
        // Initialize Mitos stemmer
        Stemmer.Initialize();
        // Load stopwords
        Set<String> stop = loadStopwords("stopwordsEn.txt");
        stop.addAll(loadStopwords("stopwordsGr.txt"));
        // Collect and sort file paths
        List<String> allFiles = new ArrayList<>();
        collectFiles(root, allFiles);
        Collections.sort(allFiles);
        // Assign docIDs
        Map<String,Integer> docIdMap = new LinkedHashMap<>();
        int nextDocID = 1;
        for (String fp : allFiles) {
            docIdMap.put(fp, nextDocID++);
        }
        // Prepare directories
        File base = new File("CollectionIndex"); base.mkdirs();
        File partialDir = new File(base, "PartialIndexes"); partialDir.mkdirs();
        File finalInvDir = new File(base, "InvertedLists"); finalInvDir.mkdirs();
        // Build partial indexes
        int blockNum = 0;
        for (int i = 0; i < allFiles.size(); i += BLOCK_SIZE) {
            blockNum++;
            Map<String,Map<Integer,List<Integer>>> blockIndex = new TreeMap<>();
            List<String> blockFiles = allFiles.subList(i, Math.min(i+BLOCK_SIZE, allFiles.size()));
            indexBlock(blockFiles, docIdMap, stop, blockIndex);
            writePartialIndex(blockIndex, new File(partialDir, "block"+blockNum));
        }
        // Merge partials
        Map<String,Map<Integer,List<Integer>>> finalIndex = new TreeMap<>();
        mergePartials(partialDir, finalIndex);
        // Output files
        File vocabFile = new File(base, "VocabularyFile.txt");
        File docsFile  = new File(base, "DocumentsFile.txt");
        File postingFile = new File(base, "PostingFile.txt");
        Map<String,Long> pointers = writePostingFile(postingFile, finalIndex);
        writeVocabulary(vocabFile, finalIndex, pointers);
        writeDocuments(docsFile, docIdMap, finalIndex);
        System.out.println("Indexing complete: " + finalIndex.size() + " terms, " + docIdMap.size() + " docs.");
    }

    private static void indexBlock(List<String> files,
                                   Map<String,Integer> docIdMap,
                                   Set<String> stop,
                                   Map<String,Map<Integer,List<Integer>>> idx) {
        for (String fp : files) {
            int docID = docIdMap.get(fp);
            try {
                NXMLFileReader xml = new NXMLFileReader(new File(fp));
                Map<String,String> tags = extractTags(xml);
                int pos = 1;
                for (String text : tags.values()) {
                    if (text == null) continue;
                    for (String tok : Tokenizer.getTokens(text)) {
                        if (stop.contains(tok) || isFilteredNumeric(tok)) { pos++; continue; }
                        // Choose stemmer based on ASCII content
                        String stem;
                        if (tok.matches("^[A-Za-z]+$")) {
                            englishStemmer.setCurrent(tok);
                            englishStemmer.stem();
                            stem = englishStemmer.getCurrent().toLowerCase();
                        } else {
                            String raw = Stemmer.Stem(tok);
                            stem = (raw == null || raw.isEmpty()) ? tok : raw.toLowerCase();
                        }
                        idx.computeIfAbsent(stem, t->new TreeMap<>())
                                .computeIfAbsent(docID, d->new ArrayList<>())
                                .add(pos);
                        pos++;
                    }
                }
            } catch (Exception e) {
                System.err.println("Error indexing file " + fp + ": " + e.getMessage());
            }
        }
    }

    private static Map<String,Long> writePostingFile(
            File postingFile,
            Map<String,Map<Integer,List<Integer>>> index) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(postingFile, "rw")) {
            raf.setLength(0);
            raf.write(("Term DocID TF Positions" + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            Map<String,Long> pointers = new TreeMap<>();
            for (String term : index.keySet()) {
                long pointer = raf.getFilePointer();
                pointers.put(term, pointer);
                for (Map.Entry<Integer,List<Integer>> e : index.get(term).entrySet()) {
                    int docID = e.getKey();
                    List<Integer> posList = e.getValue();
                    int tf = posList.size();
                    StringJoiner sj = new StringJoiner(",");
                    for (int p : posList) sj.add(Integer.toString(p));
                    String line = term + " " + docID + " " + tf + " " + sj + System.lineSeparator();
                    raf.write(line.getBytes(StandardCharsets.UTF_8));
                }
            }
            return pointers;
        }
    }

    private static void writeVocabulary(
            File vocabFile,
            Map<String,Map<Integer,List<Integer>>> index,
            Map<String,Long> pointers) throws IOException {
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(vocabFile), StandardCharsets.UTF_8))) {
            w.println("Term DocumentFrequency Pointer");
            for (String term : index.keySet()) {
                int df = index.get(term).size();
                long ptr = pointers.get(term);
                w.println(term + " " + df + " " + ptr);
            }
        }
    }

    private static void writeDocuments(
            File docsFile,
            Map<String,Integer> docIdMap,
            Map<String,Map<Integer,List<Integer>>> index) throws IOException {
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(docsFile), StandardCharsets.UTF_8))) {
            w.println("DocID FilePath Norm");
            for (Map.Entry<String,Integer> de : docIdMap.entrySet()) {
                String fp = de.getKey(); int docID = de.getValue();
                double sum2 = 0;
                for (Map<Integer,List<Integer>> postings : index.values()) {
                    List<Integer> ps = postings.get(docID);
                    if (ps != null) sum2 += ps.size() * ps.size();
                }
                w.println(docID + " " + fp + " " + Math.sqrt(sum2));
            }
        }
    }

    private static Map<String,String> extractTags(NXMLFileReader xml) {
        Map<String,String> tags = new LinkedHashMap<>();
        tags.put("PMCID", xml.getPMCID());
        tags.put("Title", xml.getTitle());
        tags.put("Abstract", xml.getAbstr());
        tags.put("Body", xml.getBody());
        tags.put("Journal", xml.getJournal());
        tags.put("Publisher", xml.getPublisher());
        tags.put("Authors", String.join(" ", xml.getAuthors()));
        tags.put("Categories", String.join(" ", xml.getCategories()));
        return tags;
    }

    private static boolean isFilteredNumeric(String tok) {
        if (!tok.matches("\\d+")) return false;
        int n = Integer.parseInt(tok);
        return n < 1000 || n > 2100;
    }

    private static void writePartialIndex(
            Map<String,Map<Integer,List<Integer>>> index,
            File outDir) throws IOException {
        outDir.mkdirs();
        for (Map.Entry<String,Map<Integer,List<Integer>>> e : index.entrySet()) {
            File f = new File(outDir, e.getKey() + ".txt");
            try (PrintWriter w = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(f), StandardCharsets.UTF_8))) {
                w.println("DocID TF Positions Pointer");
                for (Map.Entry<Integer,List<Integer>> pe : e.getValue().entrySet()) {
                    int docID = pe.getKey();
                    List<Integer> pos = pe.getValue();
                    int tf = pos.size();
                    String positions = String.join(",",
                            pos.stream().map(Object::toString).toArray(String[]::new));
                    w.println(docID + " " + tf + " " + positions + " " + docID);
                }
            }
        }
    }

    private static void mergePartials(
            File partialDir,
            Map<String,Map<Integer,List<Integer>>> finalIndex) throws IOException {
        File[] blocks = partialDir.listFiles();
        if (blocks == null) return;
        for (File block : blocks) {
            for (File termFile : Objects.requireNonNull(block.listFiles())) {
                String term = termFile.getName().replaceFirst("\\.txt$", "");
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(termFile), StandardCharsets.UTF_8))) {
                    r.readLine(); // skip header
                    String line;
                    while ((line = r.readLine()) != null) {
                        String[] parts = line.split(" ", 4);
                        int docID = Integer.parseInt(parts[0]);
                        List<Integer> posList = new ArrayList<>();
                        for (String p : parts[2].split(",")) posList.add(Integer.parseInt(p));
                        finalIndex.computeIfAbsent(term, t -> new TreeMap<>())
                                .computeIfAbsent(docID, d->new ArrayList<>())
                                .addAll(posList);
                    }
                }
            }
        }
    }

    private static void collectFiles(File d, List<String> out) {
        for (File f : Objects.requireNonNull(d.listFiles())) {
            if (f.isDirectory()) collectFiles(f, out);
            else if (f.getName().toLowerCase().endsWith(".nxml")) out.add(f.getAbsolutePath());
        }
    }

    private static Set<String> loadStopwords(String res) throws IOException {
        Set<String> s = new HashSet<>();
        try (InputStream in = hy463_phaseA.class
                .getResourceAsStream("/Stopwords/" + res);
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim().toLowerCase();
                if (!line.isEmpty()) s.add(line);
            }
        }
        return s;
    }

    private static class Tokenizer {
        private static final String DELIMS = "\t\n\r\f .:!?,;";
        public static List<String> getTokens(String s) {
            String norm = Normalizer.normalize(s, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "");
            norm = norm.replaceAll("[^\\p{L}\\p{Nd}]", " ");
            StringTokenizer st = new StringTokenizer(norm, DELIMS);
            List<String> toks = new ArrayList<>();
            while (st.hasMoreTokens()) {
                String t = st.nextToken().toLowerCase();
                if (!t.isEmpty()) toks.add(t);
            }
            return toks;
        }
    }
}
