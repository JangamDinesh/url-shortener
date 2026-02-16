package com.urlshortener.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import org.bson.Document;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates unique IDs using a MongoDB atomic counter with range-based allocation.
 * Instead of hitting MongoDB for every single ID, a batch of IDs (RANGE_SIZE) is
 * allocated at once and served from memory. MongoDB is only contacted when the
 * current range is exhausted, reducing contention under high write load.
 */
@Slf4j
@Service
public class CounterService {

    private final MongoTemplate mongoTemplate;

    private static final int RANGE_SIZE = 100;

    private final AtomicLong currentId = new AtomicLong(0);
    private final AtomicLong maxId = new AtomicLong(0);

    public CounterService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public synchronized long getNextSequence(String name) {
        if (currentId.get() >= maxId.get()) {
            allocateRange(name);
        }
        return currentId.getAndIncrement();
    }

    private void allocateRange(String name) {
        Document counter = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(name)),
                new Update().inc("seq", (long) RANGE_SIZE),
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                Document.class,
                "counters"
        );
        long newMax = counter.getLong("seq");
        maxId.set(newMax);
        currentId.set(newMax - RANGE_SIZE);
        log.info("Allocated ID range [{}, {}) from MongoDB", newMax - RANGE_SIZE, newMax);
    }
}
