package org.wikimedia.analytics.refinery.spark.sql

import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.scalatest.{FlatSpec, Matchers}
import org.wikimedia.analytics.refinery.spark.sql.HiveExtensions._

class TestHiveExtensions extends FlatSpec with Matchers with DataFrameSuiteBase {

    val tableName = "test.table"
    val tableLocation = "/tmp/test/table"

    it should "normalize a field" in {
        val field = StructField("Field-1", IntegerType, nullable = false)

        val normalizedField = field.normalize()
        normalizedField.name should equal("field_1")
        normalizedField.dataType should equal(LongType)
        normalizedField.nullable should equal(true)
    }

    it should "make all fields of a struct nullable" in {
        val schema1 = StructType(Seq(
            StructField("F1", IntegerType, nullable = false),
            StructField("f2", StructType(Seq(
                StructField("S1", StringType, nullable = false),
                StructField("s2", StringType, nullable = false),
                StructField("A1", StructType(Seq(
                    StructField("c1", StringType, nullable=false)
                )))
            )), nullable = false),
            StructField("f3", StringType, nullable = false)
        ))

        val nulledSchema = schema1.makeNullable()
        nulledSchema("F1").nullable should equal(true)
        nulledSchema("f2").dataType.asInstanceOf[StructType]("S1").nullable should equal(true)
    }

    it should "build a Hive DDL string representing a simple column" in {
        val field = StructField("f1", LongType, nullable = false)
        val expected = s"`f1` bigint NOT NULL"

        field.hiveColumnDDL should equal(expected)
    }

    it should "build Hive DDL string representing a complex column" in {
        val field = StructField("S1", StructType(Seq(
            StructField("s1", StringType, nullable=true)
        )))
        val expected = s"`S1` struct<`s1`:string>"

        field.hiveColumnDDL should equal(expected)
    }


    it should "build a Hive DDL string representing multiple columns" in {
        val fields = Seq(
            StructField("f1", StringType, nullable = false),
            StructField("f2", IntegerType, nullable = true),
            StructField("S1", StructType(Seq(
                StructField("s1", StringType, nullable=true)
            )))
        )
        val expected =
            s"""`f1` string NOT NULL,
               |`f2` int,
               |`S1` struct<`s1`:string>""".stripMargin

        StructType(fields).hiveColumnsDDL() should equal(expected)
    }

    it should "merge and normalize 2 simple schemas" in {
        val schema1 = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", StringType, nullable = true)
        ))

        val schema2 = StructType(Seq(
            StructField("f4", LongType, nullable = true),
            StructField("f2", StringType, nullable = true),
            StructField("f3", FloatType, nullable = true)

        ))

        // Unioned schema will be first ordred by schema1 fields, with any extra fields
        // from schema2 appended in the order they are found there.
        val expected = StructType(Seq(
            StructField("f1", LongType, nullable = true),
            StructField("f2", StringType, nullable = true),
            StructField("f4", LongType, nullable = true),
            StructField("f3", DoubleType, nullable = true)
        ))

        schema1.merge(schema2) should equal(expected)
    }


    it should "merge and not normalize 2 simple schemas" in {
        val schema1 = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", StringType, nullable = true)
        ))

        val schema2 = StructType(Seq(
            StructField("f4", LongType, nullable = true),
            StructField("f2", StringType, nullable = true),
            StructField("f3", IntegerType, nullable = true)

        ))

        // Unioned schema will be first ordred by schema1 fields, with any extra fields
        // from schema2 appended in the order they are found there.
        val expected = StructType(Seq(
            StructField("F1", LongType, nullable = true),
            StructField("f2", StringType, nullable = true),
            StructField("f4", LongType, nullable = true),
            StructField("f3", LongType, nullable = true)
        ))

        schema1.merge(schema2, lowerCaseTopLevel=false) should equal(expected)
    }

    it should "merge and not normalize 2 complex schemas" in {
        val schema1 = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", StructType(Seq(
                StructField("S1", StringType, nullable = true),
                StructField("s2", StringType, nullable = true),
                StructField("A1", StructType(Seq(
                    StructField("c1", StringType, nullable=true)
                )))
            )), nullable = true),
            StructField("f3", StringType, nullable = true)
        ))

        val schema2 = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", StructType(Seq(
                StructField("S1", StringType, nullable = true),
                StructField("s3", StringType, nullable = true),
                StructField("A1", StructType(Seq(
                    StructField("c1", StringType, nullable=true),
                    StructField("c2", StringType, nullable=true)
                )))
            )), nullable = true),
            StructField("F5", StructType(Seq(
                StructField("b1", IntegerType, nullable=true))
            )),
            StructField("f4", LongType, nullable = true)
        ))

        // Merged schema will be first ordered by schema1 fields, with any extra fields
        // from schema2 appended in the order they are found there.
        val expected = StructType(Seq(
            StructField("F1", LongType, nullable = true),
            StructField("f2", StructType(Seq(
                StructField("S1", StringType, nullable = true),
                StructField("s2", StringType, nullable = true),
                StructField("A1", StructType(Seq(
                    StructField("c1", StringType, nullable=true),
                    StructField("c2", StringType, nullable=true)
                ))),
                StructField("s3", StringType, nullable = true)
            )), nullable = true),
            StructField("f3", StringType, nullable = true),
            StructField("F5", StructType(Seq(
                StructField("b1", LongType, nullable=true))
            )),
            StructField("f4", LongType, nullable = true)
        ))

        schema1.merge(schema2, lowerCaseTopLevel=false) should equal(expected)
    }


    it should "merge and normalize 2 complex schemas" in {
        val schema1 = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", StructType(Seq(
                StructField("S1", StringType, nullable = true),
                StructField("s2", StringType, nullable = true),
                StructField("A1", StructType(Seq(
                    StructField("c1", StringType, nullable=true)
                )))
            )), nullable = true),
            StructField("f3", StringType, nullable = true)
        ))

        val schema2 = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", StructType(Seq(
                StructField("S1", StringType, nullable = true),
                StructField("s3", StringType, nullable = true),
                StructField("A1", StructType(Seq(
                    StructField("c1", StringType, nullable=true),
                    StructField("c2", StringType, nullable=true)
                )))
            )), nullable = true),
            StructField("F5", StructType(Seq(
                StructField("b1", IntegerType, nullable=true))
            )),
            StructField("f4", LongType, nullable = true)
        ))

        // Merged schema will be first ordred by schema1 fields, with any extra fields
        // from schema2 appended in the order they are found there.
        val expected = StructType(Seq(
            StructField("f1", LongType, nullable = true),
            StructField("f2", StructType(Seq(
                StructField("S1", StringType, nullable = true),
                StructField("s2", StringType, nullable = true),
                StructField("A1", StructType(Seq(
                    StructField("c1", StringType, nullable=true),
                    StructField("c2", StringType, nullable=true)
                ))),
                StructField("s3", StringType, nullable = true)
            )), nullable = true),
            StructField("f3", StringType, nullable = true),
            StructField("f5", StructType(Seq(
                StructField("b1", LongType, nullable=true))
            )),
            StructField("f4", LongType, nullable = true)
        ))

        schema1.merge(schema2, lowerCaseTopLevel=true) should equal(expected)
    }

    it should "build non external no partitions create DDL with single schema" in {
        val schema = StructType(Seq(
            StructField("f2", LongType, nullable = true),
            StructField("f3", StringType, nullable = true),
            StructField("F1", IntegerType, nullable = true)
        ))

        val expected =
            s"""CREATE TABLE $tableName (
               |`f2` bigint,
               |`f3` string,
               |`f1` bigint
               |)
               |-- No partition provided
               |STORED AS PARQUET""".stripMargin

        val statement = schema.hiveCreateDDL(tableName)

        statement should equal(expected)
    }

    it should "build external no partitions create DDL with single schema" in {
        val schema = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", LongType, nullable = true),
            StructField("f3", StringType, nullable = true)
        ))

        val expected =
            s"""CREATE EXTERNAL TABLE $tableName (
               |`f1` bigint,
               |`f2` bigint,
               |`f3` string
               |)
               |-- No partition provided
               |STORED AS PARQUET
               |LOCATION '$tableLocation'""".stripMargin

        val statement = schema.hiveCreateDDL(tableName, tableLocation)

        statement should equal(expected)
    }

    it should "build external create DDL with partitions and single schema" in {
        val schema = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", LongType, nullable = true),
            StructField("f4", StringType, nullable = true),
            StructField("f3", StringType, nullable = true)
        ))
        val partitions = Seq("f3", "f4")

        val expected =
            s"""CREATE EXTERNAL TABLE $tableName (
               |`f1` bigint,
               |`f2` bigint
               |)
               |PARTITIONED BY (
               |`f3` string,
               |`f4` string
               |)
               |STORED AS PARQUET
               |LOCATION '$tableLocation'""".stripMargin

        val statement = schema.hiveCreateDDL(tableName, tableLocation, partitions)

        statement should equal(expected)
    }


    it should "build alter DDL with single schema" in {
        val schema = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", LongType, nullable = true),
            StructField("f3", StringType, nullable = true)
        ))

        val otherSchema = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f3", StringType, nullable = true),
            StructField("f4", LongType, nullable = true)
        ))

        val expected = Seq(
            s"""ALTER TABLE $tableName
               |ADD COLUMNS (
               |`f4` bigint
               |)""".stripMargin
        )

        val statements = schema.hiveAlterDDL(tableName, otherSchema)

        statements should equal(expected)
    }


    it should "build alter DDL with just modified merged structs" in {
        val schema = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", StructType(Seq(
                StructField("S1", StringType, nullable = true),
                StructField("s2", StringType, nullable = true),
                StructField("A1", StructType(Seq(
                    StructField("c1", StringType, nullable=true)
                )))
            )), nullable = true),
            StructField("f3", StringType, nullable = true)
        ))

        val otherSchema = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", StructType(Seq(
                StructField("S1", StringType, nullable = true),
                StructField("s3", StringType, nullable = true),
                StructField("A1", StructType(Seq(
                    StructField("c1", StringType, nullable=true),
                    StructField("C2", StringType, nullable=true)
                )))
            )), nullable = true)
        ))

        val expected = Seq(
            s"""ALTER TABLE $tableName
               |CHANGE COLUMN `f2` `f2` struct<`S1`:string,`s2`:string,`A1`:struct<`c1`:string,`C2`:string>,`s3`:string>""".stripMargin
        )

        val statements = schema.hiveAlterDDL(tableName, otherSchema)

        statements should equal(expected)
    }

    it should "build alter DDL with new columns and modified merged structs" in {
        val schema = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", StructType(Seq(
                StructField("S1", StringType, nullable = true),
                StructField("s2", StringType, nullable = true),
                StructField("A1", StructType(Seq(
                    StructField("c1", StringType, nullable=true)
                )))
            )), nullable = true),
            StructField("f3", StringType, nullable = true)
        ))

        val otherSchema = StructType(Seq(
            StructField("F1", IntegerType, nullable = true),
            StructField("f2", StructType(Seq(
                StructField("S1", StringType, nullable = true),
                StructField("s3", StringType, nullable = true),
                StructField("A1", StructType(Seq(
                    StructField("c1", StringType, nullable=true),
                    StructField("c2", StringType, nullable=true)
                )))
            )), nullable = true),
            StructField("f4", StructType(Seq(
                StructField("b1", IntegerType, nullable=true))
            )),
            StructField("f5", LongType, nullable = true)
        ))

        val expected = Seq(
            s"""ALTER TABLE $tableName
               |ADD COLUMNS (
               |`f4` struct<`b1`:bigint>,
               |`f5` bigint
               |)""".stripMargin,
            s"""ALTER TABLE $tableName
               |CHANGE COLUMN `f2` `f2` struct<`S1`:string,`s2`:string,`A1`:struct<`c1`:string,`c2`:string>,`s3`:string>""".stripMargin
        )

        val statements = schema.hiveAlterDDL(tableName, otherSchema)

        statements should equal(expected)
    }

    it should "find incompatible fields for 'not-unordered-superset' (missing)" in {
        val smallerSchema = StructType(StructField("a", LongType, nullable = false) :: Nil)
        val biggerSchema = StructType(StructField("b", LongType, nullable = false) :: Nil)

        val badFields = biggerSchema.findIncompatibleFields(smallerSchema).map(_._1)
        badFields.head.name should equal("a")
    }

    it should "find incompatible fields for 'not-unordered-superset' (different types)" in {
        val smallerSchema = StructType(StructField("a", LongType, nullable = false) :: Nil)
        val biggerSchema = StructType(StructField("a", StringType, nullable = false) :: Nil)

        val badFields = biggerSchema.findIncompatibleFields(smallerSchema).map(_._1)
        badFields.length should equal(0)
    }

    it should "find incompatible fields for 'unordered-superset' with compatible different types" in {
        val smallerSchema = StructType(StructField("a", IntegerType, nullable = false) :: Nil)
        val biggerSchema = StructType(StructField("a", LongType, nullable = false) :: Nil)

        val badFields = biggerSchema.findIncompatibleFields(smallerSchema).map(_._1)
        badFields.isEmpty should equal(true)
    }

    it should "find incompatible fields for 'not-unordered-superset' (different nullable)" in {
        val smallerSchema = StructType(StructField("a", LongType, nullable = false) :: Nil)
        val biggerSchema = StructType(StructField("a", LongType, nullable = true) :: Nil)

        val badFields = biggerSchema.findIncompatibleFields(smallerSchema).map(_._1)
        badFields.head.name should equal("a")
    }

    it should "find incompatible fields for 'not-unordered-superset' (sub-object)" in {
        val smallerSchema = StructType(
            StructField("a", StructType(
                    StructField("aa", IntegerType, nullable = true) ::
                    StructField("ab", LongType, nullable = false) ::
                    StructField("ac", BooleanType, nullable = false) :: Nil
                ), nullable = true
            ) ::
            StructField("b", LongType, nullable = false) ::
            StructField("c", BooleanType, nullable = false) :: Nil
        )

        val biggerSchema = StructType(
              StructField("b", LongType, nullable = false) ::
              StructField("d", LongType, nullable = false) ::
              StructField("a", StructType(
                      StructField("aa", IntegerType, nullable = true) ::
                      StructField("ab", LongType, nullable = false) ::
                      StructField("ad", LongType, nullable = false) :: Nil
                  ), nullable = true
              ) ::
              StructField("c", BooleanType, nullable = false) :: Nil
        )

        val badFields = biggerSchema.findIncompatibleFields(smallerSchema).map(_._1)
        badFields.head.name should equal("ac")
    }

    it should "convert a DataFrame to superset schema" in {
        val smallerSchema = StructType(
            StructField("a", StructType(
                    StructField("aa", IntegerType, nullable = true) ::
                    StructField("ab", LongType, nullable = true) ::
                    StructField("ac", BooleanType, nullable = true) :: Nil
                ), nullable = true
            ) ::
            StructField("b", LongType, nullable = true) ::
            StructField("c", BooleanType, nullable = true) :: Nil
        )

        val biggerSchema = StructType(
            StructField("b", LongType, nullable = true) ::
            StructField("d", LongType, nullable = true) ::
            StructField("a", StructType(
                    StructField("aa", IntegerType, nullable = true) ::
                    StructField("ab", LongType, nullable = true) ::
                    StructField("ad", LongType, nullable = true) ::
                    StructField("ac", BooleanType, nullable = true) :: Nil
                ), nullable = true
            ) ::
            StructField("c", BooleanType, nullable = true) :: Nil
        )

        val rdd = sc.parallelize(Seq(
            Row(Row(null, 1L, true), 1L, true),
            Row(Row(2, 2L, false), 2L, false),
            Row(null, 3L, null))
        )
        val df = spark.createDataFrame(rdd, smallerSchema)
        val newDf = df.convertToSchema(biggerSchema)

        //newDf.foreach(println)

        newDf.count should equal(3)
        newDf.schema should equal(biggerSchema)
        newDf.filter("c = TRUE").take(1).foreach(r => {
            r.getLong(0) should equal(1L)
            r.isNullAt(1) should equal(true)
            r.getBoolean(3) should equal(true)
            r.getStruct(2).isNullAt(0) should equal(true)
            r.getStruct(2).getLong(1) should equal(1L)
            r.getStruct(2).isNullAt(2) should equal(true)
            r.getStruct(2).getBoolean(3) should equal(true)
        })
        newDf.filter("c = FALSE").take(1).foreach(r => {
            r.getLong(0) should equal(2L)
            r.isNullAt(1) should equal(true)
            r.getBoolean(3) should equal(false)
            r.getStruct(2).getInt(0) should equal(2)
            r.getStruct(2).getLong(1) should equal(2L)
            r.getStruct(2).isNullAt(2) should equal(true)
            r.getStruct(2).getBoolean(3) should equal(false)
        })
        newDf.filter("c IS NULL").take(1).foreach(r => {
            r.getLong(0) should equal(3L)
            r.isNullAt(1) should equal(true)
            r.isNullAt(3) should equal(true)
            r.getStruct(2).isNullAt(0) should equal(true)
            r.getStruct(2).isNullAt(1) should equal(true)
            r.getStruct(2).isNullAt(2) should equal(true)
            r.getStruct(2).isNullAt(3) should equal(true)
        })
    }

    it should "convert a DataFrame with complex types to superset schema" in {

        val smallerSchema = StructType(
            StructField("a", StructType(
                StructField("aa", ArrayType(StringType, containsNull=true), nullable=true) ::
                  StructField("ab", MapType(StringType, LongType, valueContainsNull = true), nullable=true) ::
                  StructField("ac", BooleanType, nullable=true) ::
                  StructField("ae", ArrayType(StructType(StructField("aea", LongType, nullable = true) :: Nil), containsNull=true), nullable=true) ::
                  StructField("af", MapType(
                      StructType(StructField("afa", LongType, nullable = true) :: Nil),
                      StructType(StructField("afb", StringType, nullable = true) :: StructField("afc", LongType, nullable = true) :: Nil),
                      valueContainsNull=true), nullable=true) :: Nil
            ), nullable=true
            ) ::
              StructField("b", LongType, nullable=true) ::
              StructField("c", BooleanType, nullable=true) :: Nil
        )

        val biggerSchema = StructType(
            StructField("b", LongType, nullable=true) ::
              StructField("d", LongType, nullable=true) ::
              StructField("a", StructType(
                  StructField("aa", ArrayType(IntegerType, containsNull=true), nullable=true) ::
                    StructField("ab", MapType(StringType, StringType, valueContainsNull = true), nullable=true) ::
                    StructField("ad", LongType, nullable=true) ::
                    StructField("ac", BooleanType, nullable=true) ::
                    StructField("ae", ArrayType(StructType(StructField("aea", StringType, nullable = true) :: Nil), containsNull=true), nullable=true) ::
                    StructField("af", MapType(
                      StructType(StructField("afa", LongType, nullable = true) :: Nil),
                      StructType(StructField("afb", LongType, nullable = true) :: StructField("afc", StringType, nullable = true) :: Nil),
                      valueContainsNull=true), nullable=true) :: Nil
              ), nullable=true
              ) ::
              StructField("c", BooleanType, nullable=true) :: Nil
        )

        val rdd = sc.parallelize(Seq(
            Row(Row(null, null, true, Array(Row(11L)), Map(Row(12L) -> Row("13", 13L))), 1L, true),
            Row(Row(Array("1", "2"), Map("m1" -> 1L), false, null, null), 2L, false),
            Row(null, 3L, null))
        )
        val df = spark.createDataFrame(rdd, smallerSchema)
        val newDf = df.convertToSchema(biggerSchema)

        //newDf.foreach(println)

        newDf.count should equal(3)
        newDf.schema should equal(biggerSchema)
        newDf.filter("c = TRUE").take(1).foreach(r => {
            r.getLong(0) should equal(1L)
            r.isNullAt(1) should equal(true)
            r.getBoolean(3) should equal(true)
            r.getStruct(2).isNullAt(0) should equal(true)
            r.getStruct(2).isNullAt(1) should equal(true)
            r.getStruct(2).isNullAt(2) should equal(true)
            r.getStruct(2).getBoolean(3) should equal(true)
            r.getStruct(2).getSeq[Row](4).head.getString(0) should equal("11")
            r.getStruct(2).getMap[Row, Row](5).head._1.getLong(0) should equal(12L)
            r.getStruct(2).getMap[Row, Row](5).head._2.getLong(0) should equal(13L)
            r.getStruct(2).getMap[Row, Row](5).head._2.getString(1) should equal("13")
        })
        newDf.filter("c = FALSE").take(1).foreach(r => {
            r.getLong(0) should equal(2L)
            r.isNullAt(1) should equal(true)
            r.getBoolean(3) should equal(false)
            r.getStruct(2).getSeq[Int](0) should equal(Seq(1, 2))
            r.getStruct(2).getMap[String, String](1) should equal(Map("m1" -> "1"))
            r.getStruct(2).isNullAt(2) should equal(true)
            r.getStruct(2).getBoolean(3) should equal(false)
            r.getStruct(2).isNullAt(4) should equal(true)
            r.getStruct(2).isNullAt(5) should equal(true)
        })
        newDf.filter("c IS NULL").take(1).foreach(r => {
            r.getLong(0) should equal(3L)
            r.isNullAt(1) should equal(true)
            r.isNullAt(3) should equal(true)
            r.getStruct(2).isNullAt(0) should equal(true)
            r.getStruct(2).isNullAt(1) should equal(true)
            r.getStruct(2).isNullAt(2) should equal(true)
            r.getStruct(2).isNullAt(3) should equal(true)
            r.getStruct(2).isNullAt(4) should equal(true)
            r.getStruct(2).isNullAt(5) should equal(true)
        })
    }

    it should "convert DataFrame with case classes and null value to superset schema" in {
        val tableDf = spark.createDataFrame(sc.parallelize(Seq(Table(), Table(), Table())))
        val nullDf = spark.createDataFrame(sc.parallelize(Seq(Input(a2=null.asInstanceOf[String]), Input(), Input())))
        val newDf = nullDf.convertToSchema(tableDf.schema)

        newDf.count should equal(3)
        newDf.filter("a2 IS NULL").take(1).foreach(r => {
            r.getString(0) should equal("aa1")
            r.isNullAt(1) should equal(true)
        })
    }

    it should "convert DataFrame with case classes and null sub-object to superset schema" in {
        val tableDf = spark.createDataFrame(sc.parallelize(Seq(Table(), Table(), Table())))
        val nullDf = spark.createDataFrame(sc.parallelize(Seq(Input(b=null.asInstanceOf[NestedObjectA]), Input(), Input())))
        val newDf = nullDf.convertToSchema(tableDf.schema)

        newDf.count should equal(3)
        newDf.filter("b IS NULL").take(1).foreach(r => {
            r.getString(0) should equal("aa1")
            r.getString(1) should equal("aa2")
            r.isNullAt(2) should equal(true)
        })
    }

    it should "convert DataFrame with case classes and null sequence to superset schema" in {
        val tableDf = spark.createDataFrame(sc.parallelize(Seq(Table(), Table(), Table())))
        val nullDf = spark.createDataFrame(sc.parallelize(Seq(Input(b=NestedObjectA(b1=null.asInstanceOf[Seq[String]])), Input(), Input())))
        val newDf = nullDf.convertToSchema(tableDf.schema)

        newDf.count should equal(3)
        newDf.filter("b IS NULL").take(1).foreach(r => {
            r.getString(0) should equal("aa1")
            r.getString(1) should equal("aa2")
            r.isNullAt(2) should equal(true)
        })
    }

    it should "convert DataFrame with case classes and null map to superset schema" in {
        val tableDf = spark.createDataFrame(sc.parallelize(Seq(Table(), Table(), Table())))
        val nullDf = spark.createDataFrame(sc.parallelize(Seq(Input(b=NestedObjectA(b2=null.asInstanceOf[Map[Long, String]])), Input(), Input())))
        val newDf = nullDf.convertToSchema(tableDf.schema)

        newDf.count should equal(3)
        newDf.filter("b IS NULL").take(1).foreach(r => {
            r.getString(0) should equal("aa1")
            r.getString(1) should equal("aa2")
            r.isNullAt(2) should equal(true)
        })
    }

    it should "merge to LongType from large Long, not StringType" in {
        val events = sc.parallelize(Seq(
            """{"id": 1, "event": {"num": 123}}""",
            """{"id": 2, "event": {"num": 9223372036854776000}}"""
        ))

        val tableSchema = StructType(Seq(
            StructField("id", LongType, nullable = true),
            StructField("event", StructType(Seq(
                StructField("num", LongType, nullable = true)
            )))
        ))

        import spark.implicits._
        val df = spark.read.json(spark.createDataset[String](events))
        tableSchema.merge(df.schema) should equal(tableSchema)
    }



    it should "convert DataFrame with inferred StringType to LongType, nullifying only bad field" in {
        val events = sc.parallelize(Seq(
            """{"id": 1, "event": {"num": 123}}""",
            """{"id": 2, "event": {"num": "456"}}""",
            """{"id": 2, "event": {"num": 9223372036854776000}}""" // json parses this as a string!
        ))

        val tableSchema = StructType(Seq(
            StructField("id", LongType, nullable = true),
            StructField("event", StructType(Seq(
                StructField("num", LongType, nullable = true)
            )))
        ))

        import spark.implicits._
        val df = spark.read.json(spark.createDataset[String](events))

        val newDf = df.convertToSchema(tableSchema)
        newDf.createOrReplaceTempView("newDf")

        // 9223372036854776000 -> NULL, NumberFormatException
        spark.sql("SELECT * from newDf where event.num IS NULL").count should equal(1)
        // 123 -> 123, "456" -> 456
        spark.sql("SELECT * from newDf where event.num IS NOT NULL").count should equal(2)
        // id should be cool.
        spark.sql("SELECT * from newDf where id IS NOT NULL").count should equal(3)
    }

    it should "convert DataFrame with DecimalType to LongType, nullifying only bad field" in {
        val events = sc.parallelize(Seq(
            """{"id": 1, "event": {"num": 123}}""",
            """{"id": 1, "event": {"num": 456.456}}""",
            """{"id": 2, "event": {"num": 9223372036854776000}}"""
        ))

        val tableSchema = StructType(Seq(
            StructField("id", LongType, nullable = true),
            StructField("event", StructType(Seq(
                StructField("num", LongType, nullable = true)
            )))
        ))

        val eventSchema = StructType(Seq(
            StructField("id", LongType, nullable = true),
            StructField("event", StructType(Seq(
                StructField("num", DataTypes.createDecimalType(20, 0), nullable = true)
            )))
        ))

        import spark.implicits._
        val df = spark.read.schema(eventSchema).json(spark.createDataset[String](events))

        val newDf = df.convertToSchema(tableSchema)
        newDf.createOrReplaceTempView("newDf")

        // 9223372036854776000 -> casted to Decimal
        spark.sql("SELECT * from newDf where event.num IS NULL").count should equal(0)
        // 123 -> 123, 456.456 -> 456
        spark.sql("SELECT * from newDf where event.num IS NOT NULL").count should equal(3)
        // id should be cool.
        spark.sql("SELECT * from newDf where id IS NOT NULL").count should equal(3)
    }
}

// case classes used by DataFrame convertToSchema tests
case class NestedObjectA(
    b1: Seq[String] = Seq("b1", "b1b"),
    b2: Map[Long, String] = Map(1L -> "b2", 2L -> "b2")
)

case class NestedObjectB(
    b3: String = "b3",
    b2: Map[Long, String] = Map(1L -> "b2", 2L -> "b2"),
    b1: Seq[String] = Seq("b1", "b1b")
)

case class Input(
    a2: String = "aa2",
    b: NestedObjectA = NestedObjectA(),
    a1: String = "aa1"
)

case class Table(
    a1: String = "ab1",
    a2: String = "ab2",
    b: NestedObjectB = NestedObjectB()
)
