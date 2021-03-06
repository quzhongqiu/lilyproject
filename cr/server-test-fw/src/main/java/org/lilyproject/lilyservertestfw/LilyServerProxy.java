/*
 * Copyright 2010 Outerthought bvba
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
package org.lilyproject.lilyservertestfw;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;

import com.google.common.collect.Maps;
import com.ngdata.hbaseindexer.ConfKeys;
import com.ngdata.hbaseindexer.HBaseIndexerConfiguration;
import com.ngdata.hbaseindexer.SolrConnectionParams;
import com.ngdata.hbaseindexer.model.api.IndexerDefinition;
import com.ngdata.hbaseindexer.model.api.IndexerDefinitionBuilder;
import com.ngdata.hbaseindexer.model.api.WriteableIndexerModel;
import com.ngdata.hbaseindexer.model.impl.IndexerModelImpl;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.zookeeper.KeeperException;
import org.lilyproject.client.LilyClient;
import org.lilyproject.client.NoServersException;
import org.lilyproject.hadooptestfw.HBaseProxy;
import org.lilyproject.indexer.hbase.mapper.LilyIndexerComponentFactory;
import org.lilyproject.indexer.model.api.LResultToSolrMapper;
import org.lilyproject.repository.api.RepositoryException;
import org.lilyproject.repository.model.api.RepositoryDefinition;
import org.lilyproject.repository.model.api.RepositoryModel;
import org.lilyproject.repository.model.impl.RepositoryModelImpl;
import org.lilyproject.runtime.module.javaservice.JavaServiceManager;
import org.lilyproject.sep.ZooKeeperItfAdapter;
import org.lilyproject.solrtestfw.SolrProxy;
import org.lilyproject.util.io.Closer;
import org.lilyproject.util.test.TestHomeUtil;
import org.lilyproject.util.zookeeper.ZkConnectException;
import org.lilyproject.util.zookeeper.ZkUtil;
import org.lilyproject.util.zookeeper.ZooKeeperItf;

public class LilyServerProxy {
    public static final String LILY_CONF_CUSTOMDIR = "lily.conf.customdir";

    private final Log log = LogFactory.getLog(getClass());

    private Mode mode;
    private HBaseProxy hbaseProxy;

    public enum Mode {EMBED, CONNECT}

    private static String LILY_MODE_PROP_NAME = "lily.lilyserverproxy.mode";

    private LilyServerTestUtility lilyServerTestUtility;

    private File testHome;

    private LilyClient lilyClient;
    private WriteableIndexerModel indexerModel;
    private ZooKeeperItf zooKeeper;
    private final boolean clearData;

    public LilyServerProxy(HBaseProxy hbaseProxy) throws IOException {
        this(null, hbaseProxy);
    }

    public LilyServerProxy(Mode mode, HBaseProxy hbaseProxy) throws IOException {
        this(mode, true, hbaseProxy);
    }

    /**
     * LilyServerProxy starts a proxy for lily which either :
     * - connects to an already running lily (using launch-test-lily) (CONNECT mode)
     * - starts a new LilyServerTestUtility (EMBED mode)
     *
     * <p>In the EMBED mode the default configuration files used are the files present
     * in the resource "org/lilyproject/lilyservertestfw/conf/".
     * These files are copied into the resource at build-time from "cr/process/server/conf".
     * <br>On top of these default configuration files, custom configuration files can be used.
     * The path to these custom configuration files should be put in the system property
     * "lily.conf.customdir".
     *
     * @param mode the mode (CONNECT or EMBED) in which to start the proxy.
     */
    public LilyServerProxy(Mode mode, boolean clearData, HBaseProxy hbaseProxy) throws IOException {
        this.clearData = clearData;
        this.hbaseProxy = hbaseProxy;

        if (mode == null) {
            String lilyModeProp = System.getProperty(LILY_MODE_PROP_NAME);
            if (lilyModeProp == null || lilyModeProp.equals("") || lilyModeProp.equals("embed")) {
                this.mode = Mode.EMBED;
            } else if (lilyModeProp.equals("connect")) {
                this.mode = Mode.CONNECT;
            } else {
                throw new RuntimeException("Unexpected value for " + LILY_MODE_PROP_NAME + ": " + lilyModeProp);
            }
        } else {
            this.mode = mode;
        }
    }

    public void setTestHome(File testHome) throws IOException {
        if (mode != Mode.EMBED) {
            throw new RuntimeException("testHome should only be set when mode is EMBED");
        }
        this.testHome = testHome;
    }

    private void initTestHome() throws IOException {
        if (testHome == null) {
            testHome = TestHomeUtil.createTestHome("lily-serverproxy-");
        }

        FileUtils.forceMkdir(testHome);
    }

    public void start() throws Exception {
        System.out.println("LilyServerProxy mode: " + mode);

        switch (mode) {
            case EMBED:
                initTestHome();
                System.out.println("LilySeverProxy embedded mode temp dir: " + testHome.getAbsolutePath());
                // Setup default conf dir : extract conf from resources into testHome
                File defaultConfDir = new File(testHome, "conf");
                FileUtils.forceMkdir(defaultConfDir);
                extractTemplateConf(defaultConfDir);
                // Get custom conf dir
                String customConfDir = System.getProperty(LILY_CONF_CUSTOMDIR);

                lilyServerTestUtility = new LilyServerTestUtility(defaultConfDir.getAbsolutePath(), customConfDir,
                        testHome);
                lilyServerTestUtility.start();
                break;
            case CONNECT:
                break;
            default:
                throw new RuntimeException("Unexpected mode: " + mode);
        }
    }

    public void stop() {
        if (mode == Mode.CONNECT) {
            Closer.close(zooKeeper);
            Closer.close(indexerModel);
        }

        Closer.close(lilyClient);

        this.zooKeeper = null;
        this.indexerModel = null;
        this.lilyClient = null;

        // We close the server after the client, to avoid client threads possibly hanging
        // in retry loops when no servers are available
        Closer.close(lilyServerTestUtility);
    }

    public synchronized LilyClient getClient() throws IOException, InterruptedException, KeeperException,
            ZkConnectException, NoServersException, RepositoryException {
        if (lilyClient == null) {
            lilyClient = new LilyClient("localhost:2181", 30000);
        }
        return lilyClient;
    }

    /**
     * Get ZooKeeper.
     *
     * <p></p>Be careful what you do with this ZooKeeper instance, as it shared with other users: mind the single
     * event dispatching thread, don't use the global watchers. If these are a problem for you, you can as well
     * create your own ZooKeeper client.
     */
    public synchronized ZooKeeperItf getZooKeeper() throws ZkConnectException {
        if (zooKeeper == null) {
            switch (mode) {
                case EMBED:
                    JavaServiceManager serviceMgr = lilyServerTestUtility.getRuntime().getJavaServiceManager();
                    zooKeeper = (ZooKeeperItf) serviceMgr.getService(ZooKeeperItf.class);
                    break;
                case CONNECT:
                    zooKeeper = ZkUtil.connect("localhost:2181", 30000);
                    break;
                default:
                    throw new RuntimeException("Unexpected mode: " + mode);
            }
        }
        return zooKeeper;
    }

    public synchronized WriteableIndexerModel getIndexerModel() throws ZkConnectException, InterruptedException, KeeperException {
        if (indexerModel == null) {
            switch (mode) {
                case EMBED:
                    JavaServiceManager serviceMgr = lilyServerTestUtility.getRuntime().getJavaServiceManager();
                    indexerModel = (WriteableIndexerModel) serviceMgr.getService(WriteableIndexerModel.class);
                    break;
                case CONNECT:
                    Configuration conf = HBaseIndexerConfiguration.create();
                    indexerModel = new IndexerModelImpl(new ZooKeeperItfAdapter(getZooKeeper()), conf.get(ConfKeys.ZK_ROOT_NODE));
                    break;
                default:
                    throw new RuntimeException("Unexpected mode: " + mode);
            }
        }
        return indexerModel;
    }

    private void extractTemplateConf(File confDir) throws URISyntaxException, IOException {
        URL confUrl = getClass().getClassLoader().getResource(ConfUtil.CONF_RESOURCE_PATH);
        ConfUtil.copyConfResources(confUrl, ConfUtil.CONF_RESOURCE_PATH, confDir);
    }

    /**
     * Adds an index from index configuration contained in a resource, using settings that are common in development.
     * wait for indexer model, sep and indexer registry.
     *
     * @see LilyServerProxy#addIndex(String, String, String, byte[], long, boolean, boolean, boolean)
     */
    public void addIndexFromResource(String repositoryName, String indexName, String collectionName, String indexerConf, long timeout,
                                     boolean waitForIndexerModel, boolean waitForSep, boolean waitForIndexerRegistry)
            throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream(indexerConf);
        byte[] indexerConfiguration = IOUtils.toByteArray(is);
        is.close();
        addIndex(repositoryName, indexName, collectionName, indexerConfiguration, timeout, waitForIndexerModel, waitForSep,
                waitForIndexerRegistry);
    }

    /**
     * @see LilyServerProxy#addIndex(IndexerDefinition, long, boolean, boolean, boolean)
     * Adds an index from index configuration contained in a byte arrayj, using settings that are common in development.
     */
    public void addIndexFromResource(String repositoryName, String indexName, String collectionName, String indexerConf, long timeout)
            throws Exception {
        addIndexFromResource(repositoryName, indexName, collectionName, indexerConf, timeout, true, true, true);
    }

    /**
     * Adds an index from index configuration contained in a byte array.
     *
     * <p>This method waits for the index subscription to be known by the SEP (or until a given timeout
     * has passed), this assures that all repository operations from then on will be processed by the
     * SEP listeners.
     *
     * <p>Note that when the SEP events are processed, the data has been put in solr but this
     * data might not be visible until the solr index has been committed. See {@link SolrProxy#commit()}.
     * data might not be visible until the solr index has been committed. See {@link SolrProxy#commit()}.
     *
     * @param indexName              name of the index
     * @param collectionName         name of the Solr collection to which to index (when null, indexes to default collection)
     * @param indexerConfiguration   byte array containing the index configuration
     * @param timeout                maximum time to spent waiting, an exception is thrown when the required conditions
     *                               have not been reached within this timeout
     * @param waitForIndexerModel    boolean indicating the call has to wait until the indexerModel knows the
     *                               subscriptionId of the new index
     * @param waitForSep             boolean indicating the call has to wait until the SEP for the new index started.
     *                               This can only be true if the waitForIndexerModel is true as well.
     * @param waitForIndexerRegistry boolean indicating the call has to wait until the IndexerRegistry knows about
     *                               the index, this is important for synchronous indexing.
     */
    public void addIndex(String repositoryName, String indexName, String collectionName, byte[] indexerConfiguration, long timeout,
                         boolean waitForIndexerModel, boolean waitForSep, boolean waitForIndexerRegistry) throws Exception {

        Map<String,String> connectionParams = Maps.newHashMap();
        connectionParams.put(SolrConnectionParams.ZOOKEEPER, "localhost:2181/solr");
        connectionParams.put(SolrConnectionParams.COLLECTION, collectionName);
        connectionParams.put(LResultToSolrMapper.ZOOKEEPER_KEY, "localhost:2181");
        connectionParams.put(LResultToSolrMapper.REPO_KEY, repositoryName);

        IndexerDefinition index = new IndexerDefinitionBuilder()
                .name(indexName)
                .connectionType("solr")
                .connectionParams(connectionParams)
                .indexerComponentFactory(LilyIndexerComponentFactory.class.getName())
                .configuration(indexerConfiguration)
                .build();

        addIndex(index, timeout, waitForIndexerModel, waitForSep, waitForIndexerRegistry);
    }


    /**
     * Adds an index from index configuration contained in a byte array.
     *
     * <p>This method waits for the index subscription to be known by the SEP (or until a given timeout
     * has passed), this assures that all repository operations from then on will be processed by the
     * SEP listeners.
     *
     * <p>Note that when the SEP events are processed, the data has been put in solr but this
     * data might not be visible until the solr index has been committed. See {@link SolrProxy#commit()}.
     * data might not be visible until the solr index has been committed. See {@link SolrProxy#commit()}.
     *
     * @param indexerDefinition      Definition of the indexer
     * @param timeout                maximum time to spent waiting, an exception is thrown when the required conditions
     *                               have not been reached within this timeout
     * @param waitForIndexerModel    boolean indicating the call has to wait until the indexerModel knows the
     *                               subscriptionId of the new index
     * @param waitForSep             boolean indicating the call has to wait until the SEP for the new index started.
     *                               This can only be true if the waitForIndexerModel is true as well.
     * @param waitForIndexerRegistry boolean indicating the call has to wait until the IndexerRegistry knows about
     *                               the index, this is important for synchronous indexing.
     */
    public void addIndex(IndexerDefinition indexerDefinition, long timeout,
                         boolean waitForIndexerModel, boolean waitForSep, boolean waitForIndexerRegistry) throws Exception {
        long tryUntil = System.currentTimeMillis() + timeout;
        WriteableIndexerModel indexerModel = getIndexerModel();
        indexerModel.addIndexer(indexerDefinition);

        if (waitForIndexerModel) {
            // Wait for subscriptionId to be known by indexerModel
            String subscriptionId = waitOnIndexSubscriptionId(indexerDefinition.getName(), tryUntil, timeout);
            if (subscriptionId == null) {
                throw new Exception("Timed out waiting for index subscription ID to be assigned.");
            }

            if (waitForSep) {
                hbaseProxy.waitOnReplicationPeerReady(subscriptionId);
            }
        }

        if (waitForIndexerRegistry) {
            waitOnIndexerRegistry(indexerDefinition.getName(), tryUntil);
        }
    }
    public String waitOnIndexSubscriptionId(String indexName, long timeout) throws Exception {
        return waitOnIndexSubscriptionId(indexName, System.currentTimeMillis() + timeout, timeout);
    }

    private String waitOnIndexSubscriptionId(String indexName, long tryUntil, long timeout) throws Exception {
        WriteableIndexerModel indexerModel = getIndexerModel();
        String subscriptionId = null;

        // Wait for index to be known by indexerModel
        while (!indexerModel.hasIndexer(indexName) && System.currentTimeMillis() < tryUntil) {
            Thread.sleep(10);
        }
        if (!indexerModel.hasIndexer(indexName)) {
            log.info("Index '" + indexName + "' not known to indexerModel within " + timeout + "ms");
            return subscriptionId;
        }

        IndexerDefinition indexDefinition = indexerModel.getIndexer(indexName);
        subscriptionId = indexDefinition.getSubscriptionId();
        while (subscriptionId == null && System.currentTimeMillis() < tryUntil) {
            Thread.sleep(10);
            subscriptionId = indexerModel.getIndexer(indexName).getSubscriptionId();
        }
        if (subscriptionId == null) {
            log.info("SubscriptionId for index '" + indexName + "' not known to indexerModel within " + timeout + "ms");
        }
        return subscriptionId;
    }

    public void waitOnIndexerRegistry(String indexName, long tryUntil) throws Exception {
        this.hbaseProxy.waitOnReplicationPeerReady("Indexer_" + indexName);
    }

    /**
     * Calls {@link #batchBuildIndex(String, String[], long) batchBuildIndex(indexName, null, timeOut)}.
     */
    public void batchBuildIndex(String indexName, long timeOut) throws Exception {
        batchBuildIndex(indexName, null, timeOut);
    }

    /**
     * Performs a batch index build of an index, waits for it to finish. If it does not finish within the
     * specified timeout, false is returned. If the index build was not successful, an exception is thrown.
     *
     * @param batchCliArgs  the batch index arguments for this particular invocation of the batch build
     * @param timeOut   maximum time to wait for the batch index to finish. You can put this rather high: the method
     *                  will return as soon as the batch indexing is finished, it is only in case something goes
     *                  wrong that this timeout will prevent endless hanging. An exception is thrown in case
     *                  the batch indexing did not finish within this timeout.
     */
    public void batchBuildIndex(String indexName, String[] batchCliArgs, long timeOut) throws Exception {
        WriteableIndexerModel model = getIndexerModel();

        try {
            String lock = model.lockIndexer(indexName);
            try {
                IndexerDefinition index = model.getIndexer(indexName);
                IndexerDefinitionBuilder builder = new IndexerDefinitionBuilder()
                        .startFrom(index)
                        .batchIndexingState(IndexerDefinition.BatchIndexingState.BUILD_REQUESTED)
                        .batchIndexCliArguments(batchCliArgs);
                model.updateIndexer(builder.build(), lock);
            } finally {
                model.unlockIndexer(lock);
            }
        } catch (Exception e) {
            throw new Exception("Error launching batch index build.", e);
        }

        try {
            // Now wait until its finished
            long tryUntil = System.currentTimeMillis() + timeOut;
            while (System.currentTimeMillis() < tryUntil) {
                Thread.sleep(100);
                IndexerDefinition definition = model.getIndexer(indexName);

                if (definition.getBatchIndexingState() == IndexerDefinition.BatchIndexingState.INACTIVE) {
                    Long amountFailed = null;
                    //Long amountFailed = definition.getLastBatchBuildInfo().getCounters().get(COUNTER_NUM_FAILED_RECORDS);
                    boolean successFlag = definition.getLastBatchBuildInfo().isFinishedSuccessful();
                    if (successFlag && (amountFailed == null || amountFailed == 0L)) {
                        return;
                    } else {
                        System.out.println(definition.getLastBatchBuildInfo().getMapReduceJobTrackingUrls());
                        throw new Exception("Batch index build did not finish successfully: success flag = " +
                                successFlag + ", amount failed records = " + amountFailed);
                    }
                }
            }
        } catch (Exception e) {

            throw new Exception("Error checking if batch index job ended.", e);
        }

        throw new Exception("Timed out waiting for batch index build to finish.");
    }

    public LilyServerTestUtility getLilyServerTestingUtility() {
        return lilyServerTestUtility;
    }

    public void createRepository(String repositoryName) {
        try {
            RepositoryModel model = new RepositoryModelImpl(getZooKeeper());
            if (!model.repositoryExistsAndActive(repositoryName)) {
                model.create(repositoryName);
                model.waitUntilRepositoryInState(repositoryName, RepositoryDefinition.RepositoryLifecycleState.ACTIVE,
                        100000);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
