package org.infinispan.client.hotrod.query;

import org.hibernate.search.spi.SearchIntegrator;
import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.Search;
import org.infinispan.client.hotrod.exceptions.HotRodClientException;
import org.infinispan.client.hotrod.impl.query.RemoteQueryFactory;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.client.hotrod.query.testdomain.protobuf.ModelFactoryPB;
import org.infinispan.client.hotrod.query.testdomain.protobuf.marshallers.MarshallerRegistration;
import org.infinispan.client.hotrod.query.testdomain.protobuf.marshallers.NotIndexedMarshaller;
import org.infinispan.client.hotrod.test.HotRodClientTestingUtil;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.Index;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.query.dsl.Expression;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.infinispan.query.dsl.SortOrder;
import org.infinispan.query.dsl.embedded.QueryDslConditionsTest;
import org.infinispan.query.dsl.embedded.testdomain.Account;
import org.infinispan.query.dsl.embedded.testdomain.ModelFactory;
import org.infinispan.query.remote.client.ProtobufMetadataManagerConstants;
import org.infinispan.query.remote.impl.indexing.ProtobufValueWrapper;
import org.infinispan.server.hotrod.HotRodServer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static org.infinispan.client.hotrod.test.HotRodClientTestingUtil.killRemoteCacheManager;
import static org.infinispan.client.hotrod.test.HotRodClientTestingUtil.killServers;
import static org.infinispan.server.hotrod.test.HotRodTestingUtil.hotRodCacheConfiguration;
import static org.junit.Assert.*;

/**
 * Test for query conditions (filtering). Exercises the whole query DSL on the sample domain model.
 *
 * @author anistor@redhat.com
 * @since 6.0
 */
@Test(groups = "functional", testName = "client.hotrod.query.RemoteQueryDslConditionsTest")
public class RemoteQueryDslConditionsTest extends QueryDslConditionsTest {

   private static final String NOT_INDEXED_PROTO_SCHEMA = "package sample_bank_account;\n" +
         "/* @Indexed(false) */\n" +
         "message NotIndexed {\n" +
         "\toptional string notIndexedField = 1;\n" +
         "}\n";
   protected HotRodServer hotRodServer;
   protected RemoteCacheManager remoteCacheManager;
   protected RemoteCache<Object, Object> remoteCache;
   protected Cache<Object, Object> cache;

   @Override
   protected QueryFactory getQueryFactory() {
      return Search.getQueryFactory(remoteCache);
   }

   @Override
   protected ModelFactory getModelFactory() {
      return ModelFactoryPB.INSTANCE;
   }

   @Override
   protected RemoteCache<Object, Object> getCacheForQuery() {
      return remoteCache;
   }

   protected Cache<Object, Object> getEmbeddedCache() {
      return cache;
   }

   @Override
   protected void createCacheManagers() throws Throwable {
      ConfigurationBuilder cfg = getConfigurationBuilder();
      createClusteredCaches(1, cfg);

      cache = manager(0).getCache();

      hotRodServer = HotRodClientTestingUtil.startHotRodServer(manager(0));

      org.infinispan.client.hotrod.configuration.ConfigurationBuilder clientBuilder = new org.infinispan.client.hotrod.configuration.ConfigurationBuilder();
      clientBuilder.addServer().host("127.0.0.1").port(hotRodServer.getPort());
      clientBuilder.marshaller(new ProtoStreamMarshaller());
      remoteCacheManager = new RemoteCacheManager(clientBuilder.build());
      remoteCache = remoteCacheManager.getCache();
      initProtoSchema(remoteCacheManager);
   }

   protected void initProtoSchema(RemoteCacheManager remoteCacheManager) throws IOException {
      //initialize server-side serialization context
      RemoteCache<String, String> metadataCache = remoteCacheManager.getCache(ProtobufMetadataManagerConstants.PROTOBUF_METADATA_CACHE_NAME);
      metadataCache.put("sample_bank_account/bank.proto", Util.read(Util.getResourceAsStream("/sample_bank_account/bank.proto", getClass().getClassLoader())));
      assertFalse(metadataCache.containsKey(ProtobufMetadataManagerConstants.ERRORS_KEY_SUFFIX));
      metadataCache.put("not_indexed.proto", NOT_INDEXED_PROTO_SCHEMA);

      //initialize client-side serialization context
      SerializationContext serCtx = ProtoStreamMarshaller.getSerializationContext(remoteCacheManager);
      MarshallerRegistration.registerMarshallers(serCtx);
      serCtx.registerProtoFiles(FileDescriptorSource.fromString("not_indexed.proto", NOT_INDEXED_PROTO_SCHEMA));
      serCtx.registerMarshaller(new NotIndexedMarshaller());
   }

   protected ConfigurationBuilder getConfigurationBuilder() {
      ConfigurationBuilder builder = hotRodCacheConfiguration();
      builder.indexing().index(Index.ALL)
            .addProperty("default.directory_provider", "ram")
            .addProperty("lucene_version", "LUCENE_CURRENT");
      return builder;
   }

   @AfterClass(alwaysRun = true)
   public void release() {
      killRemoteCacheManager(remoteCacheManager);
      killServers(hotRodServer);
   }

   @Override
   public void testIndexPresence() {
      SearchIntegrator searchIntegrator = org.infinispan.query.Search.getSearchManager(cache).unwrap(SearchIntegrator.class);

      assertTrue(searchIntegrator.getIndexedTypes().contains(ProtobufValueWrapper.class));
      assertNotNull(searchIntegrator.getIndexManager(ProtobufValueWrapper.class.getName()));
   }

   @Override
   public void testQueryFactoryType() {
      assertEquals(RemoteQueryFactory.class, getQueryFactory().getClass());
   }

   @Test(expectedExceptions = HotRodClientException.class, expectedExceptionsMessageRegExp = ".*ISPN000405:.*")
   @Override
   public void testInvalidEmbeddedAttributeQuery() throws Exception {
      super.testInvalidEmbeddedAttributeQuery();
   }

   /**
    * This test is overridden because dates need special handling for protobuf (being actually emulated as long
    * timestamps).
    */
   @Override
   public void testSampleDomainQuery9() throws Exception {
      QueryFactory qf = getQueryFactory();

      // all the transactions that happened in January 2013, projected by date field only
      Query q = qf.from(getModelFactory().getTransactionImplClass())
            .select("date")
            .having("date").between(makeDate("2013-01-01"), makeDate("2013-01-31"))
            .toBuilder().build();

      List<Object[]> list = q.list();
      assertEquals(4, list.size());
      assertEquals(1, list.get(0).length);
      assertEquals(1, list.get(1).length);
      assertEquals(1, list.get(2).length);
      assertEquals(1, list.get(3).length);

      for (int i = 0; i < 4; i++) {
         Long d = (Long) list.get(i)[0];
         assertTrue(d.compareTo(makeDate("2013-01-31").getTime()) <= 0);
         assertTrue(d.compareTo(makeDate("2013-01-01").getTime()) >= 0);
      }
   }

   public void testDefaultValue() throws Exception {
      QueryFactory qf = getQueryFactory();

      Query q = qf.from(getModelFactory().getAccountImplClass()).orderBy("description", SortOrder.ASC).build();

      List<Account> list = q.list();
      assertEquals(3, list.size());
      assertEquals("Checking account", list.get(0).getDescription());
   }

   //todo [anistor] null numbers do not seem to work in remote mode
   @Test(enabled = false)
   @Override
   public void testIsNullNumericWithProjection1() throws Exception {
      super.testIsNullNumericWithProjection1();
   }

   //todo [anistor] the original exception gets wrapped in HotRodClientException
   @Override
   @Test(expectedExceptions = HotRodClientException.class, expectedExceptionsMessageRegExp = "org.hibernate.hql.ParsingException: The expression 'surname' must be part of an aggregate function or it should be included in the GROUP BY clause")
   public void testGroupBy3() throws Exception {
      super.testGroupBy3();
   }

   //todo [anistor] the original exception gets wrapped in HotRodClientException
   @Test(expectedExceptions = HotRodClientException.class, expectedExceptionsMessageRegExp = "org.hibernate.hql.ParsingException: Queries containing grouping and aggregation functions must use projections.")
   @Override
   public void testGroupBy5() {
      super.testGroupBy5();
   }

   /**
    * This test is overridden because dates need special handling for protobuf (being actually emulated as long
    * timestamps).
    */
   @Override
   public void testDateGrouping1() throws Exception {
      QueryFactory qf = getQueryFactory();
      Query q = qf.from(getModelFactory().getTransactionImplClass())
            .select("date")
            .having("date").between(makeDate("2013-02-15"), makeDate("2013-03-15")).toBuilder()
            .groupBy("date")
            .build();

      List<Object[]> list = q.list();
      assertEquals(1, list.size());
      assertEquals(1, list.get(0).length);
      assertEquals(makeDate("2013-02-27").getTime(), list.get(0)[0]);
   }

   /**
    * This test is overridden because dates need special handling for protobuf (being actually emulated as long
    * timestamps).
    */
   @Override
   public void testDateGrouping2() throws Exception {
      QueryFactory qf = getQueryFactory();
      Query q = qf.from(getModelFactory().getTransactionImplClass())
            .select(Expression.count("date"), Expression.min("date"))
            .having("description").eq("Hotel").toBuilder()
            .groupBy("id")
            .build();

      List<Object[]> list = q.list();
      assertEquals(1, list.size());
      assertEquals(2, list.get(0).length);
      assertEquals(1L, list.get(0)[0]);
      assertEquals(makeDate("2013-02-27").getTime(), list.get(0)[1]);
   }

   /**
    * This test is overridden because dates need special handling for protobuf (being actually emulated as long
    * timestamps).
    */
   @Override
   public void testDateGrouping3() throws Exception {
      QueryFactory qf = getQueryFactory();
      Query q = qf.from(getModelFactory().getTransactionImplClass())
            .select(Expression.min("date"), Expression.count("date"))
            .having("description").eq("Hotel").toBuilder()
            .groupBy("id")
            .build();

      List<Object[]> list = q.list();
      assertEquals(1, list.size());
      assertEquals(2, list.get(0).length);
      assertEquals(makeDate("2013-02-27").getTime(), list.get(0)[0]);
      assertEquals(1L, list.get(0)[1]);
   }

   /**
    * This test is overridden because dates need special handling for protobuf (being actually emulated as long
    * timestamps).
    */
   @Override
   public void testDuplicateDateProjection() throws Exception {
      QueryFactory qf = getQueryFactory();

      Query q = qf.from(getModelFactory().getTransactionImplClass())
            .select("id", "date", "date")
            .having("description").eq("Hotel")
            .toBuilder().build();
      List<Object[]> list = q.list();

      assertEquals(1, list.size());
      assertEquals(3, list.get(0).length);
      assertEquals(3, list.get(0)[0]);
      assertEquals(makeDate("2013-02-27").getTime(), list.get(0)[1]);
      assertEquals(makeDate("2013-02-27").getTime(), list.get(0)[2]);
   }
}