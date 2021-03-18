package org.sunbird.job.spec

import java.io.File
import java.util

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
import org.apache.flink.test.util.MiniClusterWithClientResource
import org.cassandraunit.CQLDataLoader
import org.cassandraunit.dataset.cql.FileCQLDataSet
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito
import org.mockito.Mockito.doNothing
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.domain.`object`.DefinitionCache
import org.sunbird.job.fixture.EventFixture
import org.sunbird.job.functions.{ImageEnrichmentFunction, VideoEnrichmentFunction}
import org.sunbird.job.models.Asset
import org.sunbird.job.task.AssetEnrichmentConfig
import org.sunbird.job.util.{CassandraUtil, CloudStorageUtil, JSONUtil, Neo4JUtil, ScalaJsonUtil, Slug, YouTubeUtil}
import org.sunbird.spec.BaseTestSpec

class AssetEnrichmentTaskTestSpec extends BaseTestSpec {

  implicit val mapTypeInfo: TypeInformation[java.util.Map[String, AnyRef]] = TypeExtractor.getForClass(classOf[java.util.Map[String, AnyRef]])
  implicit val strTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])

  val flinkCluster = new MiniClusterWithClientResource(new MiniClusterResourceConfiguration.Builder()
    .setConfiguration(testConfiguration())
    .setNumberSlotsPerTaskManager(1)
    .setNumberTaskManagers(1)
    .build)
  val mockKafkaUtil: FlinkKafkaConnector = mock[FlinkKafkaConnector](Mockito.withSettings().serializable())
  val config: Config = ConfigFactory.load("test.conf").withFallback(ConfigFactory.systemEnvironment())
  val jobConfig = new AssetEnrichmentConfig(config)
  val definitionUtil = new DefinitionCache
  var cassandraUtil: CassandraUtil = _
  implicit val mockNeo4JUtil: Neo4JUtil = mock[Neo4JUtil](Mockito.withSettings().serializable())
  implicit val cloudUtil: CloudStorageUtil = new CloudStorageUtil(jobConfig)
  implicit val youTubeUtil: YouTubeUtil = new YouTubeUtil(jobConfig)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
        EmbeddedCassandraServerHelper.startEmbeddedCassandra(800000L)
        cassandraUtil = new CassandraUtil(jobConfig.dbHost, jobConfig.dbPort)
        val session = cassandraUtil.session
        val dataLoader = new CQLDataLoader(session)
        dataLoader.load(new FileCQLDataSet(getClass.getResource("/test.cql").getPath, true, true))
    flinkCluster.before()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
        try {
          EmbeddedCassandraServerHelper.cleanEmbeddedCassandra()
        } catch {
          case ex: Exception => {
          }
        }
    flinkCluster.after()
  }

  "replaceArtifactUrl" should " update the provided asset properties " in {
    val metaData = Map[String, AnyRef]("cloudStorageKey" -> "https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/do_1132316405761064961124/artifact/0_jmrpnxe-djmth37l_.jpg",
      "s3Key" -> "https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/do_1132316405761064961124/artifact/0_jmrpnxe-djmth37l_.jpg",
      "artifactBasePath" -> "https://sunbirddev.blob.core.windows.net/sunbird-content-dev",
      "artifactUrl" -> "https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/do_1132316405761064961124/artifact/0_jmrpnxe-djmth37l_.jpg")
    val asset = getAsset(EventFixture.IMAGE_ASSET, metaData)
    new ImageEnrichmentFunction(jobConfig).replaceArtifactUrl(asset)(cloudUtil)
    asset.getFromMetaData("artifactUrl", "") should be("https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/do_1132316405761064961124/artifact/0_jmrpnxe-djmth37l_.jpg")
    asset.getFromMetaData("downloadUrl", "") should be("https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/do_1132316405761064961124/artifact/0_jmrpnxe-djmth37l_.jpg")
    asset.getFromMetaData("cloudStorageKey", "") should be("content/do_1132316405761064961124/artifact/0_jmrpnxe-djmth37l_.jpg")
    asset.getFromMetaData("s3Key", "") should be("content/do_1132316405761064961124/artifact/0_jmrpnxe-djmth37l_.jpg")
  }

  "imageEnrichment" should " enrich the image for the asset " in {
    doNothing().when(mockNeo4JUtil).updateNode(anyString(), any[Map[String, AnyRef]]())

    val metaData = getMetaDataForImageAsset
    val asset = getAsset(EventFixture.IMAGE_ASSET, metaData)
    new ImageEnrichmentFunction(jobConfig).imageEnrichment(asset)(jobConfig, definitionUtil, cloudUtil, mockNeo4JUtil)
    val variants = ScalaJsonUtil.deserialize[Map[String, String]](asset.getFromMetaData("variants", "").asInstanceOf[String])
    variants.size should be(3)
    variants.keys should contain allOf("high", "medium", "low")
    variants.getOrElse("high", "") should be("https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/do_113233717480390656195/artifact/bitcoin-4_1545114579639.jpg")
    asset.getFromMetaData("status", "").asInstanceOf[String] should be("Live")
  }

  "vidoeEnrichment" should " enrich the video for the mp4 asset " in {
    doNothing().when(mockNeo4JUtil).updateNode(anyString(), any[Map[String, AnyRef]]())

    val metaData = getMetaDataForMp4VideoAsset
    val asset = getAsset(EventFixture.VIDEO_MP4_ASSET, metaData)
    new VideoEnrichmentFunction(jobConfig).videoEnrichment(asset)(jobConfig, youTubeUtil, cloudUtil, mockNeo4JUtil, cassandraUtil)
    asset.getFromMetaData("status", "").asInstanceOf[String] should be("Live")
  }

  "vidoeEnrichment" should " enrich the video for the youtube asset " in {
    doNothing().when(mockNeo4JUtil).updateNode(anyString(), any[Map[String, AnyRef]]())

    val metaData = getMetaDataForYouTubeVideoAsset
    val asset = getAsset(EventFixture.VIDEO_YOUTUBE_ASSET, metaData)
    new VideoEnrichmentFunction(jobConfig).videoEnrichment(asset)(jobConfig, youTubeUtil, cloudUtil, mockNeo4JUtil, cassandraUtil)
    asset.getFromMetaData("thumbnail", "").asInstanceOf[String] should be("https://i.ytimg.com/vi/-SgZ3Enpau8/mqdefault.jpg")
    asset.getFromMetaData("status", "").asInstanceOf[String] should be("Live")
    asset.getFromMetaData("duration", "0").asInstanceOf[String] should be("273")
  }

  "test create Slug file " should " create slug file for the provided file" in {
    val file = new File("-αimage.jpg")
    val slugFile = Slug.createSlugFile(file)
    assert("aimage.jpg" == slugFile.getName)
  }

  "validateForArtifactUrl" should "validate for content upload context driven" in {
    val metaData = Map[String, AnyRef]("cloudStorageKey" -> "https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/do_1132316405761064961124/artifact/0_jmrpnxe-djmth37l_.jpg",
      "s3Key" -> "https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/do_1132316405761064961124/artifact/0_jmrpnxe-djmth37l_.jpg",
      "artifactBasePath" -> "https://sunbirddev.blob.core.windows.net/sunbird-content-dev",
      "artifactUrl" -> "https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/do_1132316405761064961124/artifact/0_jmrpnxe-djmth37l_.jpg")
    val asset = getAsset(EventFixture.IMAGE_ASSET, metaData)
    val validate = new VideoEnrichmentFunction(jobConfig).validateForArtifactUrl(asset, true)
    validate should be(true)
  }

  "ImageEnrichmentHelper" should "return Exception if the image url is wrong" in {
    val metaData = getMetaDataForImageAsset
    val asset = getAsset(EventFixture.IMAGE_ASSET, metaData)
    asset.addToMetaData("downloadUrl", "https://unknownurl123.com")
    assertThrows[Exception] {
      new ImageEnrichmentFunction(jobConfig).imageEnrichment(asset)(jobConfig, definitionUtil, cloudUtil, mockNeo4JUtil)
    }
  }

  "VideoEnrichmentHelper" should "return Exception if the video url is wrong" in {
    val metaData = getMetaDataForMp4VideoAsset
    val asset = getAsset(EventFixture.VIDEO_MP4_ASSET, metaData)
    asset.addToMetaData("artifactUrl", "https://unknownurl1234.com")
    assertThrows[Exception] {
      new VideoEnrichmentFunction(jobConfig).videoEnrichment(asset)(jobConfig, youTubeUtil, cloudUtil, mockNeo4JUtil, cassandraUtil)
    }
  }

  "VideoEnrichmentHelper" should "return Exception if the video url is empty" in {
    val metaData = getMetaDataForMp4VideoAsset
    val asset = getAsset(EventFixture.VIDEO_MP4_ASSET, metaData)
    asset.addToMetaData("artifactUrl", "")
    assertThrows[Exception] {
      new VideoEnrichmentFunction(jobConfig).videoEnrichment(asset)(jobConfig, youTubeUtil, cloudUtil, mockNeo4JUtil, cassandraUtil)
    }
  }

  "VideoEnrichmentFunction" should "return Exception if the metadata is not found" in {
    val metaData = getMetaDataForMp4VideoAsset
    val asset = getAsset(EventFixture.VIDEO_MP4_ASSET, metaData)
    asset.addToMetaData("artifactUrl", "")
    assertThrows[Exception] {
      new VideoEnrichmentFunction(jobConfig).getMetaData(asset.identifier)(mockNeo4JUtil)
    }
  }

  "ImageEnrichmentFunction" should "return Exception if the metadata is not found" in {
    val metaData = getMetaDataForImageAsset
    val asset = getAsset(EventFixture.IMAGE_ASSET, metaData)
    asset.addToMetaData("artifactUrl", "")
    assertThrows[Exception] {
      new ImageEnrichmentFunction(jobConfig).getMetaData(asset.identifier)(mockNeo4JUtil)
    }
  }

  "getVideoInfo" should "return the id of the video for the provided Youtube URL" in {
    val result = new YouTubeUtil(jobConfig).getIdFromUrl("https://www.youtube.com/watch?v=-SgZ3Enpau8")
    result.getOrElse("") should be("-SgZ3Enpau8")
  }

  "getVideoInfo" should "return the duration of the video for the " in {
    val result = new YouTubeUtil(jobConfig).computeVideoDuration("PT4M33S")
    result should be("273")
  }

  def getAsset(event: String, metaData: Map[String, AnyRef]): Asset = {
    val eventMap = JSONUtil.deserialize[util.Map[String, Any]](event)
    val asset = Asset(eventMap)
    asset.setMetaData(metaData)
    asset
  }

  def getMetaDataForImageAsset: Map[String, AnyRef] = {
    val metaData = """{"ownershipType": ["createdBy"], "code": "org.ekstep0.07321483804683715", "prevStatus": "Processing", "downloadUrl": "https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/do_113233717480390656195/artifact/bitcoin-4_1545114579639.jpg", "channel": "b00bc992ef25f1a9a8d63291e20efc8d", "language": ["English"], "mimeType": "image/jpeg","idealScreenSize": "normal", "createdOn": "2021-03-11T06:27:08.370+0000", "primaryCategory": "Certificate Template", "contentDisposition": "inline", "artifactUrl": "https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/do_113233717480390656195/artifact/bitcoin-4_1545114579639.jpg", "contentEncoding": "identity", "lastUpdatedOn": "2021-03-11T06:27:10.254+0000", "contentType": "Asset", "dialcodeRequired": "No", "audience": ["Student"], "creator": "Reviewer User", "lastStatusChangedOn": "2021-03-11T06:27:10.237+0000", "visibility": "Default", "os": ["All"], "IL_SYS_NODE_TYPE": "DATA_NODE", "cloudStorageKey": "content/do_113233717480390656195/artifact/bitcoin-4_1545114579639.jpg", "mediaType": "image", "osId": "org.ekstep.quiz.app", "version": 2, "versionKey": "1615444030254", "license": "CC BY 4.0", "idealScreenDensity": "hdpi", "s3Key": "content/do_113233717480390656195/artifact/bitcoin-4_1545114579639.jpg", "framework": "NCF", "size": 86402.0, "createdBy": "95e4942d-cbe8-477d-aebd-ad8e6de4bfc8", "compatibilityLevel": 1, "IL_FUNC_OBJECT_TYPE": "Asset", "name": "bitcoin-4 1545114579639", "IL_UNIQUE_ID": "do_113233717480390656195", "status": "Live"}"""
    ScalaJsonUtil.deserialize[Map[String, AnyRef]](metaData)
  }

  def getMetaDataForMp4VideoAsset: Map[String, AnyRef] = {
    val metaData = """{"artifactUrl": "https://sunbirddev.blob.core.windows.net/sunbird-content-dev/content/kp_ft_1563562323128/artifact/sample_1563562323191.mp4", "ownershipType": ["createdBy"], "code": "Test_Asset_15_MB", "apoc_json": "{\"batch\": true}", "channel": "in.ekstep", "organisation": ["Sunbird", "QA ORG"], "language": ["English"], "mimeType": "video/mp4", "media": "video", "idealScreenSize": "normal", "createdOn": "2019-03-06T13:13:13.917+0000", "apoc_text": "APOC", "primaryCategory": "Asset", "appId": "local.sunbird.portal", "contentDisposition": "inline", "contentEncoding": "identity", "lastUpdatedOn": "2019-03-06T13:13:13.917+0000", "contentType": "Asset", "dialcodeRequired": "No", "apoc_num": 1, "audience": ["Learner"], "creator": "Creation", "createdFor": ["ORG_001", "0123653943740170242"], "visibility": "Default", "os": ["All"], "IL_SYS_NODE_TYPE": "DATA_NODE", "consumerId": "9393568c-3a56-47dd-a9a3-34da3c821638", "mediaType": "content", "osId": "org.ekstep.quiz.app", "versionKey": "1551877993917", "idealScreenDensity": "hdpi", "framework": "NCFCOPY", "createdBy": "874ed8a5-782e-4f6c-8f36-e0288455901e", "compatibilityLevel": 1.0, "IL_FUNC_OBJECT_TYPE": "Asset", "name": "Test_Asset_15_MB", "IL_UNIQUE_ID": "do_1127129845261680641588", "status": "Draft"}"""
    ScalaJsonUtil.deserialize[Map[String, AnyRef]](metaData)
  }

  def getMetaDataForYouTubeVideoAsset: Map[String, AnyRef] = {
    val metaData = """{"artifactUrl": "https://www.youtube.com/watch?v=-SgZ3Enpau8", "ownershipType": ["createdBy"], "code": "Test_Asset_15_MB", "apoc_json": "{\"batch\": true}", "channel": "in.ekstep", "organisation": ["Sunbird", "QA ORG"], "language": ["English"], "mimeType": "video/x-youtube", "media": "video", "idealScreenSize": "normal", "createdOn": "2019-03-06T13:13:13.917+0000", "apoc_text": "APOC", "primaryCategory": "Asset", "appId": "local.sunbird.portal", "contentDisposition": "inline", "contentEncoding": "identity", "lastUpdatedOn": "2019-03-06T13:13:13.917+0000", "contentType": "Asset", "dialcodeRequired": "No", "apoc_num": 1, "audience": ["Learner"], "creator": "Creation", "createdFor": ["ORG_001", "0123653943740170242"], "visibility": "Default", "os": ["All"], "IL_SYS_NODE_TYPE": "DATA_NODE", "consumerId": "9393568c-3a56-47dd-a9a3-34da3c821638", "mediaType": "content", "osId": "org.ekstep.quiz.app", "versionKey": "1551877993917", "idealScreenDensity": "hdpi", "framework": "NCFCOPY", "createdBy": "874ed8a5-782e-4f6c-8f36-e0288455901e", "compatibilityLevel": 1.0, "IL_FUNC_OBJECT_TYPE": "Asset", "name": "Test_Asset_15_MB", "IL_UNIQUE_ID": "do_1127129845261680641599", "status": "Draft"}"""
    ScalaJsonUtil.deserialize[Map[String, AnyRef]](metaData)
  }

}