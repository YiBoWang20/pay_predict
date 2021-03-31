package train.userpay

import mam.{Dic, SparkSessionInit}
import mam.GetSaveData.{getDataFromXXK, getProcessedUserMeta, getTrainUser, getUserProfileOrderPart, getUserProfilePlayPart, getUserProfilePreferencePart, getVideoFirstCategory, getVideoLabel, getVideoSecondCategory, saveDataSet}
import mam.SparkSessionInit.spark
import mam.Utils.{printDf, sysParamSetting}
import org.apache.spark.ml.feature.{StringIndexer, StringIndexerModel}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, lit, udf, when}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object TrainSetProcess {


  def main(args: Array[String]): Unit = {


    // 1 SparkSession init
    sysParamSetting
    SparkSessionInit.init()

    val trainTime = args(0) + " " + args(1)
    println("trainTime", trainTime)

    // 2 Get Data

    val df_user_profile_play = getUserProfilePlayPart(spark, trainTime, "train")
    printDf("输入 df_user_profile_play", df_user_profile_play)

    val df_user_profile_pref = getUserProfilePreferencePart(spark, trainTime, "train")
    printDf("输入 df_user_profile_pref", df_user_profile_pref)

    val df_user_profile_order = getUserProfileOrderPart(spark, trainTime, "train")
    printDf("输入 df_user_profile_order", df_user_profile_order)

    val df_video_first_category = getVideoFirstCategory()
    printDf("输入 df_video_first_category", df_video_first_category)

    val df_video_second_category = getVideoSecondCategory()
    printDf("输入 df_video_second_category", df_video_second_category)

    val df_label = getVideoLabel()
    printDf("输入 df_label", df_label)

    val df_train_user = getTrainUser(spark, trainTime)
    printDf("输入 df_train_user", df_train_user)

    //    val df_play_vec = getDataFromXXK("train", "train_play_vector_" + playsNum)
    //    printDf("输入 df_play_vec", df_play_vec)

    val df_click_Meta = getProcessedUserMeta()
    printDf("df_click_Meta", df_click_Meta)

    // 3 Train Set Process
    val df_train_set = trainSetProcess(df_user_profile_play, df_user_profile_pref, df_user_profile_order,
      df_video_first_category, df_video_second_category, df_label, df_train_user, df_click_Meta)

    // 4 Save Train Users
    saveDataSet(trainTime, df_train_set, "train")
    printDf("输出 df_train_set", df_train_set)
    println("Train Set Process Done！")


  }


  def trainSetProcess(df_user_profile_play: DataFrame, df_user_profile_pref: DataFrame, df_user_profile_order: DataFrame,
                      df_video_first_category: DataFrame, df_video_second_category: DataFrame, df_label: DataFrame,
                      df_train_user: DataFrame, df_click_meta: DataFrame): DataFrame = {


    /**
     * Process User Profile Data
     */
    val joinKeysUserId = Seq(Dic.colUserId)
    val df_profile_play_pref = df_user_profile_play.join(df_user_profile_pref, joinKeysUserId, "left")
    val df_user_profile = df_profile_play_pref.join(df_user_profile_order, joinKeysUserId, "left")


    /**
     * 空值填充
     */
    val colList = df_user_profile.columns.toList
    val colTypeList = df_user_profile.dtypes.toList
    val mapColList = ArrayBuffer[String]()
    for (elem <- colTypeList) {
      if (!elem._2.equals("StringType") && !elem._2.equals("IntegerType")
        && !elem._2.equals("DoubleType") && !elem._2.equals("LongType")) {
        mapColList.append(elem._1)
      }
    }


    val numColList = colList.diff(mapColList)

    val df_userProfile_filled = df_user_profile
      .na.fill(-1, List(Dic.colDaysSinceLastPurchasePackage, Dic.colDaysSinceLastClickPackage,
      Dic.colDaysFromLastActive, Dic.colDaysSinceFirstActiveInTimewindow))
      .na.fill(0, numColList)

    /**
     * 偏好部分标签处理
     */

    // 一级标签
    val videoFirstCategoryMap = df_video_first_category.rdd //Dataframe转化为RDD
      .map(row => row.getAs(Dic.colVideoOneLevelClassification).toString -> row.getAs(Dic.colIndex).toString)
      .collectAsMap() //将key-value对类型的RDD转化成Map
      .asInstanceOf[mutable.HashMap[String, Int]]

    // 二级标签
    val videoSecondCategoryMap = df_video_second_category.rdd //Dataframe转化为RDD
      .map(row => row.getAs(Dic.colVideoTwoLevelClassificationList).toString -> row.getAs(Dic.colIndex).toString)
      .collectAsMap() //将key-value对类型的RDD转化成Map
      .asInstanceOf[mutable.HashMap[String, Int]]


    // 类别型标签
    val labelMap = df_label.rdd //Dataframe转化为RDD
      .map(row => row.getAs(Dic.colVideoTagList).toString -> row.getAs(Dic.colIndex).toString)
      .collectAsMap() //将key-value对类型的RDD转化成Map
      .asInstanceOf[mutable.HashMap[String, Int]]



    //    var videoFirstCategoryMap: Map[String, Int] = Map()
    //    var videoSecondCategoryMap: Map[String, Int] = Map()
    //    var labelMap: Map[String, Int] = Map()

    //    // 一级分类
    //    var conList = df_video_first_category.collect()
    //    for (elem <- conList) {
    //      val s = elem.toString()
    //      videoFirstCategoryMap += (s.substring(1, s.length - 1).split("\t")(1) -> s.substring(1, s.length - 1).split("\t")(0).toInt)
    //
    //    }
    //    //二级分类
    //    conList = df_video_second_category.collect()
    //    for (elem <- conList) {
    //      val s = elem.toString()
    //      videoSecondCategoryMap += (s.substring(1, s.length - 1).split("\t")(1) -> s.substring(1, s.length - 1).split("\t")(0).toInt)
    //
    //    }
    //
    //    //标签
    //    conList = df_label.collect()
    //    for (elem <- conList) {
    //      val s = elem.toString()
    //      labelMap += (s.substring(1, s.length - 1).split("\t")(1) -> s.substring(1, s.length - 1).split("\t")(0).toInt)
    //
    //    }


    val prefColumns = List(Dic.colVideoOneLevelPreference, Dic.colVideoTwoLevelPreference,
      Dic.colMovieTwoLevelPreference, Dic.colSingleTwoLevelPreference, Dic.colInPackageVideoTwoLevelPreference)

    var df_userProfile_split_pref1 = df_userProfile_filled

    def udfFillPreference = udf(fillPreference _)

    def fillPreference(prefer: Map[String, Int], offset: Int) = {
      if (prefer == null) {
        null
      } else {
        val mapArray = prefer.toArray
        if (mapArray.length > offset - 1) {
          mapArray(offset - 1)._1
        } else {
          null
        }
      }
    }

    for (elem <- prefColumns) {
      df_userProfile_split_pref1 = df_userProfile_split_pref1.withColumn(elem + "_1", udfFillPreference(col(elem), lit(1)))
        .withColumn(elem + "_2", udfFillPreference(col(elem), lit(2)))
        .withColumn(elem + "_3", udfFillPreference(col(elem), lit(3)))
    }


    def udfFillPreferenceIndex = udf(fillPreferenceIndex _)

    def fillPreferenceIndex(prefer: String, mapLine: String) = {
      if (prefer == null) {
        null
      } else {
        var tempMap: Map[String, Int] = Map()
        val lineIterator1 = mapLine.split(",")
        lineIterator1.foreach(m => tempMap += (m.split(" -> ")(0) -> m.split(" -> ")(1).toInt))
        tempMap.get(prefer)
      }
    }

    var df_userProfile_split_pref2 = df_userProfile_split_pref1
    for (elem <- prefColumns) {
      if (elem.contains(Dic.colVideoOneLevelPreference)) {
        df_userProfile_split_pref2 = df_userProfile_split_pref2.withColumn(elem + "_1", udfFillPreferenceIndex(col(elem + "_1"), lit(videoFirstCategoryMap.mkString(","))))
          .withColumn(elem + "_2", udfFillPreferenceIndex(col(elem + "_2"), lit(videoFirstCategoryMap.mkString(","))))
          .withColumn(elem + "_3", udfFillPreferenceIndex(col(elem + "_3"), lit(videoFirstCategoryMap.mkString(","))))
      } else {
        df_userProfile_split_pref2 = df_userProfile_split_pref2.withColumn(elem + "_1", udfFillPreferenceIndex(col(elem + "_1"), lit(videoSecondCategoryMap.mkString(","))))
          .withColumn(elem + "_2", udfFillPreferenceIndex(col(elem + "_2"), lit(videoSecondCategoryMap.mkString(","))))
          .withColumn(elem + "_3", udfFillPreferenceIndex(col(elem + "_3"), lit(videoSecondCategoryMap.mkString(","))))
      }
    }

    var df_userProfile_split_pref3 = df_userProfile_split_pref2
    for (elem <- prefColumns) {
      if (elem.equals(Dic.colVideoOneLevelPreference)) {
        df_userProfile_split_pref3 = df_userProfile_split_pref3.na.fill(videoFirstCategoryMap.size, List(elem + "_1", elem + "_2", elem + "_3"))
      } else {
        df_userProfile_split_pref3 = df_userProfile_split_pref3.na.fill(videoSecondCategoryMap.size, List(elem + "_1", elem + "_2", elem + "_3"))
      }
    }


    val columnTypeList = df_userProfile_split_pref3.dtypes.toList
    val columnList = ArrayBuffer[String]()
    for (elem <- columnTypeList) {
      if (elem._2.equals("StringType") || elem._2.equals("IntegerType")
        || elem._2.equals("DoubleType") || elem._2.equals("LongType")) {
        columnList.append(elem._1)
      }
    }


    /**
     * 将添加用户的标签信息
     */
    val df_train_user_prof = df_userProfile_split_pref3.select(columnList.map(df_userProfile_split_pref3.col(_)): _*)


    /**
     * 添加用户的点击类提取特征
     */


    val df_train_click = df_train_user_prof.join(df_click_meta, joinKeysUserId, "left")
      .na.fill(-1)


    val click_cols = df_click_meta.columns

    val df_train_click_encode = clicksEncoder(click_cols, df_train_click)

    val df_user_click = df_train_click_encode
      .drop(
        Dic.colDeviceMsg, Dic.colFeatureCode, Dic.colBigVersion,
        Dic.colProvince, Dic.colCity, Dic.colCityLevel, Dic.colAreaId
      )

    printDf("df_user_click", df_user_click)


    /**
     * 添加用户标签
     */

    val df_train_set = df_train_user.join(df_user_click, joinKeysUserId, "left")
    df_train_set


  }


  def clicksEncoder(click_col: Array[String], df_data_set: DataFrame) = {


    var df_raw_click_index = df_data_set
    var indexModel: StringIndexerModel = null

    for (col <- click_col) {
      if (!col.equals(Dic.colUserId)) {
        indexModel = new StringIndexer()
          .setInputCol(col)
          .setOutputCol(col + "_index")
          .setHandleInvalid("keep")
          .fit(df_raw_click_index)

        df_raw_click_index = indexModel.transform(df_raw_click_index)
      }
    }

    printDf("df_raw_click_index", df_raw_click_index)


    df_raw_click_index

  }


}
