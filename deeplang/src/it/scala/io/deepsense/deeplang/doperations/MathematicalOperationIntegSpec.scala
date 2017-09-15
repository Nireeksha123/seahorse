/**
 * Copyright (c) 2015, CodiLime Inc.
 */

package io.deepsense.deeplang.doperations

import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.scalatest.Ignore

import io.deepsense.deeplang.doperables.Transformation
import io.deepsense.deeplang.doperables.dataframe.types.categorical.CategoricalMetadata
import io.deepsense.deeplang.doperables.dataframe.types.categorical.CategoricalMapper
import io.deepsense.deeplang.doperations.exceptions.DOperationExecutionException
import io.deepsense.deeplang.{DOperable, DeeplangIntegTestSupport}
import io.deepsense.deeplang.doperables.dataframe.DataFrame

@Ignore
class MathematicalOperationIntegSpec extends DeeplangIntegTestSupport {

  val resultColumn = 3
  val delta = 0.01
  val column0 = "c0"
  val column1 = "c1"
  val column2 = "c2"
  val column3 = "c3"

  "MathematicalOperation" should {

    "create Transformation that counts ABS properly" in {
      runTest(s"ABS($column1) as $column3", Seq(1.0, 1.1, 1.2, 1.3, null))
    }

    "create Transformation that counts POW properly" in {
      runTest(s"POW($column1, 2.0) as $column3", Seq(1, 1.21, 1.44, 1.69, null))
    }

    "create Transformation that counts SQRT properly" in {
      runTest(s"SQRT($column2) as $column3", Seq(0.447, 1.483, null, 2.04, null))
    }

    "create Transformation that counts SIN properly" in {
      runTest(s"SIN($column1) as $column3", Seq(0.841, -0.891, 0.932, -0.96, null))
    }

    "create Transformation that counts COS properly" in {
      runTest(s"COS($column1) as $column3", Seq(0.540, 0.453, 0.362, 0.267, null))
    }

    "create Transformation that counts TAN properly" in {
      runTest(s"TAN($column1) as $column3", Seq(1.557, -1.964, 2.572, -3.602, null))
    }

    "create Transformation that counts LN properly" in {
      runTest(s"LN($column2) as $column3", Seq(-1.609, 0.788, null, 1.435, null))
    }

    "create Transformation that counts MIN properly" in {
      runTest(s"MIN($column1, $column2) as $column3", Seq(0.4, 1.48, null, -1.3, null))
    }

    "create Transformation that counts MAX properly" in {
      runTest(s"MIN($column1, $column2) as $column3", Seq(0.4, 1.48, null, 2.04, null))
    }

    "create Transformation that counts complex formulas properly" in {
      runTest(s"MAX(POW($column1,$column2), $column1*$column2)+ABS($column1) as $column3",
        Seq(0.4, 1.48, null, -1.3, null))
    }

    "fail when formula is not correct" in {
      intercept[DOperationExecutionException] {
        val dataFrame = applyFormulaToDataFrame("MAX(", prepareDataFrame())
        dataFrame.sparkDataFrame.collect()
      }
    }

    "fail when argument to the function is not correct" in {
      // counting LN from negative number
      intercept[DOperationExecutionException] {
        val dataFrame = applyFormulaToDataFrame(s"LN($column1) as $column3", prepareDataFrame())
        dataFrame.sparkDataFrame.collect()
      }
    }

    "retain the categorical column type" in {
      val dataFrame = applyFormulaToDataFrame(s"SIN($column1) as $column3",
        prepareDataFrameWithCategorical())
      CategoricalMetadata(dataFrame).isCategorical(column0) shouldBe true
      CategoricalMetadata(dataFrame).isCategorical(column1) shouldBe false
      CategoricalMetadata(dataFrame).isCategorical(column2) shouldBe false
      CategoricalMetadata(dataFrame).isCategorical(column3) shouldBe false
    }
  }

  def runTest(formula: String, expectedValues: Seq[Any]) : Unit = {
    val dataFrame = applyFormulaToDataFrame(formula, prepareDataFrame())
    val rows = dataFrame.sparkDataFrame.collect()
    validateSchema(dataFrame.sparkDataFrame.schema)
    validateColumn(rows, expectedValues)
  }

  def applyFormulaToDataFrame(formula: String, df: DataFrame): DataFrame = {
    val transformation = prepareTransformation(formula)
    applyTransformation(transformation, df)
  }

  def applyTransformation(transformation: Transformation, df: DataFrame): DataFrame = {
    new ApplyTransformation()
      .execute(executionContext)(Vector(transformation, df))
      .head.asInstanceOf[DataFrame]
  }

  def prepareTransformation(formula: String): Transformation = {
    val operation = new MathematicalOperation()
    operation
      .parameters
      .getStringParameter(MathematicalOperation.formulaParam)
      .value =
      Some(formula)
    operation.execute(executionContext)(Vector.empty[DOperable]).head.asInstanceOf[Transformation]
  }

  def validateSchema(schema: StructType) = {
    schema.fieldNames shouldBe Array(column0, column1, column2, column3)
    schema.fields(0).dataType shouldBe StringType
    schema.fields(1).dataType shouldBe DoubleType
    schema.fields(2).dataType shouldBe DoubleType
    schema.fields(3).dataType shouldBe DoubleType
  }

  /**
   * Check if produced column matches the expected values
   */
  def validateColumn(
    rows: Array[Row], expectedValues: Seq[Any], column: Integer = resultColumn): Unit = {
    forAll(expectedValues.zipWithIndex) {
      case (expectedVal, i) => {
        val value = rows(i).get(column)
        value match {
          case d: Double => expectedVal shouldBe d +- delta
          case _ => expectedVal shouldBe value
        }
      }
    }
  }

  def prepareDataFrameWithCategorical(): DataFrame = {
    val df = prepareDataFrame()
    CategoricalMapper(df, executionContext.dataFrameBuilder).categorized(column0)
  }

  def prepareDataFrame(): DataFrame = {
    val schema: StructType = StructType(List(
      StructField(column0, StringType),
      StructField(column1, DoubleType),
      StructField(column2, DoubleType)))
    val manualRowsSeq: Seq[Row] = Seq(
      Row("aaa", 1.0, 0.2),
      Row("bbb", -1.1, 2.2),
      Row("ccc", 1.2, null),
      Row("ddd", -1.3, 4.2),
      Row("eee", null, null))
    createDataFrame(manualRowsSeq, schema)
  }
}
