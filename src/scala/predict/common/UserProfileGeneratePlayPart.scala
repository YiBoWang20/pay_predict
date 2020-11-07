package predict.common

import mam.Dic
import mam.Utils.{calDate, printDf, udfGetDays}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}


object UserProfileGeneratePlayPart {

  var tempTable = "temp_table"
  var partitiondate: String = _
  var license: String = _

  def main(args: Array[String]): Unit = {

    partitiondate = args(0)
    license = args(1)

    val spark = SparkSession.builder().enableHiveSupport().getOrCreate()

    // 训练集的划分时间点 - 2020-06-01 00:00:00
    val now = "2020-07-01 00:00:00"

    // 1 - processed medias - meida的数据处理相对固定，目前取固定分区 - 2020-10-28，wasu - Konverse - 2020-11-2
    val df_medias = getMedias(spark)

    printDf("df_medias", df_medias)

    // 2 - processed play data
    val df_plays = getPlay(spark)

    printDf("df_plays", df_plays)

    // 3 - data process
    val df_result = userProfileGeneratePlayPart(now, 30, df_medias, df_plays)

    printDf("df_result", df_result)

    // 4 - save data
//    saveData(spark, df_result)
  }

  def userProfileGeneratePlayPart(now: String, timeWindow: Int, df_medias: DataFrame, df_plays: DataFrame) = {

    val df_user_id = df_plays
      .select(col(Dic.colUserId)).distinct()

    val pre_30 = calDate(now, -30)
    val pre_14 = calDate(now, days = -14)
    val pre_7 = calDate(now, -7)
    val pre_3 = calDate(now, -3)
    val pre_1 = calDate(now, -1)

    val df_play_part_1 = df_plays
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_30))
      .groupBy(col(Dic.colUserId))
      .agg(
        countDistinct(col(Dic.colPlayEndTime)).as(Dic.colActiveDaysLast30Days),
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeLast30Days),
        udfGetDays(max(col(Dic.colPlayEndTime)), lit(now)).as(Dic.colDaysFromLastActive),
        udfGetDays(min(col(Dic.colPlayEndTime)), lit(now)).as(Dic.colDaysSinceFirstActiveInTimewindow))

    val df_play_part_2 = df_plays
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_14))
      .groupBy(col(Dic.colUserId))
      .agg(
        countDistinct(col(Dic.colPlayEndTime)).as(Dic.colActiveDaysLast14Days),
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeLast14Days))

    val df_play_part_3 = df_plays
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_7))
      .groupBy(col(Dic.colUserId))
      .agg(
        countDistinct(col(Dic.colPlayEndTime)).as(Dic.colActiveDaysLast7Days),
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeLast7Days))

    val df_play_part_4 = df_plays
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_3))
      .groupBy(col(Dic.colUserId))
      .agg(
        countDistinct(col(Dic.colPlayEndTime)).as(Dic.colActiveDaysLast3Days),
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeLast3Days))

    val joinKeysUserId = Seq(Dic.colUserId)

    val df_result_tmp_1 = df_user_id
      .join(df_play_part_1, joinKeysUserId, "left")
      .join(df_play_part_2, joinKeysUserId, "left")
      .join(df_play_part_3, joinKeysUserId, "left")
      .join(df_play_part_4, joinKeysUserId, "left")

    val joinKeyVideoId = Seq(Dic.colVideoId)

    val df_user_medias = df_plays.join(df_medias, joinKeyVideoId, "inner")

    val df_play_medias_part_11 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_30)
          && col(Dic.colIsPaid).===(1))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimePaidVideosLast30Days))

    val df_play_medias_part_12 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_14)
          && col(Dic.colIsPaid).===(1))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimePaidVideosLast14Days))

    val df_play_medias_part_13 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_7)
          && col(Dic.colIsPaid).===(1))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimePaidVideosLast7Days))

    val df_play_medias_part_14 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_3)
          && col(Dic.colIsPaid).===(1))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimePaidVideosLast3Days))

    val df_play_medias_part_15 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_1)
          && col(Dic.colIsPaid).===(1))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimePaidVideosLast1Days))

    val df_result_tmp_2 = df_result_tmp_1
      .join(df_play_medias_part_11, joinKeysUserId, "left")
      .join(df_play_medias_part_12, joinKeysUserId, "left")
      .join(df_play_medias_part_13, joinKeysUserId, "left")
      .join(df_play_medias_part_14, joinKeysUserId, "left")
      .join(df_play_medias_part_15, joinKeysUserId, "left")

    df_result_tmp_1.unpersist()

    val df_play_medias_part_21 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_30)
          && !isnan(col(Dic.colPackageId)))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeInPackageVideosLast30Days),
        stddev(col(Dic.colBroadcastTime)).as(Dic.colVarTimeInPackageVideosLast30Days),
        countDistinct(col(Dic.colVideoId)).as(Dic.colNumberInPackagesVideosLast30Days))

    val df_play_medias_part_22 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_14)
          && !isnan(col(Dic.colPackageId)))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeInPackageVideosLast14Days),
        stddev(col(Dic.colBroadcastTime)).as(Dic.colVarTimeInPackageVideosLast14Days),
        countDistinct(col(Dic.colVideoId)).as(Dic.colNumberInPackagesVideosLast14Days))

    val df_play_medias_part_23 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_7)
          && !isnan(col(Dic.colPackageId)))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeInPackageVideosLast7Days),
        stddev(col(Dic.colBroadcastTime)).as(Dic.colVarTimeInPackageVideosLast7Days),
        countDistinct(col(Dic.colVideoId)).as(Dic.colNumberInPackagesVideosLast7Days))

    val df_play_medias_part_24 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_3)
          && !isnan(col(Dic.colPackageId)))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeInPackageVideosLast3Days),
        stddev(col(Dic.colBroadcastTime)).as(Dic.colVarTimeInPackageVideosLast3Days),
        countDistinct(col(Dic.colVideoId)).as(Dic.colNumberInPackagesVideosLast3Days))

    val df_play_medias_part_25 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_1)
          && !isnan(col(Dic.colPackageId)))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeInPackageVideosLast1Days),
        stddev(col(Dic.colBroadcastTime)).as(Dic.colVarTimeInPackageVideosLast1Days),
        countDistinct(col(Dic.colVideoId)).as(Dic.colNumberInPackagesVideosLast1Days))

    val df_result_tmp_3 = df_result_tmp_2
      .join(df_play_medias_part_21, joinKeysUserId, "left")
      .join(df_play_medias_part_22, joinKeysUserId, "left")
      .join(df_play_medias_part_23, joinKeysUserId, "left")
      .join(df_play_medias_part_24, joinKeysUserId, "left")
      .join(df_play_medias_part_25, joinKeysUserId, "left")

    df_result_tmp_2.unpersist()

    val df_play_medias_part_31 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_30)
          && col(Dic.colVideoOneLevelClassification).===("幼儿"))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeChildrenVideosLast30Days),
        countDistinct(col(Dic.colVideoId)).as(Dic.colNumberChildrenVideosLast30Days))

    val df_play_medias_part_32 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_14)
          && col(Dic.colVideoOneLevelClassification).===("幼儿"))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeChildrenVideosLast14Days),
        countDistinct(col(Dic.colVideoId)).as(Dic.colNumberChildrenVideosLast14Days))

    val df_play_medias_part_33 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_7)
          && col(Dic.colVideoOneLevelClassification).===("幼儿"))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeChildrenVideosLast7Days),
        countDistinct(col(Dic.colVideoId)).as(Dic.colNumberChildrenVideosLast7Days))

    val df_play_medias_part_34 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_3)
          && col(Dic.colVideoOneLevelClassification).===("幼儿"))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeChildrenVideosLast3Days),
        countDistinct(col(Dic.colVideoId)).as(Dic.colNumberChildrenVideosLast3Days))

    val df_play_medias_part_35 = df_user_medias
      .filter(
        col(Dic.colPlayEndTime).<(now)
          && col(Dic.colPlayEndTime).>=(pre_1)
          && col(Dic.colVideoOneLevelClassification).===("幼儿"))
      .groupBy(col(Dic.colUserId))
      .agg(
        sum(col(Dic.colBroadcastTime)).as(Dic.colTotalTimeChildrenVideosLast1Days),
        countDistinct(col(Dic.colVideoId)).as(Dic.colNumberChildrenVideosLast1Days))

    val df_result = df_result_tmp_3
      .join(df_play_medias_part_31, joinKeysUserId, "left")
      .join(df_play_medias_part_32, joinKeysUserId, "left")
      .join(df_play_medias_part_33, joinKeysUserId, "left")
      .join(df_play_medias_part_34, joinKeysUserId, "left")
      .join(df_play_medias_part_35, joinKeysUserId, "left")

    df_result_tmp_3.unpersist()

    df_result
  }

  /**
    * Get processed media data.
    *
    * @param spark
    * @return
    */
  def getMedias(spark: SparkSession) = {

    // 1 - get processed medias
    val user_order_sql =
      s"""
         |SELECT
         |    video_id ,
         |    video_title ,
         |    video_one_level_classification ,
         |    video_two_level_classification_list ,
         |    video_tag_list ,
         |    director_list ,
         |    actor_list ,
         |    country ,
         |    language ,
         |    release_date ,
         |    storage_time ,
         |    video_time ,
         |    score ,
         |    is_paid ,
         |    package_id ,
         |    is_single ,
         |    is_trailers ,
         |    supplier ,
         |    introduction
         |FROM
         |    vodrs.t_media_sum_processed_paypredict
         |WHERE
         |    partitiondate='20201028' and license='wasu'
      """.stripMargin

    val df_medias = spark.sql(user_order_sql)

    df_medias
  }

  /**
    * Get user play data.
    *
    * @param spark
    * @return
    */
  def getPlay(spark: SparkSession) = {

    // 1 - 获取用户播放记录
    val user_play_sql =
      s"""
         |SELECT
         |    user_id,
         |    video_id,
         |    play_end_time,
         |    broadcast_time
         |FROM
         |    vodrs.t_sdu_user_play_history_paypredict
         |WHERE
         |    partitiondate='$partitiondate' and license='$license'
      """.stripMargin

    val df_play = spark.sql(user_play_sql)

    df_play
  }

}
