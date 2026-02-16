package com.urlshortener.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.bson.Document;

@Slf4j
@Component
@RequiredArgsConstructor
public class CounterInitializer {

    private final MongoTemplate mongoTemplate;

    @PostConstruct
    public void initCounter() {
        if (!mongoTemplate.collectionExists("counters")) {
            mongoTemplate.createCollection("counters");
        }

        Document counter = mongoTemplate.findById("url_sequence", Document.class, "counters");
        if (counter == null) {
            Document doc = new Document("_id", "url_sequence")
                    .append("seq", 0L);
            mongoTemplate.getCollection("counters").insertOne(doc);
            log.info("Counter initialized in MongoDB.");
        } else {
            log.info("Counter already exists in MongoDB.");
        }
    }
}
