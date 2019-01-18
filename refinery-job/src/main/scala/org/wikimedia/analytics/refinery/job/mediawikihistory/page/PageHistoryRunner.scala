package org.wikimedia.analytics.refinery.job.mediawikihistory.page

import org.apache.spark.sql.SparkSession
import org.wikimedia.analytics.refinery.spark.utils.{MapAccumulator, StatsHelper}


/**
  * This class defines the functions for the page history reconstruction process.
  * It delegates the reconstruction part of it's process to the
  * [[PageHistoryBuilder]] class.
  *
  * The [[run]] function loads [[PageEvent]] and [[PageState]] RDDs from raw path
  * using [[PageEventBuilder]] utilities. It then calls
  * [[PageHistoryBuilder.run]] to partition the RDDs and rebuild history.
  *
  * It finally writes the resulting [[PageState]] data in parquet format.
  *
  * Note: You can have errors output as well by providing
  * errorsPath to the [[run]] function.
  */
class PageHistoryRunner(
                         val spark: SparkSession,
                         val statsAccumulator: Option[MapAccumulator[String, Long]],
                         numPartitions: Int
                       ) extends StatsHelper with Serializable {

  import org.apache.spark.sql.SaveMode
  import com.databricks.spark.avro._
  import org.apache.log4j.Logger
  import org.apache.spark.sql.Row
  import org.apache.spark.sql.types._
  import org.wikimedia.analytics.refinery.core.TimestampHelpers


  @transient
  lazy val log: Logger = Logger.getLogger(this.getClass)

  val METRIC_LOCALIZED_NAMESPACES = "pageHistory.localizedNamespaces"
  val METRIC_EVENTS_PARSING_OK = "pageHistory.eventsParsing.OK"
  val METRIC_EVENTS_PARSING_KO = "pageHistory.eventsParsing.KO"
  val METRIC_INITIAL_STATES = "pageHistory.initialStates"
  val METRIC_WRITTEN_ROWS = "pageHistory.writtenRows"

  /**
    * Extract and clean [[PageEvent]] and [[PageState]] RDDs,
    * then launch the reconstruction and
    * writes the results (and potentially the errors).
    *
    * @param wikiConstraint The wiki database names on which to execute the job (empty for all wikis)
    * @param loggingDataPath The path of the logging data (avro files partitioned by wiki_db)
    * @param pageDataPath The path of the page data (avro files partitioned by wiki_db)
    * @param revisionDataPath The path of the revision data (avro files partitioned by wiki_db)
    * @param namespacesPath The path of the namespaces data (CSV file)
    * @param outputPath The path to output the reconstructed page history (parquet files)
    * @param errorsPathOption An optional path to output errors (csv files) if defined
    */
  def run(
           wikiConstraint: Seq[String],
           loggingDataPath: String,
           pageDataPath: String,
           revisionDataPath: String,
           namespacesPath: String,
           outputPath: String,
           errorsPathOption: Option[String]
  ): Unit = {

    log.info(s"Page history jobs starting")

    //***********************************
    // Prepare page events and states RDDs
    //***********************************

    // Work with 2 times more partitions that expected for file production
    spark.sql("SET spark.sql.shuffle.partitions=" + 4 * numPartitions)

    val loggingDf = spark.read.avro(loggingDataPath)
    loggingDf.createOrReplaceTempView("logging")
    spark.table("logging")

    val pageDf = spark.read.avro(pageDataPath)
    pageDf.createOrReplaceTempView("page")
    spark.table("page")

    val revisionDf = spark.read.avro(revisionDataPath)
    revisionDf.createOrReplaceTempView("revision")
    spark.table("revision")

    val wikiClause = if (wikiConstraint.isEmpty) "" else {
      "AND wiki_db IN (" + wikiConstraint.map(w => s"'$w'").mkString(", ") + ")\n"
    }

    val namespacesCsvSchema = StructType(
        Seq(StructField("domain", StringType, nullable = false),
            StructField("wiki_db", StringType, nullable = false),
            StructField("namespace", IntegerType, nullable = false),
            StructField("namespace_canonical_name",
                        StringType,
                        nullable = false),
            StructField("namespace_localized_name",
                        StringType,
                        nullable = false),
            StructField("is_content", IntegerType, nullable = false)))

    val namespaces = spark.read
      .schema(namespacesCsvSchema)
      .csv(namespacesPath)
      .rdd
      .map(r => {
        val wikiDb = r.getString(1)
        addOptionalStat(s"$wikiDb.$METRIC_LOCALIZED_NAMESPACES", 1)
        (
          wikiDb,
          r.getInt(2),
          if (r.isNullAt(3)) "" else r.getString(3),
          if (r.isNullAt(4)) "" else r.getString(4),
          r.getInt(5)
        )
      }).collect()

    val canonicalNamespaceMap = namespaces
      .map(t => (t._1, PageEventBuilder.normalizeTitle(t._3)) -> t._2)
      .toMap
    val localizedNamespaceMap = namespaces
      .map(t => (t._1, PageEventBuilder.normalizeTitle(t._4)) -> t._2)
      .toMap

    val isContentNamespaceMap = namespaces
      .map(t => (t._1, t._2) -> (t._5 == 1))
      .toMap.withDefaultValue(false)

    val pageEventBuilder = new PageEventBuilder(
      canonicalNamespaceMap,
      localizedNamespaceMap,
      isContentNamespaceMap
    )
    val parsedPageEvents = spark.sql(
      // NOTE: The following fields are sanitized according to log_deleted on cloud dbs:
      //  &1: log_action, log_namespace, log_title, log_page
      //  &2: log_comment_id, log_comment
      //  &4: log_user_text, log_actor
      //  log_deleted is not null or 0: log_params
      s"""
  SELECT
    log_type,
    log_action,
    log_page,
    log_timestamp,
    CAST(log_user AS BIGINT) AS log_user,
    log_title,
    log_params,
    log_namespace,
    wiki_db
  FROM logging l
  WHERE ((log_type = 'move')
          OR (log_type = 'delete'
              AND log_action IN ('delete', 'restore')))
      $wikiClause
  GROUP BY -- Grouping by to enforce expected partitioning
    log_type,
    log_action,
    log_page,
    log_timestamp,
    log_user,
    log_title,
    log_params,
    log_namespace,
    wiki_db
      """)
      .rdd
      .map(row =>
      {
        val pageEvent = {
          if (row.getString(0) == "move") pageEventBuilder.buildMovePageEvent(row)
          else pageEventBuilder.buildSimplePageEvent(row)
        }
        val metricName = if (pageEvent.parsingErrors.isEmpty) METRIC_EVENTS_PARSING_OK else METRIC_EVENTS_PARSING_KO
        addOptionalStat(s"${pageEvent.wikiDb}.$metricName", 1)
        pageEvent
      })

    val pageEvents = parsedPageEvents.filter(_.parsingErrors.isEmpty).cache()

    val pageStates = spark.sql(
      s"""
  SELECT
    page_id,
    rev.rev_timestamp,
    page_title,
    page_namespace,
    rev2.rev_user,
    page.wiki_db,
    page_is_redirect
  FROM page
    INNER JOIN (
      -- crazy but true: there are multiple revisions with rev_parent_id = 0 for the same page
      SELECT
        min(rev_timestamp) as rev_timestamp,
        rev_page,
        wiki_db as wiki_db_rev
      FROM revision
      WHERE TRUE
        $wikiClause
      GROUP BY
        rev_page,
        wiki_db
    ) rev
      ON page_id = rev_page
        AND page.wiki_db = rev.wiki_db_rev
    INNER JOIN (
      SELECT
        rev_page,
        wiki_db as wiki_db_rev2,
        rev_timestamp,
        -- TODO: this is null 0 if the user is anonymous but also null if rev_deleted&4, need to verify that's ok
        rev_user
      FROM revision r
      WHERE TRUE
        $wikiClause
    ) rev2
      ON rev.rev_page = rev2.rev_page
        AND rev.wiki_db_rev = rev2.wiki_db_rev2
        AND rev.rev_timestamp = rev2.rev_timestamp
  WHERE page.page_title IS NOT NULL -- Used for Graph partitioning, not accepting undefined
    $wikiClause
  GROUP BY -- Grouping by to enforce expected partitioning
    page_id,
    rev.rev_timestamp,
    page_title,
    page_namespace,
    rev2.rev_user,
    page.wiki_db,
    page_is_redirect
      """)
      .rdd
      .map(row => {
        val wikiDb = row.getString(5)
        val title = row.getString(2)
        val namespace = row.getInt(3)
        val isContentNamespace = isContentNamespaceMap((wikiDb, namespace))
        addOptionalStat(s"$wikiDb.$METRIC_INITIAL_STATES", 1L)
        new PageState(
          pageId = if (row.isNullAt(0)) None else Some(row.getLong(0)),
          pageCreationTimestamp = TimestampHelpers.makeMediawikiTimestamp(row.getString(1)),
          pageFirstEditTimestamp = TimestampHelpers.makeMediawikiTimestamp(row.getString(1)),
          titleHistorical = title,
          title = title,
          namespaceHistorical = namespace,
          namespaceIsContentHistorical = isContentNamespace,
          namespace = namespace,
          namespaceIsContent = isContentNamespace,
          isRedirect = Some(row.getBoolean(6)),
          isDeleted = false,
          startTimestamp = TimestampHelpers.makeMediawikiTimestamp(row.getString(1)),
          endTimestamp = None,
          causedByEventType = "create",
          causedByUserId = if (row.isNullAt(4)) None else Some(row.getLong(4)),
          wikiDb = wikiDb
        )
      })
      .cache()

    log.info(s"Page history data defined, starting reconstruction")


    //***********************************
    // Reconstruct page history
    //***********************************

    val pageHistoryBuilder = new PageHistoryBuilder(spark, statsAccumulator)
    val (pageHistoryRdd, unmatchedEvents) = pageHistoryBuilder.run(pageEvents, pageStates)

    log.info(s"Page history reconstruction done, writing results, errors and stats")


    //***********************************
    // Write results
    //***********************************

    // Write history
    spark.createDataFrame(pageHistoryRdd.map(state => {
          addOptionalStat(s"${state.wikiDb}.$METRIC_WRITTEN_ROWS", 1)
          state.toRow
        }), PageState.schema)
      .repartition(numPartitions)
      .write
      .mode(SaveMode.Overwrite)
      .parquet(outputPath)
    log.info(s"Page history reconstruction results written")

    //***********************************
    // Optionally Write errors
    //***********************************
    errorsPathOption.foreach(errorsPath => {
      val parsingErrorEvents = parsedPageEvents.filter(_.parsingErrors.nonEmpty)
      val errorDf = spark.createDataFrame(
        parsingErrorEvents.map(e => Row(e.wikiDb, "parsing", e.toString))
          .union(unmatchedEvents.map(e => Row(e.wikiDb, "matching", e.toString))
          ),
        StructType(Seq(
          StructField("wiki_db", StringType, nullable = false),
          StructField("error_type", StringType, nullable = false),
          StructField("event", StringType, nullable = false)
        ))
      )
      errorDf.repartition(1)
        .write
        .mode(SaveMode.Overwrite)
        .format("csv")
        .option("sep", "\t")
        .save(errorsPath)
      log.info(s"Page history reconstruction errors written")
    })

    log.info(s"Page history jobs done")
  }

}
