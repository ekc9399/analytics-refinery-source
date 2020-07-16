package org.wikimedia.analytics.refinery.job.refine

import com.github.nscala_time.time.Imports.{DateTime, _}
import java.io.{BufferedReader, EOFException, InputStreamReader}

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, DataFrameReader, Row, SparkSession}
import org.joda.time.Hours
import org.joda.time.format.DateTimeFormatter
import org.wikimedia.analytics.refinery.core.{HivePartition, LogHelper}
import org.wikimedia.analytics.refinery.spark.sql.HiveExtensions._
import org.wikimedia.analytics.refinery.spark.sql.PartitionedDataFrame

import scala.util.control.Exception.allCatch
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}


/**
  * Represents a dataset input and output of a 'refine' job (AKA ETL).  This case class should
  * be used to hold state about an input path and an output HivePartition.  Done and failure
  * flags in the output path are used to indicate the status of a refine job.  If a failure
  * flag exists, some previous job failed.  If a done flag exists, some previous job succeeded.
  * The done flag will have the mtime of the input path directory written into it.  This
  * is used by the shouldRefine method to determine if the input source data has changed
  * since the doneFlag was previously written.  If it has, shouldRefine will return true.
  *
  * Note: 'refine' is not a well defined term.  In general it means a an ETL type job,
  * that takes data from one place, augements it, and outputs it elsewhere.  It usually
  * is expected to be a 1 to 1 mapping of input and output paths, i.e. every input path
  * has an output path.  As such, this probably shouldn't be used for aggregation
  * type jobs, where multiple inputs are mapped to one output.
  *
  * @param spark                SparkSession
  * @param inputPath            Full input partition path
  * @param partition            HivePartition
  * @param schemaLoader         A SparkSchemaLoader that knows what the schema of this RefineTarget
  *                             is.  The default is to not provide an explicit schema, which
  *                             will rely on the spark DataFrameReader to infer the schema
  *                             from the inputPath data.  This works well if the data is
  *                             e.g. Parquet, but only semi-well if the data is JSON.
  *                             You should provide an implemented SparkSchemaLoader
  *                             for JSON data whenever you can.  The schemaLoader
  *                             is only used when you call inputDataFrame, so if you
  *                             only use this class to find targets (but not read them),
  *                             you can omit providing this.
  * @param inputFormatOpt       If given, this will be used as the input format when reading data.
  *                             Should be one of "text", "json" "json_sequence" or "parquet".
  *                             If not given, the input format will be inferred from the data.
  * @param doneFlag             Name of file that should be written upon success of
  *                             the refine job.  This can be created by calling
  *                             the writeDoneFlag method.
  * @param failureFlag          Name of file that should be written upon failure of
  *                             the refine job run.  This can be created by calling
  *
  */
case class RefineTarget(
    spark: SparkSession,
    inputPath: Path,
    partition: HivePartition,
    schemaLoader: SparkSchemaLoader = ExplicitSchemaLoader(None),
    inputFormatOpt: Option[String] = None,
    doneFlag: String    = "_REFINED",
    failureFlag: String = "_REFINE_FAILED"
) extends LogHelper {
    /**
      * The FileSystem that Spark is operating in
      */
    val fs: FileSystem = FileSystem.get(spark.sparkContext.hadoopConfiguration)

    /**
      * The value of inputFormatOpt if provided, else the value returned by inferInputFormat
      */
    lazy val inputFormat: String = inputFormatOpt.getOrElse(inferInputFormat)

    /**
      * The Spark schema for this target.  This is loaded
      * using the provided SparkSchemaLoader schemaLoader.
      */
    lazy val schema: Option[StructType] = schemaLoader.loadSchema(this)

    /**
      * Easy access to the fully qualified Hive table name.
      */
    val tableName: String = partition.tableName

    /**
      * Easy access to the hive partition path, AKA the output destination path
      */
    val outputPath = new Path(partition.path)

    /**
      * Path to doneFlag in hive table partition output path
      */
    val doneFlagPath = new Path(s"$outputPath/$doneFlag")

    /**
      * Path to doneFlag in hive table partition output path
      */
    val failureFlagPath = new Path(s"$outputPath/$failureFlag")

    /**
      * Number of records successfully refined for this RefineTarget.
      * This should be set using the success method.
      */
    var recordCount: Long = -1

    /**
      * The mtime of the inputPath at the time this RefineTarget is instantiated.
      * Caching this allows us to use the earliest mtime possible to store in doneFlag,
      * in case the inputPath changes while this target is being refined.
      */
    private val inputMTimeCached: Option[DateTime] = inputMTime()

    /**
      * True if the inputPath exists
      * @return
      */
    def inputExists(): Boolean = fs.exists(inputPath)

    /**
      * True if the outputPath exists
      * @return
      */
    def outputExists(): Boolean = fs.exists(outputPath)

    /**
      * True if the Hive table for this target exists
      * @return
      */
    def tableExists(): Boolean = {
        allCatch.opt(spark.table(tableName)) match {
            case Some(_) => true
            case _       => false
        }
    }

    /**
      * True if the outputPath/doneFlag exists
      * @return
      */
    def doneFlagExists(): Boolean = fs.exists(doneFlagPath)

    /**
      * True if the outputPath/failureFlag exists
      * @return
      */
    def failureFlagExists(): Boolean = fs.exists(failureFlagPath)


    /**
      * Returns the mtime Long timestamp of inputPath.  inputPath's
      * mtime will change if it or any of its direct files change.
      * It will not change if a content in a subdirectory changes.
      * @return
      */
    def inputMTime(): Option[DateTime] = {
        if (inputExists()) {
            Some(new DateTime(fs.getFileStatus(inputPath).getModificationTime))
        }
        else
            None
    }


    /**
      * Reads a Long timestamp out of path and returns a new Option[DateTime].
      * If the file is empty (issue when writing), return None.
      *
      * @param path File path to read the DateTime from
      * @return Option[DateTime]
      */
    private def readMTimeFromFile(path: Path): Option[DateTime] = {
        val inStream = fs.open(path)
        val mtime = try {
            Some(new DateTime(inStream.readUTF()))
        } catch {
            case e: EOFException => None
        }
        inStream.close()
        mtime
    }


    /**
      * Writes this RefineTarget's mtime to path
      * @param path
      */
    private def writeMTimeToFile(path: Path): Unit = {
        val mtime = inputMTimeCached.getOrElse(
            throw new RuntimeException(
                s"Cannot write mtime to flag file, input mod time was not obtained when $this was " +
                    s"instantiated, probably because it did not exist. This should not happen"
            )
        )

        val outStream = fs.create(path)
        outStream.writeUTF(mtime.toString)
        outStream.close()
    }


    /**
      * Write out doneFlag file for this output target partition
      *
      * This saves the modification timestamp of the inputPath as it when this target was
      * instantiated.  This will allow later comparison of the contents of doneFlag with the
      * inputPath modification time.  If they are different, the user might decide to rerun
      * the refine job for this target, perhaps assuming that there is new
      * data in inputPath.  Note that inputPath directory mod time only changes if
      * its direct content changes, it will not change if something in a subdirectory
      * below it changes.
      */
    def writeDoneFlag(): Unit = {
        writeMTimeToFile(doneFlagPath)
    }


    /**
      * Write out failureFlag file for this output target partition
      *
      * This saves the modification timestamp of the inputPath as it when this target was
      * instantiated.  This will allow later comparison of the contents of failureFlag with the
      * inputPath modification time.
      */
    def writeFailureFlag(): Unit = {
        writeMTimeToFile(failureFlagPath)
    }


    /**
      * Reads the Long timestamp as a DateTime out of the doneFlag
      * If the done flag does not exist or the timestamp can not be read,
      * return None.
      * @return
      */
    def doneFlagMTime(): Option[DateTime] = {
        if (doneFlagExists())
            readMTimeFromFile(doneFlagPath)
        else
            None
    }


    /**
      * Reads the Long timestamp as a DateTime out of the failureFlag
      * If the failure flag does not exist or the timestamp can not be read,
      * return None.
      * @return
      */
    def failureFlagMTime(): Option[DateTime] = {
        if (failureFlagExists())
            readMTimeFromFile(failureFlagPath)
        else
            None
    }


    /**
      * This target needs refined if:
      *
      * - The output doesn't exist OR
      * - The output doneFlag doesn't exist or it does and the input mtime has changed OR
      * - The output failureFlag doesn't exist, or it does and we want to ignore previous
      *   failures or the input mtime has changed.
      *
      *
      * The input's mtime has changed if it does not equal the timestamp in the output doneFlag
      * or failureFlag file, meaning that something has changed in the inputPath since the last
      * time the flag file was written.
      *
      * @param ignoreFailureFlag
      * @param ignoreDoneFlag
      * @return
      */
    private def shouldRefine(
         ignoreFailureFlag: Boolean,
         ignoreDoneFlag: Boolean
    ): Boolean = {

        // This could be written and returned as a single boolean conditional statement,
        // keeping track of possible states was confusing.  This is clearer.

        // If the outputExists, check for existent status flag files
        if (outputExists()) {
            // If doneFlag exists, and the input mtime has changed, then we need to refine.
            if (doneFlagExists()) {
                return ignoreDoneFlag || inputMTimeCached != doneFlagMTime()
            }
            // Else if the failure flag exists, we need to refine if
            // we are ignoring the failure flag, or if the input mtime has changed.
            else if (failureFlagExists()) {
                return ignoreFailureFlag || inputMTimeCached != failureFlagMTime()
            }
        }

        // If none of the above conditions return, we will refine.
        true
    }


    /**
      * Given a RefineTarget, and option whitelist regex and blacklist regex,
      * this returns true if the RefineTarget should be refined, based on regex matching and
      * on output existence and doneFlag content.
      *
      * Both tableWhitelistRegex and tableBlacklistRegex are applied if given.
      * If a table matches both regexes, it will blacklisted.
      *
      * @param tableWhitelistRegex Option[Regex]
      * @param tableBlacklistRegex Option[Regex]
      * @param ignoreFailureFlag
      * @param ignoreDoneFlag
      * @return
      */
    def shouldRefine(
        tableWhitelistRegex: Option[Regex] = None,
        tableBlacklistRegex: Option[Regex] = None,
        ignoreFailureFlag  : Boolean = false,
        ignoreDoneFlag     : Boolean = false
    ): Boolean = {
        // Filter for targets that will refine to tables that match the whitelist
        if (tableWhitelistRegex.isDefined &&
            !RefineTarget.regexMatches(partition.table, tableWhitelistRegex.get)
        ) {
            log.debug(
                s"$this table ${partition.table} does not match table whitelist regex " +
                    s"${tableWhitelistRegex.get}', skipping."
            )
            return false
        }

        // Filter out targets that will refine to tables that match the blacklist
        if (tableBlacklistRegex.isDefined &&
            RefineTarget.regexMatches(partition.table, tableBlacklistRegex.get)
        ) {
            log.debug(
                s"$this table ${partition.table} matches table blacklist regex " +
                    s"'${tableBlacklistRegex.get}', skipping."
            )
            return false
        }

        // Finally filter for those that need to be refined (have new data, or need re-refined).
        val shouldRefineThis = shouldRefine(ignoreFailureFlag, ignoreDoneFlag)

        // If this shouldn't be refined, output some debug statements about why.
        if (!shouldRefineThis) {
            if (failureFlagExists) {
                log.warn(
                    s"$this previously failed refinement and does not have new data since the " +
                        s"last refine at ${failureFlagMTime().getOrElse("_unknown_")}, skipping."
                )
            }
            else if (doneFlagExists) {
                log.debug(
                    s"$this does not have new data since the last successful refine at " +
                        s"${doneFlagMTime().getOrElse("_unknown_")}, skipping."
                )
            }
        }

        shouldRefineThis
    }

    /**
      * Reads the first bytes in inputPath as chars, and examines them to
      * infer the file format.  This will only work if the first file
      * is Parquet, JSON text, or SequenceFile with JSON string values.
      * If the directory is empty, this will return "empty".
      *
      * Kinda hacky, but should work! :)
      *
      * @return One of "parquet", "sequence_json", "json", "empty", or "text".
      *
      */
    def inferInputFormat: String = {
        // Get a list of all data file Paths at inputPath.
        // If inputPath is a file, this will just be inputPath, else it will
        // Be the first file that does not start with an underscore and also
        // has a non zero size.
        val inputDataFiles = fs.listStatus(inputPath)
             .filter(f => !f.getPath.getName.startsWith("_") && f.getLen > 0)
             .map(_.getPath)

        // If we didn't find any data files at inputPath, then return "empty".
        if (inputDataFiles.isEmpty) return "empty"

        // Read the first few characters out of the first data file.
        val buffer = new Array[Char](3)
        val in = new BufferedReader(new InputStreamReader(fs.open(inputDataFiles.head)))
        val bytesRead = in.read(buffer, 0, buffer.length)
        in.close()

        // Return empty we can't read any bytes from the first data file.
        // This probably shouldn't happen, since we filtered where f.getLen > 0,
        // but is good just in case.
        if (bytesRead <= 0) return "empty"

        // Infer the format of inputPath's data based on the first few characters
        // in the first data file.
        buffer match {
            case Array('P','A','R')                        => "parquet"
            case Array('S','E','Q')                        => "sequence_json"
            case _ if buffer(0) == '{' || buffer(0) == '[' => "json"
            case _                                         => "text"
        }
    }

    /**
      * Reads input path into a DataFrame.
      *
      * schemaLoader will be used to load the schema for this RefineTarget.
      * If schemaLoader returns Some, the schema will be used when reading the data.
      * Otherwise schema will be inferred from the data by the Spark DataFrameReader.
      * Note that if inputFormat is "text", schemaLoader should not return a schema
      * unless it is a single text column schema.  Returning a schema for "text" data
      * will result in an AssertionError when reading the DataFrame, as the data will
      * not match the schema.
      *
      * @param dfReaderOptions Map[String, String]
      *     Extra Spark DataFrameReader options to use

      * @param useMergedSchemaForRead
      *     If true, the schema loaded by schemaLoader will be merged (and normalized)
      *     with the target's Hive table before reading the input data.  This might
      *     be useful if the Hive table has extra fields (from previous schema evolution)
      *     that are not present in the loaded schema but may be present in some of the
      *     input data.
      *     Generally, it is not a good idea to use this option, but it may be necessary
      *     for input data that has not used good backwards compatibility constraints
      *     when changing schemas.
      *
      * @return
      */
    def inputDataFrame(
        dfReaderOptions: Map[String, String] = Map(),
        useMergedSchemaForRead: Boolean = false
    ): DataFrame = {

        val schemaForRead = if (schema.isDefined && useMergedSchemaForRead && tableExists) {
            // TODO: This is being deprecated as all new versioned schemas should
            // be backwards compatible.  Once we can be sure they are, we can remove
            // this conditional logic.  https://phabricator.wikimedia.org/T255818
            //
            // If useMergedSchemaForRead and the target Hive table exists, then
            // merge the input schema with Hive schema, keeping the casing on top
            // level field names where possible (since this schema will be used to
            // load JSON data). This will ensure that other events in the file
            // that have fields that Hive has, but that the loaded schema
            // doesn't have, will still be read. Ideally this wouldn't matter,
            // since different schema versions should all be backwards compatible,
            // but is is possible that in legacy EventLogging schemas someone has
            // removed a field from a latest schema. Without merging, data of an older
            // version that have now removed fields would lose these fields.  Hive's schema
            // should have been evolved in a way that it has all fields ever seen in any
            // schema version, so merging these together ensure that those fields still are read.
            // See also:
            // - https://phabricator.wikimedia.org/T227088
            // - https://phabricator.wikimedia.org/T226219
            val mergedSchema = spark.table(tableName).schema.merge(
                schema.get,
                lowerCaseTopLevel=false
            )
            log.debug(
                s"Merged schema for $this with Hive table schema" +
                s"before reading input data:\n${mergedSchema.treeString}"
            )
            Some(mergedSchema)
        } else {
            schema
        }

        val dfReader: DataFrameReader = { schemaForRead match {
            case None    => spark.read
            // If we're loading from textual data, then assume that we will want all fields
            // in the schema to be nullable (AKA not required).
            case Some(s) => inputFormat match {
                case "text" =>
                    spark.read.schema(s.makeNullable())
                case "json" | "sequence_json" =>
                    // By default read JSON data with a schema in FAILFAST mode.
                    // This makes the read fail if the input data is not cast-able to the schema.
                    // This can be overidden by user provided options.
                    spark.read.schema(s.makeNullable()).option("mode", "FAILFAST")
                case _ =>
                    spark.read.schema(s)
            }
        }}.options(dfReaderOptions) // Apply any user supplied DataFrameReader options

        // import spark implicits for Dataset/DataFrame conversion
        import spark.implicits._

        // Read inputPath either as text, Parquet, JSON, or SequenceFile JSON, based on input format
        inputFormat match {
            case "text" | "json" | "parquet" => dfReader.format(inputFormat).load(inputPath.toString)

            // Expect data to be SequenceFiles with JSON strings as values.
            // (sequenceFileJson is defined in refinery HiveExtensions.)
            case "sequence_json" => dfReader.sequenceFileJson(inputPath.toString, spark)

            // If there is no data at inputPath, then we either want a schema-less emptyDataFrame,
            // or an empty DataFrame with schema
            case "empty"         => schema match {
                case None    => spark.emptyDataFrame
                case Some(s) => spark.createDataFrame(spark.sparkContext.emptyRDD[Row], s)
            }
        }
    }

    /**
      * Helper wrapper around inputDataFrame that returns a PartitionedDataFrame
      * with inputDataFrame and its HivePartition.
      *
      * @param dfReaderOptions Map[String, String] Extra Spark DataFrameReader options to use
      * @return
      */
    def inputPartitionedDataFrame(
        dfReaderOptions: Map[String, String] = Map(),
        mergeWithHiveSchema: Boolean = false
    ): PartitionedDataFrame = {
        new PartitionedDataFrame(
            inputDataFrame(dfReaderOptions, mergeWithHiveSchema),
            partition
        )
    }

    /**
      * Gets the first line as a String out of the inputPath without converting to a DataFrame.
      * This reads only the first line of data, not all the data in the inputPath.
      * @return
      */
    def firstLine(): Option[String] = {
        // Get the first line out of the inputPath
        inputFormat match {
            case "sequence_json" =>
                spark.sparkContext.sequenceFile[Long, String](inputPath.toString)
                     .map(t => t._2).take(1).headOption

            case "json" =>
                spark.sparkContext.textFile(inputPath.toString).take(1).headOption

            case "empty" =>
                None

            case _ =>
                throw new RuntimeException(
                    s"Cannot cannot read first line of target $this with format $inputFormat. " +
                        "Must be one either 'sequence_json' or 'json'"
                    )
        }
    }


    /**
      * Returns a Failure with e wrapped in a new more descriptive Exception
      * @param e Original exception that caused this failure
      * @return
      */
    def failure(e: Exception): Try[RefineTarget] = {
        Failure(RefineTargetException(
            this, s"Failed refinement of $this. Original exception: $e", e
        ))
    }


    /**
      * Returns Success(this) of this RefineTarget
      * @return
      */
    def success(recordCount: Long): Try[RefineTarget] = {
        this.recordCount = recordCount
        Success(this)
    }


    override def toString: String = {
        s"$inputPath -> $partition"
    }

}


object RefineTarget {

    /**
      * Helper constructor to create a RefineTarget inferring the output HivePartition
      * using a full outputPath to the partition location.
      *
      * @param spark
      * @param inputPath
      * @param outputPath
      *     The full path to the Hive style partition.
      *     This assumes that the Hive database and table name are in the directories
      *     directly above the first Hive style partition directory.  E.g in
      *     /wmf/data/event/mediawiki_revision_create/datacenter=eqiad/year=2020/month=6/day=18/hour=0
      *     database=event, table=mediawiki_revision_create, and the partitions are the following
      *     directories.
      * @param schemaLoader
      * @return
      */
    def apply(
        spark: SparkSession,
        inputPath: String,
        outputPath: String,
        schemaLoader: SparkSchemaLoader
    ): RefineTarget = {
        new RefineTarget(
            spark,
            new Path(inputPath),
            HivePartition(outputPath),
            schemaLoader
        )
    }

    /**
      * Finds RefineTargets with existent input partition paths between sinceDateTime and untilDateTime.
      * The table and partitions are extracted from inputPath by combining inputPathDateTimeFormatter
      * and inputPathRegex.
      *
      * inputPathDateTimeFormatter will be used to construct the expected inputPath for each
      * input partition directory between sinceDateTime and untilDateTime.  E.g. a formatter
      * with a format of "'hourly'/yyyy/MM/dd/HH" will look for existent inputPaths
      * for every hour in the provided time period, like
      *     $baseInputPath/subdir1/hourly/2017/07/26/00,
      *     $baseInputPath/subdir1/hourly/2017/07/26/01,
      *     $baseInputPath/subdir2/hourly/2017/07/26/00,
      *     $baseInputPath/subdir2/hourly/2017/07/26/01,
      * etc.
      *
      * inputPathRegex is expected to capture named groups that include "table" and any other
      * partition keys.  inputPathRegex's capture groups must contain one named "table".
      * E.g. new Regex(
      *     "(eqiad|codfw)_(.+)/hourly/\\d{4}/\\d{2}/\\d{2}/\\d{2}",
      *     "datacenter", "table", "year", "month", "day", "hour"
      *
      * and an inputPath of
      *     $baseInputPath/eqiad_mediawiki_revision-create/2017/07/26/01
      *
      * Will construct a RefineTarget with table "mediawiki_revision_create" (hyphens are converted
      * to underscores) and partitions datacenter="eqiad",year=2017,month=07,day=26,hour=01
      *
      *
      * @param spark                        SparkSession
      *
      * @param baseInputPath                Path to base input datasets.  Each subdirectory
      *                                     is assumed to be a unique dataset with individual
      *                                     partitions.  Every subdirectory's partition
      *                                     paths here must be compatible with the provided
      *                                     values of inputPathDateTimeFormatter and inputPathRegex.
      *
      * @param baseTableLocationPath        Path to directory where Hive table data will be stored.
      *                                     $baseTableLocationPath/$table will be the value of the
      *                                     external Hive table's LOCATION.
      *
      * @param databaseName                 Hive database name
      *
      * @param inputPathDateTimeFormatter   Formatter used to construct input partition paths
      *                                     in the given time range.
      *
      * @param inputPathRegex               Regex used to extract table name and partition
      *                                     information.
      *
      * @param sinceDateTime                Start date time to look for input partitions.
      *
      * @param untilDateTime                End date time to look for input partitions.
      *                                     Defaults to DateTime.now
      *
      * @param schemaLoader                 Will be used to get the DataFrame with a specific schema.
      *
      * @return
      */
    def find(
        spark: SparkSession,
        baseInputPath: Path,
        baseTableLocationPath: Path,
        databaseName: String,
        inputPathDateTimeFormatter: DateTimeFormatter,
        inputPathRegex: Regex,
        sinceDateTime: DateTime,
        untilDateTime: DateTime = DateTime.now,
        schemaLoader: SparkSchemaLoader = ExplicitSchemaLoader(None)
    ): Seq[RefineTarget] = {
        val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
        val inputDatasetPaths = subdirectoryPaths(fs, baseInputPath)

        // Map all partitions in each inputPaths since sinceDateTime to RefineTargets
        inputDatasetPaths.flatMap { inputDatasetPath =>
            // Get all possible input partition paths for all directories in inputDatasetPath
            // between sinceDateTime and untilDateTime.
            // This will include all possible partition paths in that time range, even if that path
            // does not actually exist.
            val pastPartitionPaths = partitionPathsSince(
                inputDatasetPath.toString,
                inputPathDateTimeFormatter,
                sinceDateTime,
                untilDateTime
            ).filter { case inputPathRegex => true }

            // Convert each possible partition input path into a possible RefineTarget for refinement.
            pastPartitionPaths.map(partitionPath => {
                // Any capturedKeys other than table are expected to be partition key=values.
                val partition = HivePartition(
                    databaseName,
                    baseTableLocationPath.toString,
                    partitionPath.toString,
                    inputPathRegex
                )

                RefineTarget(
                    spark,
                    partitionPath,
                    partition,
                    schemaLoader
                )
            })
            // We only care about input partition paths that actually exist,
            // so filter out those that don't.
            .filter(_.inputExists())
        }
    }

    /**
      * Retruns a Seq of all directory Paths in a directory.
      * @param fs           Hadoop FileSystem
      * @param inDirectory  directory Path in which to look for subdirectories
      * @return
      */
    def subdirectoryPaths(fs: FileSystem, inDirectory: Path): Seq[Path] = {
        fs.listStatus(inDirectory).filter(_.isDirectory).map(_.getPath)
    }


    /**
      * Given 2 DateTimes, this generates a Seq of DateTimes representing all hours
      * between since d1 (inclusive) and d2 (exclusive).  E.g.
      *     DateTime.now -> 2017-08-10T21:42:32.820Z
      *
      *     hoursInBetween(DateTime.now - 2.hours, DateTime.now) ->
      *         Seq(2017-08-10T19:00:00.000Z, 2017-08-10T20:00:00.000Z)
      *
      * In the above example, the current hour is 21, and this function returns
      * the previous two hours.
      *
      * @param d1   sinceDateTime
      * @param d2   untilDateTime
      * @return
      */
    def hoursInBetween(d1: DateTime, d2: DateTime): Seq[DateTime] = {
        val oldestHour = new DateTime(d1, DateTimeZone.UTC).hourOfDay.roundCeilingCopy
        val youngestHour = new DateTime(d2, DateTimeZone.UTC).hourOfDay.roundFloorCopy

        for (h <- 0 until Hours.hoursBetween(oldestHour, youngestHour).getHours) yield {
            oldestHour + h.hours
        }
    }


    /**
      * Given a DateTimeFormatter and 2 DateTimes, this will generate
      * a Seq of Paths for every distinct result of fmt.print(hour) prefixed
      * with pathPrefix.  If your date formatter generates the same
      * path for multiple hours, only one of those paths will be included
      * in the result.  This way, you can still generate a list more granular partitions, if
      * your data happens to be partitioned at a more granular time bucketing than hourly.
      *
      * @param pathPrefix   Prefix to prepend to every generated partition path
      * @param fmt          Date formatter to use to extract partition paths from hours
      *                     between d1 and d2
      * @param d1           sinceDateTime
      * @param d2           untilDateTime,  Defaults to DateTime.now
      * @return
      */
    def partitionPathsSince(
        pathPrefix: String,
        fmt: DateTimeFormatter,
        d1: DateTime,
        d2: DateTime = DateTime.now
    ): Seq[Path] = {
        hoursInBetween(d1, d2)
            .map(hour => new Path(pathPrefix + "/" + fmt.print(hour)))
            .distinct
    }

    /**
      * Returns true of s matches r, else false.
      * @param s    String to match
      * @param r    Regex
      * @return
      */
    def regexMatches(s: String, r: Regex): Boolean = {
        s match {
            case r(_*) => true
            case _     => false
        }
    }
}


/**
  * Exception wrapper used to retrieve the RefineTarget instance from a Failure instance.
  * @param target   RefineTarget
  * @param message  exception message
  * @param cause    Original Exception
  */
case class RefineTargetException(
    target: RefineTarget,
    message: String = "",
    cause: Throwable = None.orNull
) extends Exception(message, cause) { }


/**
  * Abstract trait.  Given a RefineTarget, this loadSchema will inspect it and return a
  * StructType Spark schema.
  */
trait SparkSchemaLoader {
    def loadSchema(target: RefineTarget): Option[StructType]
}


/**
  * This can be used to provide an explicit schema directly to RefineTarget rather than
  * allowing it to infer the schema when loading a DataFrame.
  * @param schema
  */
case class ExplicitSchemaLoader(schema: Option[StructType]) extends SparkSchemaLoader {
    def loadSchema(target: RefineTarget): Option[StructType] = schema
}
