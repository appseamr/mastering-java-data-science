package chapter10.search;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import chapter07.searchengine.FeatureExtractor;

@Configuration
public class Container {

    @Bean(name = "luceneRanker")
    public DefaultRanker luceneRanker() throws Exception {
        return new DefaultRanker();
    }

    @Bean(name = "xgbRanker")
    public XgbRanker xgbRanker() throws Exception {
        FeatureExtractor fe = load("project/feature-extractor.bin");
        return new XgbRanker(fe, "project/xgb_model.bin");
    }

    @Bean(name = "abRanker")
    public ABRanker abRanker(@Qualifier("luceneRanker") DefaultRanker lucene,
            @Qualifier("xgbRanker") XgbRanker xgb) {
        return new ABRanker(lucene, xgb, 0L);
    }

    @Bean
    public SearchEngineService searchEngineService(@Qualifier("abRanker") FeedbackRanker ranker) 
            throws IOException {
        File index = new File("project/lucene-rerank");
        FSDirectory directory = FSDirectory.open(index.toPath());
        DirectoryReader reader = DirectoryReader.open(directory);
        IndexSearcher searcher = new IndexSearcher(reader);
        return new SearchEngineService(searcher, ranker);
    }

    private static <E> E load(String filepath) throws IOException {
        Path path = Paths.get(filepath);
        try (InputStream is = Files.newInputStream(path)) {
            try (BufferedInputStream bis = new BufferedInputStream(is)) {
                return SerializationUtils.deserialize(bis);
            }
        }
    }
}
