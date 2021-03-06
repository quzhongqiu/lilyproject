/*
 * Copyright 2012 NGDATA nv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.tools.import_.cli;

/**
 * Wrapper of ImportListener which makes all calls synchronized.
 */
public class SynchronizedImportListener implements ImportListener {
    private ImportListener delegate;

    public SynchronizedImportListener(ImportListener listener) {
        this.delegate = listener;
    }

    @Override
    public synchronized void exception(Throwable throwable) {
        delegate.exception(throwable);
    }

    @Override
    public synchronized void recordImportException(Throwable throwable, String json, int lineNumber) {
        delegate.recordImportException(throwable, json, lineNumber);
    }

    @Override
    public synchronized void tooManyRecordImportErrors(long count) {
        delegate.tooManyRecordImportErrors(count);
    }

    @Override
    public synchronized void conflict(EntityType entityType, String entityName, String propName, Object oldValue,
            Object newValue) throws ImportConflictException {
        delegate.conflict(entityType, entityName, propName, oldValue, newValue);
    }

    @Override
    public synchronized void existsAndEqual(EntityType entityType, String entityName, String entityId) {
        delegate.existsAndEqual(entityType, entityName, entityId);
    }

    @Override
    public synchronized void updated(EntityType entityType, String entityName, String entityId, Long version) {
        delegate.updated(entityType, entityName, entityId, version);
    }

    @Override
    public synchronized void created(EntityType entityType, String entityName, String entityId) {
        delegate.created(entityType, entityName, entityId);
    }

    @Override
    public synchronized void allowedFailure(EntityType entityType, String entityName, String entityId, String reason) {
        delegate.allowedFailure(entityType, entityName, entityId, reason);
    }
}
