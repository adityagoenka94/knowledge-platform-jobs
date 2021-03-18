package org.sunbird.job.task

import java.util

import com.typesafe.config.Config
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.streaming.api.scala.OutputTag
import org.sunbird.job.BaseJobConfig
import org.sunbird.job.domain.Event
import scala.collection.JavaConverters._

class AssetEnrichmentConfig(override val config: Config) extends BaseJobConfig(config, "composite-search-indexer") {

  implicit val mapTypeInfo: TypeInformation[util.Map[String, AnyRef]] = TypeExtractor.getForClass(classOf[util.Map[String, AnyRef]])
  implicit val eventTypeInfo: TypeInformation[Event] = TypeExtractor.getForClass(classOf[Event])

  // Job Configuration
  val jobEnv: String = config.getString("job.env")

  // Kafka Topics Configuration
  val kafkaInputTopic: String = config.getString("kafka.input.topic")

  // Parallelism
  val eventRouterParallelism: Int = config.getInt("task.router.parallelism")
  val videoEnrichmentIndexerParallelism: Int = config.getInt("task.videoEnrichment.parallelism")
  val imageEnrichmentIndexerParallelism: Int = config.getInt("task.imageEnrichment.parallelism")

  // Consumers
  val assetEnrichmentConsumer = "asset-enrichment-consumer"
  val assetEnrichmentRouter = "asset-enrichment-router"

  // Metric List
  val totalEventsCount = "total-events-count"
  val skippedEventCount = "skipped-event-count"
  val imageEnrichmentEventCount = "image-enrichment-event-count"
  val successImageEnrichmentEventCount = "success-image-enrichment-event-count"
  val failedImageEnrichmentEventCount = "failed-image-enrichment-event-count"
  val successVideoEnrichmentEventCount = "success-video-enrichment-event-count"
  val failedVideoEnrichmentEventCount = "failed-video-enrichment-event-count"
  val videoEnrichmentEventCount = "video-enrichment-event-count"

  // Neo4J Configurations
  val graphRoutePath: String = config.getString("neo4j.routePath")
  val graphName: String = config.getString("neo4j.graph")

  // Tags
  val imageEnrichmentDataOutTag: OutputTag[Event] = OutputTag[Event]("image-enrichment-data")
  val videoEnrichmentDataOutTag: OutputTag[Event] = OutputTag[Event]("video-enrichment-data")

  // Asset Variables
  val contentUploadContextDriven: Boolean = if (config.hasPath("content.upload.context.driven")) config.getBoolean("content.upload.context.driven") else true
  val maxIterationCount: Int = if (config.hasPath("max.iteration.count")) config.getInt("max.iteration.count") else 2

  // Video Enrichment
  val youtubeAppName: String = if (config.hasPath("content.youtube.applicationName")) config.getString("content.youtube.applicationName") else "fetch-youtube-license"
  val videoIdRegex: util.List[String] = if (config.hasPath("youtube.license.regexPattern")) config.getStringList("youtube.license.regexPattern") else util.Arrays.asList[String]("\\?vi?=([^&]*)", "watch\\?.*v=([^&]*)", "(?:embed|vi?)/([^/?]*)", "^([A-Za-z0-9\\-\\_]*)")
  val streamableMimeType: util.List[String] = if (config.hasPath("content.stream.mimeType")) config.getStringList("content.stream.mimeType") else util.Arrays.asList[String]("video/mp4")
  val isStreamingEnabled: Boolean = if (config.hasPath("content.streamingEnabled")) config.getBoolean("content.streamingEnabled") else false

  // Video Stream Cassandra
  val dbHost: String = config.getString("lpa-cassandra.host")
  val dbPort: Int = config.getInt("lpa-cassandra.port")
  val streamKeyspace: String = if (config.hasPath("lpa-cassandra.keyspace")) config.getString("lpa-cassandra.keyspace") else "platform_db"
  val streamTable: String = if (config.hasPath("lpa-cassandra.stream.table")) config.getString("lpa-cassandra.stream.table") else "job_request"

  // Schema Definition Util for Image Enrichment
  val definitionBasePath: String = if (config.hasPath("schema.base_path")) config.getString("schema.base_path") else "https://sunbirddev.blob.core.windows.net/sunbird-content-dev/schemas/local"
  val schemaSupportVersionMap: Map[String, String] = if (config.hasPath("schema.supported_version")) config.getAnyRef("schema.supported_version").asInstanceOf[util.Map[String, String]].asScala.toMap else Map[String, String]()

  def getString(key: String, default: String): String = {
    if (config.hasPath(key)) config.getString(key) else default
  }
}