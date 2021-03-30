package org.wikimedia.analytics.refinery.job.refine

import org.apache.hadoop.fs.FileSystem
import org.apache.spark.sql.SparkSession
import org.wikimedia.analytics.refinery.core.LogHelper
import org.wikimedia.analytics.refinery.core.config._

import scala.util.matching.Regex
import scala.collection.immutable.ListMap

/**
  * A Refine job that applies a specially configured transform function
  * that will sanitize input dataset fields based on a sanitization
  * allow list.
  */
object RefineSanitize extends LogHelper with ConfigHelper
{
    // Config class for CLI argument parser using scopt.
    case class Config(
        allowlist_path   : String,
        salts_path       : Option[String] = None,
        keep_all_enabled : Boolean = false
    )

    object Config {
        // This is just used to ease generating help message with default values.
        // Required configs are set to dummy values.
        val default = Config("")

        // RefineSanitize accepts all Refine options plus some extras.
        val propertiesDoc = Refine.Config.propertiesDoc ++ ListMap(
            "allowlist_path" ->
                "Path to the sanitization allowlist file.",
            "salts_path" ->
                s"""Read the cryptographic salts for hashing of fields from this path.
               |Default: ${default.salts_path}""",
            "keep_all_enabled" ->
                s"""Whether or not to enable the use of the keep_all tag in the allowlist.
               |Default: ${default.keep_all_enabled}"""
        )

        val usage: String =
            """|Sanitize datasets into Hive tables using an allowlist.
               |
               |Given an input base path for the data and one for the allowlist, this job
               |will search all subdirectories for input partitions to sanitize. It will
               |interpret the allowlist and apply it to keep only the tables and fields
               |mentioned in it. Anything not explicitly allow listed will be nulled.
               |
               |Example:
               |  spark-submit --class org.wikimedia.analytics.refinery.job.RefineSanitize refinery-job.jar \
               |   # read configs out of this file
               |   --config_file                 /etc/refinery/refine/event_sanitize_job.properties \
               |   # Override and/or set other configs on the CLI
               |   --allowlist_path              /wmf/path/to/allowlist \
               |   --input_path                  /wmf/data/event \
               |   --output_path                 /user/mforns/sanitized' \
               |   --database                    mforns \
               |   --since                       24 \
               |"""
    }

    def main(args: Array[String]): Unit = {
        if (args.contains("--help")) {
            println(help(Config.usage, Config.propertiesDoc))
            sys.exit(0)
        }

        val spark = SparkSession.builder().enableHiveSupport().getOrCreate()
        // if not running in yarn, make spark log level quieter.
        if (spark.conf.get("spark.master") != "yarn") {
            spark.sparkContext.setLogLevel("WARN")
        }

        // Load RefineSanitize specific configs
        val sanitizeConfig = loadConfig(args)
        // Also load Refine configs
        val refineConfig = Refine.loadConfig(args)

        val allSucceeded = apply(spark)(
            sanitizeConfig.allowlist_path,
            sanitizeConfig.salts_path,
            sanitizeConfig.keep_all_enabled,
            refineConfig
        )

        // Exit with proper exit val if not running in YARN.
        if (spark.conf.get("spark.master") != "yarn") {
            sys.exit(if (allSucceeded) 0 else 1)
        }
    }

    def loadConfig(args: Array[String]): Config = {
        val config = try {
            configureArgs[Config] (args)
        } catch {
            case e: ConfigHelperException =>
                log.fatal (e.getMessage + ". Aborting.")
                sys.exit(1)
        }

        log.info("Loaded configuration:\n" + prettyPrint(config))
        config
    }

    /**
      * Apply sanitization to tables in Hive with the specified params.
      *
      * @param spark Spark session
      * @param salts_path Path to salts file
      * @param keep_all_enabled Whether to enable the use of the keep_all flag.
      * @param refineConfig Refine.Config
      * @return true if the sanitization succeeded, false otherwise.
      */
    def apply(spark: SparkSession = SparkSession.builder().enableHiveSupport().getOrCreate())(
        allowlist_path   : String,
        salts_path       : Option[String],
        keep_all_enabled : Boolean,
        refineConfig     : Refine.Config
    ): Boolean = {
        val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)

        val allowlist = SanitizeTransformation.loadAllowlist(fs)(allowlist_path, keep_all_enabled)
        log.debug(s"Loaded allowlist from $allowlist_path:\n" + allowlist)

        val hashingSalts = salts_path match {
            case Some(saltsPath) => SanitizeTransformation.loadHashingSalts(fs)(saltsPath)
            case None => Seq.empty
        }

        // Get a SanitizeTransform function based on allowlist and salts.
        val sanitize = SanitizeTransformation(
            allowlist,
            hashingSalts
        )

        // If --table_whitelist_regex is not explicitly configured,
        // use the value of all table keys in the allowlist to build table_whitelist_regex.
        // This prevents Refine to load RefineTargets for unwanted tables.
        val tableWhitelistRegex = Some(refineConfig.table_whitelist_regex.getOrElse(
            new Regex("^(" + allowlist.keys.mkString("|") + ")$")
        ))

        // Use Refine with the sanitization transform function to sanitize data.
        Refine(
            spark,
            refineConfig.copy(
                transform_functions = refineConfig.transform_functions :+ sanitize.asInstanceOf[Refine.TransformFunction],
                table_whitelist_regex = tableWhitelistRegex
            )
        )
    }
}
