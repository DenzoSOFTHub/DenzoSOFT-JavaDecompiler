/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.message;

import java.util.HashMap;
import java.util.Map;

/**
 * Message object passed through the decompilation pipeline.
 * Acts as a shared context between processors.
 */
public class Message {
    private final Map<String, Object> headers = new HashMap<String, Object>();
    private Object body;

    @SuppressWarnings("unchecked")
    public <T> T getHeader(String name) {
        return (T) headers.get(name);
    }

    public void setHeader(String name, Object value) {
        headers.put(name, value);
    }

    public boolean containsHeader(String name) {
        return headers.containsKey(name);
    }

    public Object getBody() {
        return body;
    }

    public void setBody(Object body) {
        this.body = body;
    }
}
