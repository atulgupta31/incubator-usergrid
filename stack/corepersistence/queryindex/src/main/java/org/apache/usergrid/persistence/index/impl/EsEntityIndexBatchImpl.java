package org.apache.usergrid.persistence.index.impl;/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.AdminClient;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.usergrid.persistence.core.scope.ApplicationScope;
import org.apache.usergrid.persistence.core.util.ValidationUtils;
import org.apache.usergrid.persistence.index.EntityIndexBatch;
import org.apache.usergrid.persistence.index.IndexFig;
import org.apache.usergrid.persistence.index.IndexScope;
import org.apache.usergrid.persistence.index.query.CandidateResult;
import org.apache.usergrid.persistence.index.utils.IndexValidationUtils;
import org.apache.usergrid.persistence.model.entity.Entity;
import org.apache.usergrid.persistence.model.entity.Id;
import org.apache.usergrid.persistence.model.field.ArrayField;
import org.apache.usergrid.persistence.model.field.BooleanField;
import org.apache.usergrid.persistence.model.field.DoubleField;
import org.apache.usergrid.persistence.model.field.EntityObjectField;
import org.apache.usergrid.persistence.model.field.Field;
import org.apache.usergrid.persistence.model.field.FloatField;
import org.apache.usergrid.persistence.model.field.IntegerField;
import org.apache.usergrid.persistence.model.field.ListField;
import org.apache.usergrid.persistence.model.field.LocationField;
import org.apache.usergrid.persistence.model.field.LongField;
import org.apache.usergrid.persistence.model.field.SetField;
import org.apache.usergrid.persistence.model.field.StringField;
import org.apache.usergrid.persistence.model.field.UUIDField;
import org.apache.usergrid.persistence.model.field.value.EntityObject;

import com.google.common.base.Joiner;

import static org.apache.usergrid.persistence.index.impl.IndexingUtils.ANALYZED_STRING_PREFIX;
import static org.apache.usergrid.persistence.index.impl.IndexingUtils.BOOLEAN_PREFIX;
import static org.apache.usergrid.persistence.index.impl.IndexingUtils.ENTITYID_FIELDNAME;
import static org.apache.usergrid.persistence.index.impl.IndexingUtils.GEO_PREFIX;
import static org.apache.usergrid.persistence.index.impl.IndexingUtils.NUMBER_PREFIX;
import static org.apache.usergrid.persistence.index.impl.IndexingUtils.STRING_PREFIX;
import static org.apache.usergrid.persistence.index.impl.IndexingUtils.createCollectionScopeTypeName;
import static org.apache.usergrid.persistence.index.impl.IndexingUtils.createIndexDocId;
import static org.apache.usergrid.persistence.index.impl.IndexingUtils.createIndexName;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;


public class EsEntityIndexBatchImpl implements EntityIndexBatch {

    private static final Logger log = LoggerFactory.getLogger( EsEntityIndexBatchImpl.class );

    private final ApplicationScope applicationScope;

    private final Client client;

    // Keep track of what types we have already initialized to avoid cost
    // of attempting to init them again. Used in the initType() method.
    private final Set<String> knownTypes;

    private final boolean refresh;

    private final String indexName;

    private BulkRequestBuilder bulkRequest;

    private final int autoFlushSize;

    private int count;


    public EsEntityIndexBatchImpl( final ApplicationScope applicationScope, final Client client, final IndexFig config,
                                   final Set<String> knownTypes, final int autoFlushSize ) {

        this.applicationScope = applicationScope;
        this.client = client;
        this.knownTypes = knownTypes;
        this.indexName = createIndexName( config.getIndexPrefix(), applicationScope );
        this.refresh = config.isForcedRefresh();
        this.autoFlushSize = autoFlushSize;
        initBatch();
    }


    @Override
    public EntityIndexBatch index( final IndexScope indexScope, final Entity entity ) {


        IndexValidationUtils.validateIndexScope( indexScope );

        final String indexType = createCollectionScopeTypeName( indexScope );

        if ( log.isDebugEnabled() ) {
            log.debug( "Indexing entity {}:{} in scope\n   app {}\n   owner {}\n   name {}\n   type {}", new Object[] {
                    entity.getId().getType(), entity.getId().getUuid(), applicationScope.getApplication(),
                    indexScope.getOwner(), indexScope.getName(), indexType
            } );
        }

        ValidationUtils.verifyEntityWrite( entity );

        initType( indexScope, indexType );

        Map<String, Object> entityAsMap = entityToMap( entity );

        // need prefix here becuase we index UUIDs as strings
        entityAsMap.put( STRING_PREFIX + ENTITYID_FIELDNAME, entity.getId().getUuid().toString().toLowerCase() );

        // let caller add these fields if needed
        // entityAsMap.put("created", entity.getId().getUuid().timestamp();
        // entityAsMap.put("updated", entity.getVersion().timestamp());

        String indexId = createIndexDocId( entity );

        log.debug( "Indexing entity id {} data {} ", indexId, entityAsMap );

        bulkRequest.add( client.prepareIndex( indexName, indexType, indexId ).setSource( entityAsMap ) );

        maybeFlush();

        return this;
    }


    @Override
    public EntityIndexBatch deindex( final IndexScope indexScope, final Id id, final UUID version ) {

        IndexValidationUtils.validateIndexScope( indexScope );

        final String indexType = createCollectionScopeTypeName( indexScope );

        if ( log.isDebugEnabled() ) {
            log.debug( "De-indexing entity {}:{} in scope\n   app {}\n   owner {}\n   name {} type {}", new Object[] {
                    id.getType(), id.getUuid(), applicationScope.getApplication(), indexScope.getOwner(),
                    indexScope.getName(), indexType
            } );
        }

        String indexId = createIndexDocId( id, version );

        bulkRequest.add( client.prepareDelete( indexName, indexType, indexId ).setRefresh( refresh ) );


        log.debug( "Deindexed Entity with index id " + indexId );

        maybeFlush();

        return this;
    }


    @Override
    public EntityIndexBatch deindex( final IndexScope indexScope, final Entity entity ) {

        return deindex( indexScope, entity.getId(), entity.getVersion() );
    }


    @Override
    public EntityIndexBatch deindex( final IndexScope indexScope, final CandidateResult entity ) {

        return deindex( indexScope, entity.getId(), entity.getVersion() );
    }


    @Override
    public void execute() {
        execute( bulkRequest.setRefresh( refresh ) );
    }


    /**
     * Execute the request, check for errors, then re-init the batch for future use
     */
    private void execute( final BulkRequestBuilder request ) {

        //nothing to do, we haven't added anthing to the index
        if(request.numberOfActions() == 0){
            return;
        }

        final BulkResponse responses = request.execute().actionGet();

        for ( BulkItemResponse response : responses ) {
            if ( response.isFailed() ) {
                throw new RuntimeException(
                        "Unable to index documents.  Errors are :" + response.getFailure().getMessage() );
            }
        }

        initBatch();
    }


    @Override
    public void executeAndRefresh() {
        execute( bulkRequest.setRefresh( true ) );
    }


    private void maybeFlush() {
        count++;

        if ( count % autoFlushSize == 0 ) {
            execute();
            count = 0;
        }
    }


    /**
     * Create ElasticSearch mapping for each type of Entity.
     */
    private void initType( final IndexScope indexScope, String typeName ) {

        // no need for synchronization here, it's OK if we init attempt to init type multiple times
        if ( knownTypes.contains( typeName ) ) {
            return;
        }

        AdminClient admin = client.admin();
        try {
            XContentBuilder mxcb = EsEntityIndexImpl.createDoubleStringIndexMapping( jsonBuilder(), typeName );


            //TODO Dave can this be collapsed into the build as well?
            admin.indices().preparePutMapping( indexName ).setType( typeName ).setSource( mxcb ).execute().actionGet();

            admin.indices().prepareGetMappings( indexName ).addTypes( typeName ).execute().actionGet();

            //            log.debug("Created new type mapping");
            //            log.debug("   Scope application: " + indexScope.getApplication());
            //            log.debug("   Scope owner: " + indexScope.getOwner());
            //            log.debug("   Type name: " + typeName);

            knownTypes.add( typeName );
        }
        catch ( IndexAlreadyExistsException ignored ) {
            // expected
        }
        catch ( IOException ex ) {
            throw new RuntimeException(
                    "Exception initializing type " + typeName + " in app " + applicationScope.getApplication()
                                                                                             .toString() );
        }
    }


    /**
     * Convert Entity to Map. Adding prefixes for types:
     *
     * su_ - String unanalyzed field sa_ - String analyzed field go_ - Location field nu_ - Number field bu_ - Boolean
     * field
     */
    private static Map entityToMap( EntityObject entity ) {

        Map<String, Object> entityMap = new HashMap<String, Object>();

        for ( Object f : entity.getFields().toArray() ) {

            Field field = ( Field ) f;

            if ( f instanceof ListField ) {
                List list = ( List ) field.getValue();
                entityMap.put( field.getName().toLowerCase(), new ArrayList( processCollectionForMap( list ) ) );

                if ( !list.isEmpty() ) {
                    if ( list.get( 0 ) instanceof String ) {
                        Joiner joiner = Joiner.on( " " ).skipNulls();
                        String joined = joiner.join( list );
                        entityMap.put( ANALYZED_STRING_PREFIX + field.getName().toLowerCase(),
                                new ArrayList( processCollectionForMap( list ) ) );
                    }
                }
            }
            else if ( f instanceof ArrayField ) {
                List list = ( List ) field.getValue();
                entityMap.put( field.getName().toLowerCase(), new ArrayList( processCollectionForMap( list ) ) );
            }
            else if ( f instanceof SetField ) {
                Set set = ( Set ) field.getValue();
                entityMap.put( field.getName().toLowerCase(), new ArrayList( processCollectionForMap( set ) ) );
            }
            else if ( f instanceof EntityObjectField ) {
                EntityObject eo = ( EntityObject ) field.getValue();
                entityMap.put( field.getName().toLowerCase(), entityToMap( eo ) ); // recursion

                // Add type information as field-name prefixes

            }
            else if ( f instanceof StringField ) {

                // index in lower case because Usergrid queries are case insensitive
                entityMap.put( ANALYZED_STRING_PREFIX + field.getName().toLowerCase(),
                        ( ( String ) field.getValue() ).toLowerCase() );
                entityMap.put( STRING_PREFIX + field.getName().toLowerCase(),
                        ( ( String ) field.getValue() ).toLowerCase() );
            }
            else if ( f instanceof LocationField ) {
                LocationField locField = ( LocationField ) f;
                Map<String, Object> locMap = new HashMap<String, Object>();

                // field names lat and lon trigger ElasticSearch geo location
                locMap.put( "lat", locField.getValue().getLatitude() );
                locMap.put( "lon", locField.getValue().getLongtitude() );
                entityMap.put( GEO_PREFIX + field.getName().toLowerCase(), locMap );
            }
            else if ( f instanceof DoubleField || f instanceof FloatField || f instanceof IntegerField
                    || f instanceof LongField ) {

                entityMap.put( NUMBER_PREFIX + field.getName().toLowerCase(), field.getValue() );
            }
            else if ( f instanceof BooleanField ) {

                entityMap.put( BOOLEAN_PREFIX + field.getName().toLowerCase(), field.getValue() );
            }
            else if ( f instanceof UUIDField ) {

                entityMap.put( STRING_PREFIX + field.getName().toLowerCase(), field.getValue().toString() );
            }
            else {
                entityMap.put( field.getName().toLowerCase(), field.getValue() );
            }
        }

        return entityMap;
    }


    private static Collection processCollectionForMap( Collection c ) {
        if ( c.isEmpty() ) {
            return c;
        }
        List processed = new ArrayList();
        Object sample = c.iterator().next();

        if ( sample instanceof Entity ) {
            for ( Object o : c.toArray() ) {
                Entity e = ( Entity ) o;
                processed.add( entityToMap( e ) );
            }
        }
        else if ( sample instanceof List ) {
            for ( Object o : c.toArray() ) {
                List list = ( List ) o;
                processed.add( processCollectionForMap( list ) ); // recursion;
            }
        }
        else if ( sample instanceof Set ) {
            for ( Object o : c.toArray() ) {
                Set set = ( Set ) o;
                processed.add( processCollectionForMap( set ) ); // recursion;
            }
        }
        else {
            for ( Object o : c.toArray() ) {
                processed.add( o );
            }
        }
        return processed;
    }


    private void initBatch() {
        this.bulkRequest = client.prepareBulk();
    }
}