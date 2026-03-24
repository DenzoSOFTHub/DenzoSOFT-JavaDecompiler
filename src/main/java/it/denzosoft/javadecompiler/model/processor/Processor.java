/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.processor;

import it.denzosoft.javadecompiler.model.message.Message;

/**
 * Interface for pipeline processors. Each stage of the decompilation
 * pipeline implements this interface.
 */
public interface Processor {
    void process(Message message) throws Exception;
}
