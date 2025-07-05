package com.urlshortener.config;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.bson.Document;

@Component
public class CounterInitializer {

    @Autowired
    private MongoTemplate mongoTemplate;

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
            System.out.println("Counter initialized in MongoDB.");
        } else {
            System.out.println("Counter already exists in MongoDB.");
        }
    }
}
