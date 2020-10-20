package predict.common
/**
 * @Author wj
 * @Date 2020/09
 * @Version 1.0
 */

import mam.Dic
import mam.Utils.udfChangeDateFormat
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql
import org.apache.spark.sql.types._
import org.apache.spark.sql.{SaveMode, SparkSession}
import mam.Utils.printDf

object OrdersProcess {

    def main(args: Array[String]): Unit ={
      System.setProperty("hadoop.home.dir","c:\\winutils")
      Logger.getLogger("org").setLevel(Level.ERROR)
      val spark: SparkSession = new sql.SparkSession.Builder()
        .appName("PredictOrdersProcess")
        //.master("local[6]")
        .getOrCreate()

      import org.apache.spark.sql.functions._
      val hdfsPath="hdfs:///pay_predict/"
      //val hdfsPath=""
      val orderRawPath=hdfsPath+"data/predict/common/raw/orders/order*.txt"
      val orderProcessedPath=hdfsPath+"data/predict/common/processed/orders"

      val schema= StructType(
        List(
          StructField(Dic.colUserId, StringType),
          StructField(Dic.colMoney, StringType),
          StructField(Dic.colResourceType, StringType),
          StructField(Dic.colResourceId, StringType),
          StructField(Dic.colResourceTitle, StringType),
          StructField(Dic.colCreationTime, StringType),
          StructField(Dic.colDiscountDescription, StringType),
          StructField(Dic.colOrderStatus, StringType),
          StructField(Dic.colOrderStartTime, StringType),
          StructField(Dic.colOrderEndTime, StringType)))
      val df = spark.read
        .option("delimiter", "\t")
        .option("header", false)
        .schema(schema)
        .csv(orderRawPath)

      printDf("df", df)

      val df1=df.withColumn(Dic.colCreationTime,udfChangeDateFormat(col(Dic.colCreationTime)))
        .withColumn(Dic.colOrderStartTime,udfChangeDateFormat(col(Dic.colOrderStartTime)))
        .withColumn(Dic.colOrderEndTime,udfChangeDateFormat(col(Dic.colOrderEndTime)))

      printDf("df1", df1)

      val df2=df1.select(
        when(col(Dic.colUserId)==="NULL",null).otherwise(col(Dic.colUserId)).as(Dic.colUserId),
        when(col(Dic.colMoney)==="NULL",Double.NaN).otherwise(col(Dic.colMoney) cast DoubleType).as(Dic.colMoney),
        when(col(Dic.colResourceType)==="NULL",Double.NaN).otherwise(col(Dic.colResourceType) cast DoubleType).as(Dic.colResourceType),
        when(col(Dic.colResourceId)==="NULL",null).otherwise(col(Dic.colResourceId) ).as(Dic.colResourceId),
        when(col(Dic.colResourceTitle)==="NULL",null).otherwise(col(Dic.colResourceTitle)).as(Dic.colResourceTitle),
        when(col(Dic.colCreationTime)==="NULL",null).otherwise(col(Dic.colCreationTime) cast TimestampType ).as(Dic.colCreationTime),
        when(col(Dic.colDiscountDescription)==="NULL",null).otherwise(col(Dic.colDiscountDescription)).as(Dic.colDiscountDescription),
        when(col(Dic.colOrderStatus)==="NULL",Double.NaN).otherwise(col(Dic.colOrderStatus) cast DoubleType).as(Dic.colOrderStatus),
        when(col(Dic.colOrderStartTime)==="NULL",null).otherwise(col(Dic.colOrderStartTime) cast TimestampType).as(Dic.colOrderStartTime),
        when(col(Dic.colOrderEndTime)==="NULL",null).otherwise(col(Dic.colOrderEndTime) cast TimestampType).as(Dic.colOrderEndTime))

      printDf("df2", df2)

      df2.write.mode(SaveMode.Overwrite).format("parquet").save(orderProcessedPath)
      println("预测阶段订单数据处理完成！")
    }

  }
