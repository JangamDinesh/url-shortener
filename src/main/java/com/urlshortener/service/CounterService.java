package com.urlshortener.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import org.bson.Document;

@Service
public class CounterService {

    @Autowired
    private MongoTemplate mongoTemplate;

    public long getNextSequence(String name) {
        Document counter = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(name)),
                new Update().inc("seq", 1L),
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                Document.class,
                "counters"
        );
        return counter.getLong("seq");
    }
}
