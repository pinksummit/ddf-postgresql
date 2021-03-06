package com.cohesive.ddf.provider.pg;

import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.cohesive.ddf.provider.common.ProviderMetacard;
import com.cohesive.ddf.provider.common.UpdateByURIRequest;
import com.cohesive.ddf.provider.common.query.CatalogQuery;
import com.cohesive.ddf.provider.persistence.ContentTypeMapper;
import com.cohesive.ddf.provider.persistence.IngestMapper;
import com.cohesive.ddf.provider.persistence.QueryMapper;
import com.jolbox.bonecp.BoneCPDataSource;

import ddf.catalog.data.ContentType;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.Result;
import ddf.catalog.filter.FilterAdapter;
import ddf.catalog.operation.CreateRequest;
import ddf.catalog.operation.CreateResponse;
import ddf.catalog.operation.CreateResponseImpl;
import ddf.catalog.operation.DeleteRequest;
import ddf.catalog.operation.DeleteResponse;
import ddf.catalog.operation.DeleteResponseImpl;
import ddf.catalog.operation.Query;
import ddf.catalog.operation.QueryRequest;
import ddf.catalog.operation.SourceResponse;
import ddf.catalog.operation.SourceResponseImpl;
import ddf.catalog.operation.Update;
import ddf.catalog.operation.UpdateImpl;
import ddf.catalog.operation.UpdateRequest;
import ddf.catalog.operation.UpdateResponse;
import ddf.catalog.operation.UpdateResponseImpl;
import ddf.catalog.source.CatalogProvider;
import ddf.catalog.source.IngestException;
import ddf.catalog.source.SourceMonitor;
import ddf.catalog.source.UnsupportedQueryException;
import ddf.catalog.util.MaskableImpl;
import ddf.security.encryption.EncryptionService;

public class PostgresCatalogProvider extends MaskableImpl implements CatalogProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger( PostgresCatalogProvider.class );

    private SourceMonitor callback;

    // The following 4 variables are injected by Spring Beans file
    private FilterAdapter filterAdapter = null;
    private BoneCPDataSource datasource = null;
    private EncryptionService encryptionService = null;
    private PostgresNamespaceHandler namespaceHandler = null;

    // MyBatis Mappers
    @Autowired
    private ContentTypeMapper catalogTypesMapper;
    @Autowired
    private IngestMapper crudMapper;
    @Autowired
    private QueryMapper queryMapper = null;

    public PostgresCatalogProvider() {
        this.setDescription( "DDF 2.1 PostgreSQL Catalog Provider" );
        this.setVersion( "1.1.0" );
        this.setOrganization( "Cohesive Integrations" );
        this.setTitle( "PostgreSQL Catalog Provider" );
    }
    
    public void destroy(){
        if ( datasource != null ){
            datasource.close();
        }
    }

    public void setDataSource( BoneCPDataSource datasource ) {
        this.datasource = datasource;
    }

    public void setFilterAdapter( FilterAdapter adapter ) {
        this.filterAdapter = adapter;
    }

    public void setEncryptionService( EncryptionService encryptionService ) {
        this.encryptionService = encryptionService;
    }

    public void setNamespaceHandler( PostgresNamespaceHandler nsHandler ) {
        this.namespaceHandler = nsHandler;
    }

    @Override
    public boolean isAvailable() {
        try {
            LOGGER.debug( "Testing if database connection is available (called from CatalogFramework)" );
            boolean available = queryMapper.testConnection() == 1;
            if ( callback != null ) {
                if ( available ) {
                    callback.setAvailable();
                } else {
                    callback.setUnavailable();
                }
            }
            return available;
        } catch ( Exception e ) {
            LOGGER.warn( "Postgres Connection not available: " + e.getMessage() );
            LOGGER.debug( e.getMessage(), e );
            return false;
        }
    }

    @Override
    public boolean isAvailable( SourceMonitor cb ) {
        this.callback = cb;
        return isAvailable();
    }

    public void maskId( String sourceId ) {
        LOGGER.info( this.getTitle() + " Source ID changed from [" + getId() + "] to [" + sourceId + "]" );
        super.maskId( sourceId );
    }

    @Override
    public CreateResponse create( CreateRequest createRequest ) throws IngestException {
        try {
            
            List<Metacard> createMetacards = createRequest.getMetacards();
            List<Metacard> output = new ArrayList<Metacard>();
            LOGGER.debug( "Attempting to store metadata for {} metacards", createMetacards.size() );
            Date now = new Date();
            String sourceId = getId();
            for ( Metacard metacard : createMetacards ) {
                ProviderMetacard providerMetacard = new ProviderMetacard( metacard, now, sourceId );
                crudMapper.createMetacard( providerMetacard );
                output.add( providerMetacard );
                LOGGER.trace( "Saved Metacard in Postgres Catalog: {}", providerMetacard );
            }

            return new CreateResponseImpl( createRequest, null, output );
        } catch ( Exception e ) {
            LOGGER.warn( "Could not ingest Metacard: {}", e.getMessage(), e );
            throw new IngestException( e.getMessage() );
        } catch ( Throwable t ) {
            LOGGER.warn( "Could not ingest Metacard: {}", t.getMessage(), t );
            throw new IngestException( t.getMessage() );
        }
    }

    @Override
    public UpdateResponse update( UpdateRequest updateRequest ) throws IngestException {
        String updateAttribute = updateRequest.getAttributeName();
        LOGGER.debug( "Attempting to update metadata using the '{}' attribute", updateAttribute );
        List<Update> updates = null;
        if ( UpdateRequest.UPDATE_BY_ID.equals( updateAttribute ) ) {
            updates = updateById( updateRequest );
        } else if ( UpdateRequest.UPDATE_BY_PRODUCT_URI.equals( updateAttribute ) ) {
            updates = updateByResourceURI( updateRequest );
        } else {
            LOGGER.warn( "Could not update Metacard because the Update By attribute is unknown{}", updateAttribute );
            throw new IngestException( "Updating by '" + updateAttribute + "' is not supported by the " + getTitle() + ", only updating by '" + UpdateRequest.UPDATE_BY_ID + "' or '"
                    + UpdateRequest.UPDATE_BY_PRODUCT_URI + " are supported." );
        }
        return new UpdateResponseImpl( updateRequest, null, updates );
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public DeleteResponse delete( DeleteRequest deleteRequest ) throws IngestException {
        String deleteAttribute = deleteRequest.getAttributeName();
        LOGGER.debug( "Attempting to update metadata using the '{}' attribute", deleteAttribute );
        int size = 0;
        List<Metacard> returnCards = null;
        if ( DeleteRequest.DELETE_BY_ID.equals( deleteAttribute ) ) {
            List<String> ids = (List<String>) deleteRequest.getAttributeValues();
            List<Result> results = queryMapper.queryById( ids );
            size = crudMapper.deleteMetacards( ids );
            LOGGER.trace( "Deleted {} Metacards from Postgres Catalog", size );
            returnCards = new ArrayList<Metacard>( size );
            final String id = getId();
            for ( Result result : results ) {
                Metacard metacard = result.getMetacard();
                metacard.setSourceId( id );
                returnCards.add( metacard );
                if ( LOGGER.isTraceEnabled() ){
                    LOGGER.trace( "Deleted Metacard from Postgres Catalog: {}", metacard );
                }
            }
        } else if ( DeleteRequest.DELETE_BY_PRODUCT_URI.equals( deleteAttribute ) ) {
            List<URI> uris = (List<URI>) deleteRequest.getAttributeValues();
            List<Result> results = queryMapper.queryByURI( uris );
            size = crudMapper.deleteMetacardsByURI( uris );
            LOGGER.trace( "Deleted {} Metacards from Postgres Catalog", size );
            returnCards = new ArrayList<Metacard>( size );
            final String id = getId();
            for ( Result result : results ) {
                Metacard metacard = result.getMetacard();
                metacard.setSourceId( id );
                returnCards.add( metacard );
                if ( LOGGER.isTraceEnabled() ){
                    LOGGER.trace( "Deleted Metacard from Postgres Catalog: {}", metacard );
                }
            }
        } else {
            LOGGER.warn( "Could not delete Metacard because the Delete By attribute is unknown{}", deleteAttribute );
            throw new IngestException( "Deleting by '" + deleteAttribute + "' is not supported by the " + getTitle() + ", only deleting by '" + DeleteRequest.DELETE_BY_ID + "' or '"
                    + DeleteRequest.DELETE_BY_PRODUCT_URI + " are supported." );
        }

        return new DeleteResponseImpl( deleteRequest, null, new ArrayList<Metacard>( returnCards ) );
    }

    @Override
    public SourceResponse query( QueryRequest request ) throws UnsupportedQueryException {
        try {
            Query query = request.getQuery();
            LOGGER.debug( "Attempting to execute query against Postgres Catalog: {}", query );

            SQLHolder holder = filterAdapter.adapt( request.getQuery(), new PostgresFilterDelegate( namespaceHandler ) );
            CatalogQuery providerQuery = new PostgresQuery( query, holder, getId() );

            List<Result> results = this.queryMapper.query( providerQuery );
            Long grandTotal = null;
            if ( query.requestsTotalResultsCount() ) {
                int queryPageSize = query.getPageSize();
                int resultsSize = results.size();
                LOGGER.debug( "Query returned {} matching results", resultsSize );
                if ( queryPageSize == resultsSize ) {
                    grandTotal = Long.valueOf( queryMapper.getTotalCount( providerQuery ) );
                } else {
                    int startIndex = query.getStartIndex();
                    if ( startIndex < 1 ) {
                        startIndex = 1;
                    }
                    grandTotal = Long.valueOf( resultsSize + (startIndex - 1) );
                }
            }
            return new SourceResponseImpl( request, results, grandTotal );
        } catch ( Exception e ) {
            LOGGER.debug( "Could not execte query: " + e.getMessage(), e );
            throw new UnsupportedQueryException( e );
        }
    }

    @Override
    public Set<ContentType> getContentTypes() {
        Set<ContentType> types = null;
        try {
            types = new LinkedHashSet<ContentType>( catalogTypesMapper.getContentTypes() );
            LOGGER.debug( "The Content Types returned from Postgres Metadata Catalog are: {}", types );
        } catch ( Exception e ) {
            LOGGER.warn( "Could not get Content Types: " + e.getMessage() );
            LOGGER.debug( e.getMessage(), e );
            types = new HashSet<ContentType>();
        }
        return types;
    }

    private List<Update> updateByResourceURI( UpdateRequest updateRequest ) {
        List<Entry<Serializable, Metacard>> updates = updateRequest.getUpdates();
        List<Update> returnUpdates = new ArrayList<Update>();
        Date now = new Date();
        for ( Entry<Serializable, Metacard> entry : updates ) {
            ProviderMetacard metacard = new ProviderMetacard( entry.getValue(), now, getId() );
            // System.out.println( "Resource URI (Update): " + metacard.getResourceURI() );
            // System.out.println( "Resource Size (Update): " + metacard.getResourceSize() );
            int size = crudMapper.updateMetacardByURI( new UpdateByURIRequest( metacard, ((URI) entry.getKey()).toString() ) );
            LOGGER.debug( "Updated {} metacards by ResourceID", size );
            returnUpdates.add( new UpdateImpl( metacard, null ) );
        }
        return returnUpdates;
    }

    private List<Update> updateById( UpdateRequest updateRequest ) {
        List<Entry<Serializable, Metacard>> updates = updateRequest.getUpdates();
        List<Update> returnUpdates = new ArrayList<Update>();
        Date now = new Date();
        for ( Entry<Serializable, Metacard> entry : updates ) {
            ProviderMetacard metacard = new ProviderMetacard( entry.getValue(), now, (String) entry.getKey(), getId() );
            // System.out.println( "Resource URI (Update): " + metacard.getResourceURI() );
            // System.out.println( "Resource Size (Update): " + metacard.getResourceSize() );
            int size = crudMapper.updateMetacard( metacard );
            LOGGER.debug( "Updated {} metacards by Catalog ID", size );
            returnUpdates.add( new UpdateImpl( metacard, null ) );
        }
        return returnUpdates;
    }

    public void updateDataSource( @SuppressWarnings( "rawtypes" ) Map properties ) {
        LOGGER.debug( "Attempting to update the Postgres Datasource with values: {}", properties );
        if ( datasource == null ) {
            LOGGER.warn( "DataSource is null and has not been properly initialized. This object should be created before it is updated." );
            return;
        }
        Object propertyObject = properties.get( "url" );
        if ( propertyObject != null ) {
            datasource.setJdbcUrl( propertyObject.toString() );
            LOGGER.info( "Set Database URL to [" + propertyObject + "]" );
        }

        propertyObject = properties.get( "username" );
        if ( propertyObject != null ) {
            datasource.setUsername( propertyObject.toString() );
            LOGGER.info( "Set Database Username to [" + propertyObject + "]" );
        }

        propertyObject = properties.get( "password" );
        if ( propertyObject != null ) {
            String decryptedPassword = decrypt( propertyObject.toString() );
            datasource.setPassword( decryptedPassword );
            LOGGER.info( "Set Database Password." );
        }

        propertyObject = properties.get( "idleConnectionTestPeriodInMinutes" );
        try {
            if ( propertyObject != null ) {
                datasource.setIdleConnectionTestPeriodInMinutes( Long.valueOf( propertyObject.toString() ) );
                LOGGER.info( "Set idleConnectionTestPeriodInMinutes to [" + propertyObject + "]" );
            }
        } catch ( NumberFormatException e ) {
            LOGGER.info( "This property {idleConnectionTestPeriodInMinutes} requires that the input be an integer value. ", e );
        }

        propertyObject = properties.get( "idleMaxAgeInMinutes" );
        try {
            if ( propertyObject != null ) {
                datasource.setIdleMaxAgeInMinutes( Long.valueOf( propertyObject.toString() ) );
                LOGGER.info( "Set idleMaxAgeInMinutes to [" + propertyObject + "]" );
            }
        } catch ( NumberFormatException e ) {
            LOGGER.info( "This property {idleMaxAgeInMinutes} requires that the input be an integer value. ", e );
        }

        propertyObject = properties.get( "maxConnectionsPerPartition" );
        try {
            if ( propertyObject != null ) {
                datasource.setMaxConnectionsPerPartition( Integer.valueOf( propertyObject.toString() ) );
                LOGGER.info( "Set maxConnectionsPerPartition to [" + propertyObject + "]" );
            }
        } catch ( NumberFormatException e ) {
            LOGGER.info( "This property {maxConnectionsPerPartition} requires that the input be an integer value. ", e );
        }

        propertyObject = properties.get( "partitionCount" );
        try {
            if ( propertyObject != null ) {
                datasource.setPartitionCount( Integer.valueOf( propertyObject.toString() ) );
                LOGGER.info( "Set partitionCount to [" + propertyObject + "]" );
            }
        } catch ( NumberFormatException e ) {
            LOGGER.info( "This property {partitionCount} requires that the input be an integer value. ", e );
        }

        propertyObject = properties.get( "acquireIncrement" );
        try {
            if ( propertyObject != null ) {
                datasource.setAcquireIncrement( Integer.valueOf( propertyObject.toString() ) );
                LOGGER.info( "Set acquireIncrement to [" + propertyObject + "]" );
            }
        } catch ( NumberFormatException e ) {
            LOGGER.info( "This property {acquireIncrement} requires that the input be an integer value. ", e );
        }

        propertyObject = properties.get( "statementsCacheSize" );
        try {
            if ( propertyObject != null ) {
                datasource.setStatementsCacheSize( Integer.valueOf( propertyObject.toString() ) );
                LOGGER.info( "Set statementsCacheSize to [" + propertyObject + "]" );
            }
        } catch ( NumberFormatException e ) {
            LOGGER.info( "This property {statementsCacheSize} requires that the input be an integer value. ", e );
        }

        if ( callback != null ) {
            if ( isAvailable() ) {
                callback.setAvailable();
            } else {
                callback.setUnavailable();
            }
        } else {
            LOGGER.debug( "Availability Callback is null." );
        }
    }

    // @formatter:off
    /**
     * 
     * @param wrappedEncryptedValue
     *            An wrapped encrypted password.
     * 
     *            <pre>
     *   {@code
     *     One can encrypt passwords using the security:encrypt console command.
     *     
     *     ddf@local>security:encrypt protect
     *     HsOcGt8seSKc34sRUYpakQ==
     *     
     *     A wrapped encrypted password is wrapped in ENC() as follows: ENC(HsOcGt8seSKc34sRUYpakQ==)
     *   }
     * </pre>
     */
    // @formatter:on
    private String decrypt( String wrappedEncryptedValue ) {
        String decryptedValue = null;
        String encryptedValue = unwrapEncryptedValue( wrappedEncryptedValue );

        if ( wrappedEncryptedValue == null || wrappedEncryptedValue.isEmpty() ) {
            LOGGER.error( "A blank password was provided in the Postgres Catalog Provider configuration in the console." );
            decryptedValue = null;
        } else if ( encryptionService != null && !wrappedEncryptedValue.equals( encryptedValue ) ) {
            decryptedValue = encryptionService.decrypt( encryptedValue );
        } else if ( encryptionService == null && !wrappedEncryptedValue.equals( encryptedValue ) ) {
            LOGGER.error( "The Postgres Catalog Provider has a null Encryption Service.  Unable to attempt to decrypt the encrypted Postgres password: " + encryptedValue
                    + ".  Setting decrypted password to null." );
            decryptedValue = null;
        } else if ( wrappedEncryptedValue.equals( encryptedValue ) ) {
            /**
             * If the password is not in the form ENC(my-encrypted-password), we assume the password is not encrypted.
             */
            decryptedValue = wrappedEncryptedValue;
            LOGGER.warn( "A plain text password was provided in the Postgres Catalog Provider configuration in the console.  Consider using an encrypted password." );
        }

        /*
         * if (logger.isDebugEnabled()) { StringBuilder builder = new StringBuilder();
         * builder.append("encryptionService: " + encryptionService + "\n"); builder.append("wrappedEncryptedValue: " +
         * wrappedEncryptedValue + "\n"); builder.append("encryptedValue: " + encryptedValue + "\n");
         * builder.append("decryptedValue: " + decryptedValue + "\n"); logger.debug(builder.toString()); }
         */

        return decryptedValue;
    }

    private String unwrapEncryptedValue( String wrappedEncryptedValue ) {
        /**
         * The wrapped encrypted password should be in the form ENC(my-encrypted-password)
         */
        final String pattern = "^ENC\\((.*)\\)$";
        String unwrappedEncryptedValue = null;

        // logger.debug("Wrapped encrypted value: " + wrappedEncryptedValue);
        Pattern p = Pattern.compile( pattern );
        Matcher m = p.matcher( wrappedEncryptedValue );
        if ( m.find() ) {
            /**
             * Get the value in parenthesis. In this example, ENC(my-encrypted-password), m.group(1) would return
             * my-encrypted-password.
             */
            unwrappedEncryptedValue = m.group( 1 );
            // logger.debug("Encrypted value: " + unwrappedEncryptedValue);
        } else {
            /**
             * If the password is not in the form ENC(my-encrypted-password), we assume the password is not encrypted.
             */
            unwrappedEncryptedValue = wrappedEncryptedValue;
            LOGGER.warn( "You have provided a plain text password in your " + "Postgres Provider configuration.  Consider using an encrypted password." );
        }

        return unwrappedEncryptedValue;
    }

}
