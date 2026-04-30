package com.data.collection.platform.service;

import java.util.List;

record SqlPredicate(String predicate, List<Object> args) {}
