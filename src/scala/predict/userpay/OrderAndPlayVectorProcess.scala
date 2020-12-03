package predict.userpay

/**
 * @author wx
 * @describe order中的订单历史，生成过去一个月的消费历史：包含支付成功和未支付成功
 */

import mam.Dic
import mam.GetSaveData.saveProcessedData
import mam.Utils.{calDate, getData, mapIdToMediasVector, printDf, sysParamSetting, udfGetAllHistory, udfGetDays, udfGetTopNHistory, udfLog, udfLpad, udfUniformTimeValidity}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.sql
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}

object OrderAndPlayVectorProcess {
  def main(args: Array[String]): Unit = {
    sysParamSetting()

    val spark: SparkSession = new sql.SparkSession.Builder()
      .appName("OrderAndPlayVectorProcessPredict")
      //.master("local[6]")
      .getOrCreate()

    val predictTime = args(0) + " " + args(1)
    println(predictTime)

    //val hdfsPath = ""
    val hdfsPath = "hdfs:///pay_predict/"
    val orderProcessedPath = hdfsPath + "data/train/common/processed/orders3"
    val playsProcessedPath = hdfsPath + "data/train/common/processed/userpay/plays_new3"
    val mediasProcessedPath = hdfsPath + "data/train/common/processed/mediastemp"
    val mediasVideoVectorPath = hdfsPath + "data/train/common/processed/userpay/mediasVector"

    val historyPath = hdfsPath + "data/predict/common/processed/userpay/history/"
    val videoVectorSavePath = hdfsPath + "data/predict/common/processed/userpay/videoVector"
    val playHistoryVectorSavePath = hdfsPath + "data/predict/common/processed/userpay/history/playHistoryVector" + args(0)

    val predictUsersPath = hdfsPath + "data/predict/userpay/predictUsers" + args(0)

    /**
     * Get Files
     */
    val df_orders = getData(spark, orderProcessedPath)
    val df_plays = getData(spark, playsProcessedPath)
    val df_medias = getData(spark, mediasProcessedPath)
    val df_predictUser = getData(spark, predictUsersPath)
    val df_mediasVectorPart = getData(spark, mediasVideoVectorPath)

    val df_predictId = df_predictUser.select(Dic.colUserId)


    /**
     * Get predict user's order history in past three months (predict time and predict time)
     * include payed and clicked history
     */

    val df_orderHistory = getOrderHistoryList(df_predictId, df_orders, predictTime, -90)
    printDf("Users' orders history in past three months", df_orderHistory)

//    saveProcessedData(df_orderHistory, historyPath + "orderHistory" + args(0))
    println("Order history process done! ")


    /**
     * Get predict user's play history
     * 对于每个用户生成播放历史，14天内的播放历史，最多取n条
     */
    val timeWindowPlay = 14 //过去14天的
    val topNPlay = 50
    val df_predictUserPlayHistory = getPlaySeqList(df_predictId, df_plays, Dic.colVideoId, predictTime, -timeWindowPlay, topNPlay)
    printDf("df_predictUserPlayHistory", df_predictUserPlayHistory)

    // 因为映射后向量存不下，因此把这个数据存储到HDFS上，然后用python运行的，所以如果后面的映射向量可以存储就不要存了
//    saveProcessedData(df_predictUserPlayHistory, historyPath + "playHistory" + args(0))

    /**
     * Map Video Id List To Vector !
     */

    // Get vector of medias' video
    val df_videoVector = mediasVectorProcess(df_mediasVectorPart, predictTime)
    printDf("df_videoVector", df_videoVector)

    // 因为映射后向量存不下，因此把这个数据存储到HDFS上，然后用python运行的，所以如果后面的映射向量可以存储就不要存了
//    saveProcessedData(df_videoVector, videoVectorSavePath)

    //    val df_predictUserPlayHistoryVector = mapVideoVector(df_predictUserPlayHistory, df_videoVector, topNPlayHistory = 50)
    //    printDf("df_predictUserPlayHistoryVector", df_predictUserPlayHistoryVector)
    //    saveProcessedData(df_predictUserPlayHistoryVector, playHistoryVectorSavePath)
    //


    // play time, not use yet
    //    val df_playsTime = getPlaySeqList(df_predictId, df_plays, Dic.colTimeSum, now, -timeWindowPlay, topNPlay)
    //    val df_playsHistory = df_playsVideo.join(df_playsTime, Dic.colUserId)

    //    printDf("plays_list", df_playsHistory)
    //    saveProcessedData(df_playsHistory, savePath + "playHistory" + args(0))



    println("Play history vector process done!!!")



    /**
     * Get All Users' play history during predict time to express package as package vector
     */
    val df_videoInPackPlayTimes = getVideoPlaysTimes(df_plays, predictTime, 7, df_medias)
    printDf("df_playsHistoryPredictIn2Pack", df_videoInPackPlayTimes)

    /**
     * Use videos' vector in package to express package
     */
    val df_playTimesAndVector = df_videoVector.join(df_videoInPackPlayTimes, Seq(Dic.colVideoId), "inner")

    // Video vector and play times weighted
    val df_packageExpByVideo = df_playTimesAndVector.withColumn("log_count", when(col(Dic.colPlayTimes) > 0, udfLog(col(Dic.colPlayTimes))).otherwise(0))
      .select(Dic.colVideoId, "vector","log_count")

    printDf("df_packageExpByVideo", df_packageExpByVideo)


    //    Use python to select "weighted_vector" to be a Matrix then merge with every user
    saveProcessedData(df_packageExpByVideo, historyPath + "packageExpByPlayedVideo" + args(0))

    println("packageExpByVideo dataframe save done!")

  }


  def getOrderHistoryList(df_predictId: DataFrame, df_orders: DataFrame, now: String, timeLength: Int) = {

    /**
     * Select order history during now and now-timeLength
     * Create a new column called CreationTimeGap
     */
    val df_orderPart = df_orders.join(df_predictId, Seq(Dic.colUserId), "inner")
      .filter(col(Dic.colCreationTime) < now and col(Dic.colCreationTime) >= calDate(now, timeLength))
      .withColumn("now", lit(now))
      .withColumn(Dic.colCreationTimeGap, udfGetDays(col(Dic.colCreationTime), col("now")))
      //.withColumn(Dic.colIsMoneyError, udfGetErrorMoneySign(col(Dic.colResourceType), col(Dic.colMoney)))
      .withColumn(Dic.colTimeValidity, udfUniformTimeValidity(col(Dic.colTimeValidity), col(Dic.colResourceType)))
      .select(Dic.colUserId, Dic.colMoney, Dic.colResourceType, Dic.colCreationTimeGap, Dic.colTimeValidity, Dic.colOrderStatus, Dic.colCreationTime)


    printDf("df_orderPart", df_orderPart)

    /**
     * This is to make sure the order history is in order after calculate in cluster
     */
    import org.apache.spark.sql.expressions.Window
    val win = Window.partitionBy(Dic.colUserId).orderBy(desc(Dic.colCreationTime))

    val rowCount = df_orderPart.count().toString.length
    val df_orderConcatCols = df_orderPart.withColumn("index", row_number().over(win))
      .withColumn("0", lit("0")) //要pad的字符
      .withColumn("tmp_rank", udfLpad(col("index"), lit(rowCount), col("0"))) //拼接列
      .drop("0")
      .withColumn("tmp_column", concat_ws(":", col("tmp_rank"),
        concat_ws(",", col(Dic.colMoney), col(Dic.colResourceType), col(Dic.colCreationTimeGap), col(Dic.colTimeValidity), col(Dic.colOrderStatus)).as(Dic.colOrderHistory)))


    val df_orderHistoryUnionSameUser = df_orderConcatCols.groupBy(col(Dic.colUserId))
      .agg(collect_list(col("tmp_column")).as("tmp_column")) //collect_set 会去重
      .withColumn("tmp_column_1", sort_array(col("tmp_column")))
      .withColumn("tmp_column_list", udfGetAllHistory(col("tmp_column_1")))
      .select(Dic.colUserId, "tmp_column_list")
      .withColumnRenamed("tmp_column_list", Dic.colOrderHistory)


    df_orderHistoryUnionSameUser

  }


  def getVideoPlaysTimes(df_plays: DataFrame, predict_time: String, timeLength: Int, df_medias: DataFrame) = {

    // predict users' play history in predict time
    val df_predictUserPlay = df_plays.filter(col(Dic.colPlayStartTime).<(predict_time) and col(Dic.colPlayStartTime) >= calDate(predict_time, days = -timeLength))

    //video in package that need to predict
    val df_videoInPredictPack = df_medias.filter(col(Dic.colPackageId) === 100201 or col(Dic.colPackageId) === 100202)
    val df_predictPlayHistory = df_predictUserPlay.join(df_videoInPredictPack, Seq(Dic.colVideoId), "inner")


    // Get video played times by users
    val df_videoPlayTimes = df_predictPlayHistory.groupBy(Dic.colVideoId).agg(count(Dic.colPlayStartTime).as(Dic.colPlayTimes))
    df_videoPlayTimes
  }


  def getPlaySeqList(df_predictId: DataFrame, df_play: DataFrame, colName: String, now: String, timeWindowPlay: Int, topNPlay: Int) = {
    /**
     * @describe 按照userid和播放起始时间逆向排序 选取 now - timewindow 到 now的播放历史和播放时长
     * @author wx
     * @param [plays]
     * @param [now]
     * @return {@link org.apache.spark.sql.Dataset< org.apache.spark.sql.Row > }
     * */

    var df_playList = df_play.join(df_predictId, Seq(Dic.colUserId), "inner")
      .filter(col(Dic.colPlayStartTime).<(now) && col(Dic.colPlayStartTime) >= calDate(now, days = timeWindowPlay))

    //获取数字位数
    val rowCount = df_playList.count().toString.length
    println("df count number length", rowCount)

    val win = Window.partitionBy(Dic.colUserId).orderBy(col(Dic.colPlayStartTime).desc)

    /**
     * This part is to ensure the sequence has correct order after spark cluster
     * The order is desc(play_start_time)
     */
    df_playList = df_playList.withColumn("index", row_number().over(win))
      .withColumn("0", lit("0")) //要pad的字符
      .withColumn("tmp_rank", udfLpad(col("index"), lit(rowCount), col("0"))) //拼接列
      .drop("0")
      .withColumn("tmp_column", concat_ws(":", col("tmp_rank"), col(colName)))


    df_playList = df_playList.groupBy(col(Dic.colUserId))
      .agg(collect_list(col("tmp_column")).as("tmp_column")) //collect_set 会去重
      .withColumn("tmp_column_1", sort_array(col("tmp_column")))
      .withColumn(colName + "_list", udfGetTopNHistory(col("tmp_column_1"), lit(topNPlay)))
      .select(Dic.colUserId, colName + "_list")


    df_playList
  }

  def mediasVectorProcess(df_mediasVectorPart: DataFrame, predictTime: String) = {
    /**
     * @description: Add storage time gap to medias info then assemble to video vector
     * @param: df_mediasVectorPart : medias dataframe
     * @param: predictTime
     * @return: org.apache.spark.sql.Dataset<org.apache.spark.sql.Row>
     * @author: wx
     * @Date: 2020/11/30
     */

    val df_mediasVector = df_mediasVectorPart.withColumn("predictTime", lit(predictTime))
      .withColumn(Dic.colStorageTimeGap, udfGetDays(col(Dic.colStorageTime), col("predictTime")))
      .drop("predictTime", Dic.colStorageTime)

    // fill na colStorageTime with colLevelOne mean storage time
    val df_fillGapMedias = fillStorageGap(df_mediasVector)

    printDf("df_mediasVector", df_fillGapMedias)

    // Concat columns to be vector of the videos
    val mergeCols = df_fillGapMedias.columns.filter(!_.contains(Dic.colVideoId)) //remove column videoId
    val assembler = new VectorAssembler()
      .setInputCols(mergeCols)
      .setHandleInvalid("keep")
      .setOutputCol("vector")

    assembler.transform(df_fillGapMedias).select(Dic.colVideoId, "vector")

  }


  def fillStorageGap(df_medias: DataFrame): DataFrame = {
    /**
     * @describe 根据video的视频一级分类进行相关列空值的填充
     * @author wx
     * @param [mediasDf]
     * @param [spark]
     * @return {@link DataFrame }
     * */
    val df_mean = df_medias.groupBy(Dic.colVideoOneLevelClassification).agg(mean(col(Dic.colStorageTimeGap)))
      .withColumnRenamed("avg(" + Dic.colStorageTimeGap + ")", "mean_" + Dic.colStorageTimeGap)

    printDf("medias中一级分类的" + Dic.colStorageTimeGap + "平均值", df_mean)

    // video的colName全部video平均值
    val meanValue = df_medias.agg(mean(Dic.colStorageTimeGap)).collectAsList().get(0).get(0)
    println("mean " + Dic.colStorageTimeGap, meanValue)


    val df_mediasJoinMean = df_medias.join(df_mean, Seq(Dic.colVideoOneLevelClassification), "inner")
    printDf("df_mediasJoinMean", df_mediasJoinMean)


    val df_meanFilled = df_mediasJoinMean.withColumn(Dic.colStorageTimeGap, when(col(Dic.colStorageTimeGap).>=(0.0), col(Dic.colStorageTimeGap))
      .otherwise(col("mean_" + Dic.colStorageTimeGap)))
      .na.fill(Map((Dic.colStorageTimeGap, meanValue)))
      .drop("mean_" + Dic.colStorageTimeGap)

    df_meanFilled.withColumn(Dic.colStorageTimeGap, udfLog(col(Dic.colStorageTimeGap)))
      .drop(Dic.colVideoOneLevelClassification)
  }

  def mapVideoVector(df_predictUserPlayHistory: DataFrame, df_videoVector: DataFrame, topNPlayHistory: Int) = {
    /**
     * @description: Map the video id list to vector
     * @param:df_playHistory : df_playHistory Dataframe which has video id list
     * @param: df_videoVector  : Video Vector Dataframe, (columns video_id, vector)
     * @param: topNPlayHistory : play history video's number
     * @return: org.apache.spark.sql.Dataset<org.apache.spark.sql.Row>
     * @author: wx
     * @Date: 2020/11/26
     */

    import scala.collection.mutable
    /**
     * Medias to Map( video_id -> vector)
     */
    val mediasMap = df_videoVector.rdd //Dataframe转化为RDD
      .map(row => row.getAs(Dic.colVideoId).toString -> row.getAs("vector").toString)
      .collectAsMap() //将key-value对类型的RDD转化成Map
      .asInstanceOf[mutable.HashMap[String, String]]

    println("Medias Map size", mediasMap.size)

    printDf("df_predictUserPlayHistory", df_predictUserPlayHistory)

    val df_playVector = df_predictUserPlayHistory.withColumn("play_vector", mapIdToMediasVector(mediasMap)(col(Dic.colVideoId + "_list")))
      .drop(Dic.colVideoId + "_list")

    df_playVector

  }
}