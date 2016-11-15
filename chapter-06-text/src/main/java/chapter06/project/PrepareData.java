package chapter06.project;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;

import chapter06.UrlRepository;
import chapter06.cv.CV;
import chapter06.cv.IndexSplit;
import chapter06.html.JsoupTextExtractor;
import joinery.DataFrame;

public class PrepareData {

    public static void main(String[] args) throws Exception {
        ArrayListMultimap<String, String> queries = readRankingData();
        Map<String, HtmlDocument> docs = readAllDocuments(queries);
        List<PositiveNegativeQueries> queryPairs = prepareTrainTestPairs(queries);
        List<LabeledQueryDocumentPair> labeledData = preparedLabeledData(queries, queryPairs, docs);

        List<LabeledQueryDocumentPair> train = labeledData.stream()
                .filter(p -> p.isTrain())
                .collect(Collectors.toList());
        List<LabeledQueryDocumentPair> test = labeledData.stream()
                .filter(p -> !p.isTrain())
                .collect(Collectors.toList());

        FeatureExtractor featureExtractor = new FeatureExtractor().fit(train);
        DataFrame<Number> trainFeatures = featureExtractor.transform(train);
        DataFrame<Number> testFeatures = featureExtractor.transform(test);

        save("data/project-train-features.bin", trainFeatures);
        save("data/project-test-features.bin", testFeatures);

    }

    private static void save(String filepath, DataFrame<Number> df) throws IOException {
        Path path = Paths.get(filepath);
        try (OutputStream os = Files.newOutputStream(path)) {
            try (BufferedOutputStream bos = new BufferedOutputStream(os)) {
                DfHolder<Number> holder = new DfHolder<>(df);
                SerializationUtils.serialize(holder, bos);
            }
        }
    }

    private static List<LabeledQueryDocumentPair> preparedLabeledData(
            ArrayListMultimap<String, String> queries, List<PositiveNegativeQueries> queryPairs,
            Map<String, HtmlDocument> docs) {
        List<LabeledQueryDocumentPair> labeledData = new ArrayList<>();

        for (PositiveNegativeQueries pair : queryPairs) {
            String query = pair.getQuery();

            List<String> positive = queries.get(query);
            for (String url : positive) {
                if (!docs.containsKey(url)) {
                    continue;
                }
                HtmlDocument doc = docs.get(url);
                labeledData.add(new LabeledQueryDocumentPair(query, doc, 1, pair.isTrain()));
            }

            for (String negQuery : pair.getNegativeQueries()) {
                List<String> negative = queries.get(negQuery);
                for (String url : negative) {
                    if (!docs.containsKey(url)) {
                        continue;
                    }
                    HtmlDocument doc = docs.get(url);
                    labeledData.add(new LabeledQueryDocumentPair(query, doc, 0, pair.isTrain()));
                }
            }
        }
        return labeledData;
    }

    private static Map<String, HtmlDocument> readAllDocuments(ArrayListMultimap<String, String> queries)
            throws Exception {
        File cache = new File("data/html-cache.bin");
        if (cache.exists()) {
            try (InputStream is = Files.newInputStream(cache.toPath())) {
                try (BufferedInputStream bis = new BufferedInputStream(is)) {
                    return SerializationUtils.deserialize(is);
                }
            }
        }

        Map<String, HtmlDocument> docs = new ConcurrentHashMap<>();

        try (UrlRepository urls = new UrlRepository()) {
            queries.values().parallelStream().forEach(url -> {
                System.out.println("processing " + url + "...");

                Optional<HtmlDocument> doc = extractText(urls, url);
                if (doc.isPresent()) {
                    docs.put(url, doc.get());
                }
            });
        }

        Map<String, HtmlDocument> docsCopy = ImmutableMap.copyOf(docs);

        try (OutputStream os = Files.newOutputStream(cache.toPath())) {
            try (BufferedOutputStream bos = new BufferedOutputStream(os)) {
                SerializationUtils.serialize((Serializable) docsCopy, bos);
            }
        }

        return docsCopy;
    }

    private static Optional<HtmlDocument> extractText(UrlRepository urls, String url) {
        Optional<String> html = urls.get(url);
        if (!html.isPresent()) {
            return Optional.empty();
        }

        Document document = Jsoup.parse(html.get());
        String title = document.title();
        if (title == null) {
            return Optional.empty();
        }

        Element body = document.body();
        if (body == null) {
            return Optional.empty();
        }

        JsoupTextExtractor textExtractor = new JsoupTextExtractor();
        body.traverse(textExtractor);
        String bodyText = textExtractor.getText();

        Elements headerElements = body.select("h1, h2, h3, h4, h5, h6");
        ArrayListMultimap<String, String> headers = ArrayListMultimap.create();
        for (Element htag : headerElements) {
            String tagName = htag.nodeName().toLowerCase();
            headers.put(tagName, htag.text());
        }

        return Optional.of(new HtmlDocument(url, title, headers, bodyText));
    }

    private static ArrayListMultimap<String, String> readRankingData() throws IOException {
        Path path = Paths.get("data/bing-search-results.txt");
        List<String> lines = FileUtils.readLines(path.toFile(), StandardCharsets.UTF_8);

        ArrayListMultimap<String, String> queries = ArrayListMultimap.create();

        for (String stringLine : lines) {
            String[] split = stringLine.split("\t");
            String query = split[0];
            String url = split[3];
            queries.put(query, url);
        }

        return queries;
    }

    private static List<PositiveNegativeQueries> prepareTrainTestPairs(Multimap<String, String> queries) {
        List<String> allQueries = new ArrayList<>(queries.keySet());
        IndexSplit split = CV.trainTestSplit(allQueries.size(), 0.5, true, 1);

        List<PositiveNegativeQueries> queryPairs = new ArrayList<>();
        Random rnd = new Random(2);
        int size = 9;

        List<String> trainQueries = IndexSplit.elementsByIndex(allQueries, split.getTrainIdx());
        for (String query : trainQueries) {
            List<String> negativeSamples = negativeSampling(query, trainQueries, size, rnd);
            queryPairs.add(new PositiveNegativeQueries(query, negativeSamples, true));
        }

        List<String> testQueries = IndexSplit.elementsByIndex(allQueries, split.getTestIdx());
        for (String query : testQueries) {
            List<String> negativeSamples = negativeSampling(query, testQueries, size, rnd);
            queryPairs.add(new PositiveNegativeQueries(query, negativeSamples, false));
        }

        return queryPairs;
    }

    private static List<String> negativeSampling(String positive, List<String> allData, int negativeSampleSize,
            Random rnd) {
        Set<String> others = allButOne(allData, positive);
        return sample(others, negativeSampleSize, rnd);
    }

    private static <E> Set<E> allButOne(Collection<E> collection, E el) {
        Set<E> others = new HashSet<>(collection);
        others.remove(el);
        return others;
    }

    private static List<String> sample(Collection<String> others, int size, Random rnd) {
        List<String> othersList = new ArrayList<>(others);
        Collections.shuffle(othersList, rnd);
        return othersList.subList(0, size);
    }

    public static class HtmlDocument implements Serializable {
        private final String url;
        private final String title;
        private final ArrayListMultimap<String, String> headers;
        private final String bodyText;

        public HtmlDocument(String url, String title, ArrayListMultimap<String, String> headers, String bodyText) {
            this.url = url;
            this.title = title;
            this.headers = headers;
            this.bodyText = bodyText;
        }

        public String getUrl() {
            return url;
        }

        public String getTitle() {
            return title;
        }

        public ArrayListMultimap<String, String> getHeaders() {
            return headers;
        }

        public String getBodyText() {
            return bodyText;
        }
    }

    public static class LabeledQueryDocumentPair {
        private final String query;
        private final HtmlDocument document;
        private final int relevance;
        private final boolean train;

        public LabeledQueryDocumentPair(String query, HtmlDocument document, int relevance, boolean train) {
            this.query = query;
            this.document = document;
            this.relevance = relevance;
            this.train = train;
        }

        public String getQuery() {
            return query;
        }

        public HtmlDocument getDocument() {
            return document;
        }

        public int getRelevance() {
            return relevance;
        }

        public boolean isTrain() {
            return train;
        }
    }

    private static class PositiveNegativeQueries {
        private final String query;
        private final List<String> negativeQueries;
        private final boolean train;

        public PositiveNegativeQueries(String query, List<String> negativeQueries, boolean train) {
            this.query = query;
            this.negativeQueries = negativeQueries;
            this.train = train;
        }

        public List<String> getNegativeQueries() {
            return negativeQueries;
        }

        public String getQuery() {
            return query;
        }

        public boolean isTrain() {
            return train;
        }

        @Override
        public String toString() {
            return "pos: " + query + ", neg: " + String.join(", ", negativeQueries);
        }
    }

}
