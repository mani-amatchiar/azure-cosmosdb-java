/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.microsoft.azure.cosmosdb.rx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

import com.microsoft.azure.cosmos.*;
import com.microsoft.azure.cosmosdb.PartitionKeyDefinition;
import com.microsoft.azure.cosmosdb.RetryAnalyzer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

import com.microsoft.azure.cosmos.CosmosItemProperties;
import com.microsoft.azure.cosmosdb.CompositePath;
import com.microsoft.azure.cosmosdb.CompositePathSortOrder;
import com.microsoft.azure.cosmosdb.Database;
import com.microsoft.azure.cosmosdb.IndexingMode;
import com.microsoft.azure.cosmosdb.IndexingPolicy;
import com.microsoft.azure.cosmosdb.PartitionKey;
import com.microsoft.azure.cosmosdb.SpatialSpec;
import com.microsoft.azure.cosmosdb.SpatialType;

import reactor.core.publisher.Mono;

public class CollectionCrudTest extends TestSuiteBase {
    private static final int TIMEOUT = 50000;
    private static final int SETUP_TIMEOUT = 20000;
    private static final int SHUTDOWN_TIMEOUT = 20000;
    private final String databaseId = CosmosDatabaseForTest.generateId();

    private CosmosClient client;
    private CosmosDatabase database;

    @Factory(dataProvider = "clientBuildersWithDirect")
    public CollectionCrudTest(CosmosClientBuilder clientBuilder) {
        this.clientBuilder = clientBuilder;
        this.subscriberValidationTimeout = TIMEOUT;
    }

    @DataProvider(name = "collectionCrudArgProvider")
    public Object[][] collectionCrudArgProvider() {
        return new Object[][] {
                // collection name, is name base
                {UUID.randomUUID().toString()} ,

                // with special characters in the name.
                {"+ -_,:.|~" + UUID.randomUUID().toString() + " +-_,:.|~"} ,
        };
    }

    private CosmosContainerSettings getCollectionDefinition(String collectionName) {
        PartitionKeyDefinition partitionKeyDef = new PartitionKeyDefinition();
        ArrayList<String> paths = new ArrayList<String>();
        paths.add("/mypk");
        partitionKeyDef.paths(paths);

        CosmosContainerSettings collectionDefinition = new CosmosContainerSettings(
                collectionName,
                partitionKeyDef);

        return collectionDefinition;
    }

    @Test(groups = { "emulator" }, timeOut = TIMEOUT, dataProvider = "collectionCrudArgProvider")
    public void createCollection(String collectionName) throws InterruptedException {
        CosmosContainerSettings collectionDefinition = getCollectionDefinition(collectionName);
        
        Mono<CosmosContainerResponse> createObservable = database
                .createContainer(collectionDefinition);

        CosmosResponseValidator<CosmosContainerResponse> validator = new CosmosResponseValidator.Builder<CosmosContainerResponse>()
                .withId(collectionDefinition.id()).build();

        validateSuccess(createObservable, validator);
        safeDeleteAllCollections(database);
    }

    @Test(groups = { "emulator" }, timeOut = TIMEOUT)
    public void createCollectionWithCompositeIndexAndSpatialSpec() throws InterruptedException {
        PartitionKeyDefinition partitionKeyDef = new PartitionKeyDefinition();
        ArrayList<String> paths = new ArrayList<String>();
        paths.add("/mypk");
        partitionKeyDef.paths(paths);

        CosmosContainerSettings collection = new CosmosContainerSettings(
                UUID.randomUUID().toString(),
                partitionKeyDef);

        IndexingPolicy indexingPolicy = new IndexingPolicy();
        CompositePath compositePath1 = new CompositePath();
        compositePath1.path("/path1");
        compositePath1.order(CompositePathSortOrder.ASCENDING);
        CompositePath compositePath2 = new CompositePath();
        compositePath2.path("/path2");
        compositePath2.order(CompositePathSortOrder.DESCENDING);
        CompositePath compositePath3 = new CompositePath();
        compositePath3.path("/path3");
        CompositePath compositePath4 = new CompositePath();
        compositePath4.path("/path4");
        compositePath4.order(CompositePathSortOrder.ASCENDING);
        CompositePath compositePath5 = new CompositePath();
        compositePath5.path("/path5");
        compositePath5.order(CompositePathSortOrder.DESCENDING);
        CompositePath compositePath6 = new CompositePath();
        compositePath6.path("/path6");

        ArrayList<CompositePath> compositeIndex1 = new ArrayList<CompositePath>();
        compositeIndex1.add(compositePath1);
        compositeIndex1.add(compositePath2);
        compositeIndex1.add(compositePath3);

        ArrayList<CompositePath> compositeIndex2 = new ArrayList<CompositePath>();
        compositeIndex2.add(compositePath4);
        compositeIndex2.add(compositePath5);
        compositeIndex2.add(compositePath6);

        Collection<ArrayList<CompositePath>> compositeIndexes = new ArrayList<ArrayList<CompositePath>>();
        compositeIndexes.add(compositeIndex1);
        compositeIndexes.add(compositeIndex2);
        indexingPolicy.compositeIndexes(compositeIndexes);

        SpatialType[] spatialTypes = new SpatialType[] {
                SpatialType.POINT,
                SpatialType.LINE_STRING,
                SpatialType.POLYGON,
                SpatialType.MULTI_POLYGON
        };
        Collection<SpatialSpec> spatialIndexes = new ArrayList<SpatialSpec>();
        for (int index = 0; index < 2; index++) {
            Collection<SpatialType> collectionOfSpatialTypes = new ArrayList<SpatialType>();

            SpatialSpec spec = new SpatialSpec();
            spec.path("/path" + index + "/*");

            for (int i = index; i < index + 3; i++) {
                collectionOfSpatialTypes.add(spatialTypes[i]);
            }
            spec.spatialTypes(collectionOfSpatialTypes);
            spatialIndexes.add(spec);
        }

        indexingPolicy.spatialIndexes(spatialIndexes);

        collection.indexingPolicy(indexingPolicy);
        
        Mono<CosmosContainerResponse> createObservable = database
                .createContainer(collection, new CosmosContainerRequestOptions());

        CosmosResponseValidator<CosmosContainerResponse> validator = new CosmosResponseValidator.Builder<CosmosContainerResponse>()
                .withId(collection.id())
                .withCompositeIndexes(compositeIndexes)
                .withSpatialIndexes(spatialIndexes)
                .build();

        validateSuccess(createObservable, validator);
        safeDeleteAllCollections(database);
    }

    @Test(groups = { "emulator" }, timeOut = TIMEOUT, dataProvider = "collectionCrudArgProvider")
    public void readCollection(String collectionName) throws InterruptedException {
        CosmosContainerSettings collectionDefinition = getCollectionDefinition(collectionName);
        
        Mono<CosmosContainerResponse> createObservable = database.createContainer(collectionDefinition);
        CosmosContainer collection = createObservable.block().container();

        Mono<CosmosContainerResponse> readObservable = collection.read();

        CosmosResponseValidator<CosmosContainerResponse> validator = new CosmosResponseValidator.Builder<CosmosContainerResponse>()
                .withId(collection.id()).build();
        validateSuccess(readObservable, validator);
        safeDeleteAllCollections(database);
    }

    @Test(groups = { "emulator" }, timeOut = TIMEOUT, dataProvider = "collectionCrudArgProvider")
    public void readCollection_DoesntExist(String collectionName) throws Exception {

        Mono<CosmosContainerResponse> readObservable = database
                .getContainer("I don't exist").read();

        FailureValidator validator = new FailureValidator.Builder().resourceNotFound().build();
        validateFailure(readObservable, validator);
    }

    @Test(groups = { "emulator" }, timeOut = TIMEOUT, dataProvider = "collectionCrudArgProvider")
    public void deleteCollection(String collectionName) throws InterruptedException {
        CosmosContainerSettings collectionDefinition = getCollectionDefinition(collectionName);
        
        Mono<CosmosContainerResponse> createObservable = database.createContainer(collectionDefinition);
        CosmosContainer collection = createObservable.block().container();

        Mono<CosmosContainerResponse> deleteObservable = collection.delete();

        CosmosResponseValidator<CosmosContainerResponse> validator = new CosmosResponseValidator.Builder<CosmosContainerResponse>()
                .nullResource().build();
        validateSuccess(deleteObservable, validator);
    }

    @Test(groups = { "emulator" }, timeOut = TIMEOUT, dataProvider = "collectionCrudArgProvider")
    public void replaceCollection(String collectionName) throws InterruptedException  {
        // create a collection
        CosmosContainerSettings collectionDefinition = getCollectionDefinition(collectionName);
        Mono<CosmosContainerResponse> createObservable = database.createContainer(collectionDefinition);
        CosmosContainer collection = createObservable.block().container();
        CosmosContainerSettings collectionSettings = collection.read().block().settings();
        // sanity check
        assertThat(collectionSettings.indexingPolicy().indexingMode()).isEqualTo(IndexingMode.CONSISTENT);
        
        // replace indexing mode
        IndexingPolicy indexingMode = new IndexingPolicy();
        indexingMode.indexingMode(IndexingMode.LAZY);
        collectionSettings.indexingPolicy(indexingMode);
        Mono<CosmosContainerResponse> readObservable = collection.replace(collectionSettings, new CosmosContainerRequestOptions());
        
        // validate
        CosmosResponseValidator<CosmosContainerResponse> validator = new CosmosResponseValidator.Builder<CosmosContainerResponse>()
                        .indexingMode(IndexingMode.LAZY).build();
        validateSuccess(readObservable, validator);
        safeDeleteAllCollections(database);
    }

    @Test(groups = { "emulator" }, timeOut = 10 * TIMEOUT, retryAnalyzer = RetryAnalyzer.class)
    public void sessionTokenConsistencyCollectionDeleteCreateSameName() {
        CosmosClient client1 = clientBuilder.build();
        CosmosClient client2 = clientBuilder.build();

        String dbId = CosmosDatabaseForTest.generateId();
        String collectionId = "coll";
        CosmosDatabase db = null;
        try {
            Database databaseDefinition = new Database();
            databaseDefinition.id(dbId);
            db = createDatabase(client1, dbId);

            PartitionKeyDefinition partitionKeyDef = new PartitionKeyDefinition();
            ArrayList<String> paths = new ArrayList<String>();
            paths.add("/mypk");
            partitionKeyDef.paths(paths);

            CosmosContainerSettings collectionDefinition = new CosmosContainerSettings(collectionId, partitionKeyDef);
            CosmosContainer collection = createCollection(db, collectionDefinition, new CosmosContainerRequestOptions());

            CosmosItemProperties document = new CosmosItemProperties();
            document.id("doc");
            document.set("name", "New Document");
            document.set("mypk", "mypkValue");
            CosmosItem item = createDocument(collection, document);
            CosmosItemRequestOptions options = new CosmosItemRequestOptions();
            options.partitionKey(new PartitionKey("mypkValue"));
            CosmosItemResponse readDocumentResponse = item.read(options).block();
            logger.info("Client 1 READ Document Client Side Request Statistics {}", readDocumentResponse.requestDiagnosticsString());
            logger.info("Client 1 READ Document Latency {}", readDocumentResponse.requestLatency());

            document.set("name", "New Updated Document");
            CosmosItemResponse upsertDocumentResponse = collection.upsertItem(document).block();
            logger.info("Client 1 Upsert Document Client Side Request Statistics {}", upsertDocumentResponse.requestDiagnosticsString());
            logger.info("Client 1 Upsert Document Latency {}", upsertDocumentResponse.requestLatency());

            //  DELETE the existing collection
            deleteCollection(client2, dbId, collectionId);
            //  Recreate the collection with the same name but with different client
            CosmosContainer collection2 = createCollection(client2, dbId, collectionDefinition);

            CosmosItemProperties newDocument = new CosmosItemProperties();
            newDocument.id("doc");
            newDocument.set("name", "New Created Document");
            newDocument.set("mypk", "mypk");
            createDocument(collection2, newDocument);

            readDocumentResponse = client1.getDatabase(dbId).getContainer(collectionId).getItem(newDocument.id(), newDocument.get("mypk")).read().block();
            logger.info("Client 2 READ Document Client Side Request Statistics {}", readDocumentResponse.requestDiagnosticsString());
            logger.info("Client 2 READ Document Latency {}", readDocumentResponse.requestLatency());

            CosmosItemProperties readDocument = readDocumentResponse.properties();

            assertThat(readDocument.id().equals(newDocument.id())).isTrue();
            assertThat(readDocument.get("name").equals(newDocument.get("name"))).isTrue();
        } finally {
            safeDeleteDatabase(db);
            safeClose(client1);
            safeClose(client2);
        }
    }

    @BeforeClass(groups = { "emulator" }, timeOut = SETUP_TIMEOUT)
    public void beforeClass() {
        client = clientBuilder.build();
        database = createDatabase(client, databaseId);
    }

    @AfterClass(groups = { "emulator" }, timeOut = SHUTDOWN_TIMEOUT, alwaysRun = true)
    public void afterClass() {
        safeDeleteDatabase(database);
        safeClose(client);
    }
}
