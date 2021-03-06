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
package org.lilyproject.indexer.model.util;

import java.io.ByteArrayInputStream;

import com.ngdata.hbaseindexer.conf.IndexerConf;
import com.ngdata.hbaseindexer.model.api.IndexerDefinition;
import org.lilyproject.indexer.model.api.LResultToSolrMapper;
import org.lilyproject.indexer.model.indexerconf.IndexerConfException;
import org.lilyproject.indexer.model.indexerconf.LilyIndexerConf;
import org.lilyproject.indexer.model.indexerconf.LilyIndexerConfBuilder;
import org.lilyproject.repository.api.LRepository;
import org.lilyproject.repository.api.RepositoryException;
import org.lilyproject.repository.api.RepositoryManager;
import org.lilyproject.util.hbase.RepoAndTableUtil;

public class IndexInfo {
    IndexerDefinition indexDefinition;
    IndexerConf indexerConf;
    LilyIndexerConf lilyIndexerConf;
    String repositoryName;

    public IndexInfo(IndexerDefinition indexDefinition, IndexerConf indexerConf, RepositoryManager repositoryManager)
            throws IndexerConfException, RepositoryException, InterruptedException {
        this.indexDefinition = indexDefinition;
        this.indexerConf = indexerConf;

        String repoParam = indexDefinition.getConnectionParams().get(LResultToSolrMapper.REPO_KEY);
        LRepository repository = repositoryName == null ? repositoryManager.getDefaultRepository() : repositoryManager.getRepository(repositoryName);
        repositoryName = repoParam == null ? RepoAndTableUtil.DEFAULT_REPOSITORY : repoParam;

        this.lilyIndexerConf = LilyIndexerConfBuilder.build(new ByteArrayInputStream(indexDefinition.getConfiguration()), repository);
    }

    public IndexerDefinition getIndexDefinition() {
        return indexDefinition;
    }

    public IndexerConf getIndexerConf() {
        return indexerConf;
    }

    public LilyIndexerConf getLilyIndexerConf() {
        return lilyIndexerConf;
    }

    public String getRepositoryName() {
        return repositoryName;
    }
}
