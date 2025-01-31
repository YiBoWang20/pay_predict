package predict.common

import mam.Dic
import mam.Utils.{printDf, udfAddSuffix}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql
import org.apache.spark.sql.types.{FloatType, StringType, StructField, StructType}
import org.apache.spark.sql.{SaveMode, SparkSession}


object PlaysProcess {
  def main(args: Array[String]): Unit = {
    System.setProperty("hadoop.home.dir","c:\\winutils")
    Logger.getLogger("org").setLevel(Level.ERROR)
    val spark: SparkSession = new sql.SparkSession.Builder()
      //.master("local[4]")
      .appName("PredictPlaysProcess")
      .getOrCreate()
    val schema = StructType(
      List(
        StructField(Dic.colUserId, StringType),
        StructField(Dic.colPlayEndTime, StringType),
        StructField(Dic.colVideoId, StringType),
        StructField(Dic.colBroadcastTime, FloatType)
      )
    )
    import org.apache.spark.sql.functions._
    val hdfsPath="hdfs:///pay_predict/"
    //val hdfsPath=""
    val playRawPath=hdfsPath+"data/predict/common/raw/plays/behavior_*.txt" //data/predict/common/raw/medias/medias.txt
    val playProcessedPath=hdfsPath+"data/predict/common/processed/plays"
    val df = spark.read
      .option("delimiter", "\t")
      .option("header", false)
      .schema(schema)
      .csv(playRawPath)

    printDf("df",df)

     val df1=df.withColumn(Dic.colPlayEndTime,substring(col(Dic.colPlayEndTime),0,10))
     val df2=df1.groupBy(col(Dic.colUserId),col(Dic.colVideoId),col(Dic.colPlayEndTime))
     val df3=df2.agg(sum(col(Dic.colBroadcastTime)) as Dic.colBroadcastTime)
     val time_max_limit=43200
     val time_min_limit=30
     val df4=df3.filter(col(Dic.colBroadcastTime)<time_max_limit && col(Dic.colBroadcastTime)>time_min_limit)
     val df5=df4.orderBy(col(Dic.colUserId),col(Dic.colPlayEndTime))
     val df6=df5.withColumn(Dic.colPlayEndTime,udfAddSuffix(col(Dic.colPlayEndTime)))
     //df6.show()
    printDf("df6",df6)
     df6.write.mode(SaveMode.Overwrite).format("parquet").save(playProcessedPath)
     println("预测阶段播放数据处理完成！")
  }

}



