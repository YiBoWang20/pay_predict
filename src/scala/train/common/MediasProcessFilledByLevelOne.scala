package train.common

import mam.Dic
import mam.Utils._
import org.apache.spark.ml.feature.Imputer
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import mam.GetSaveData._
import scala.collection.mutable

object MediasProcessFilledByLevelOne {

  def main(args: Array[String]): Unit = {

    sysParamSetting() // Konverse - 上传 master 时，删除

    val spark = SparkSession
      .builder()
      //.master("local[6]") // Konverse - 上传 master 时，删除
//      .enableHiveSupport() // Konverse - 这个如果不影响本地运行，就不用注释；
      .getOrCreate()

    // 2 - 所有这些 文件 的读取和存储路径，全部包含到函数里，不再当参数传入，见例子 - getRawMediaData
    // 以下的这些 路径， 都不应该在存在于这个文件中， 见例子 - getRawMediaData 的读取方式
    val hdfsPath = "hdfs:///pay_predict/"
    //    val hdfsPath = ""

    val videoFirstCategoryTempPath = hdfsPath + "data/train/common/processed/videofirstcategorytemp.txt"
    val videoSecondCategoryTempPath = hdfsPath + "data/train/common/processed/videosecondcategorytemp.txt"
    val labelTempPath = hdfsPath + "data/train/common/processed/labeltemp.txt"

    // 3 - 获取原始数据
    // 在 GetAndSaveData 中，创建同名的 读取或存储函数 - spark允许，同名但是传入参数不同的 函数存在；
    // 例： 有 2个getRawMediaData，一个是我调用的，一个是你调用的，它们同名，但是传参不一样；
    // 参考 Konverse分支中，相关读取、存储数据函数的命名；
    val df_raw_medias = getRawMediaData(spark)
    printDf("输入 df_raw_medias", df_raw_medias)

    // 对数据进行处理
    val df_medias_processed = mediasProcess(df_raw_medias)
//    saveProcessedMedia(df_medias_processed)
    printDf("df_medias_processed", df_medias_processed)

    // 保存标签
    val df_label_one = getSingleStrColLabel(df_medias_processed, Dic.colVideoOneLevelClassification)
//    saveLabel(df_label_one, videoFirstCategoryTempPath)
    printDf("df_label_one", df_label_one)

    val df_label_two = getArrayStrColLabel(df_medias_processed, Dic.colVideoTwoLevelClassificationList )
//    saveLabel(df_label_two, videoSecondCategoryTempPath)
    printDf("df_label_two", df_label_two)

    val df_label_tags = getArrayStrColLabel(df_medias_processed, Dic.colVideoTagList)
//    saveLabel(df_label_tags, labelTempPath)
    printDf("df_label_tags", df_label_tags)


    // 导演 演员尚未处理 目前还未使用到
    println("媒资数据处理完成！")
  }

  def mediasProcess(df_raw_media: DataFrame) = {

    // Konverse -
    // 注意变量命名；
    // 避免使用 var；
    val df_modified_format = df_raw_media
      .select(
        col(Dic.colVideoId),
        col(Dic.colVideoTitle),
        col(Dic.colVideoOneLevelClassification),
        col(Dic.colVideoTwoLevelClassificationList),
        col(Dic.colVideoTagList),
        col(Dic.colDirectorList),
        col(Dic.colCountry),
        col(Dic.colActorList),
        col(Dic.colLanguage),
        col(Dic.colReleaseDate),
        when(col(Dic.colStorageTime).isNotNull, udfLongToTimestampV2(col(Dic.colStorageTime)))
          .otherwise(null).as(Dic.colStorageTime),
        col(Dic.colVideoTime),
        col(Dic.colScore),
        col(Dic.colIsPaid),
        col(Dic.colPackageId),
        col(Dic.colIsSingle),
        col(Dic.colIsTrailers),
        col(Dic.colSupplier),
        col(Dic.colIntroduction))
      .dropDuplicates(Dic.colVideoId)
      .na.drop("all")
      //是否单点 是否付费 是否先导片 一级分类空值
      .na.fill(
      Map(
        (Dic.colIsSingle, 0),
        (Dic.colIsPaid, 0),
        (Dic.colIsTrailers, 0),
        (Dic.colVideoOneLevelClassification, "其他")))
      .withColumn(Dic.colVideoTwoLevelClassificationList,
        when(col(Dic.colVideoTwoLevelClassificationList).isNotNull, col(Dic.colVideoTwoLevelClassificationList))
          .otherwise(Array("其他")))
      .withColumn(Dic.colVideoTagList,
        when(col(Dic.colVideoTagList).isNotNull, col(Dic.colVideoTagList))
          .otherwise(Array("其他")))

    printDf("基本处理后的med", df_modified_format)

    // 根据一级分类填充空值
    val df_mean_score_fill = meanFillAccordLevelOne(df_modified_format, Dic.colScore)
    val df_mean_video_time_fill = meanFillAccordLevelOne(df_mean_score_fill, Dic.colVideoTime)

    printDf("填充空值之后", df_mean_video_time_fill)

    df_mean_video_time_fill
  }

  /**
   * @author wj
   * @param [dfModifiedFormat ]
   * @return org.apache.spark.sql.Dataset<org.apache.spark.sql.Row>
   * @description 将指定的列使用均值进行填充
   */
  def meanFill(dfModifiedFormat: DataFrame, cols: Array[String]) = {


    val imputer = new Imputer()
      .setInputCols(cols)
      .setOutputCols(cols)
      .setStrategy("mean")

    val dfMeanFill = imputer.fit(dfModifiedFormat).transform(dfModifiedFormat)

    dfMeanFill
  }

  /**
   * @describe 根据video的视频一级分类进行相关列空值的填充
   * @author wx
   * */
  def meanFillAccordLevelOne(df_medias: DataFrame, colName: String) = {

    /**
     * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!
     * 还是有一级分类中二级分类乱入的情况
     * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!
     */
    val df_mean = df_medias
      .groupBy(Dic.colVideoOneLevelClassification)
      .agg(mean(col(colName)).as("mean_" + colName + "_accord_level_one"))

    printDf("df_mean", df_mean)

    val meanValue = df_medias
      .agg(mean(colName))
      .collectAsList()
      .get(0)
      .get(0)

    println("Mean " + colName + " of All Videos", meanValue)

    val df_mean_not_null = df_mean
      .na.fill(Map(("mean_" + colName + "_accord_level_one", meanValue)))

    printDf("df_mean_not_null", df_mean_not_null)

    val meanMap = df_mean_not_null.rdd //Dataframe转化为RDD
      .map(row =>
        row.getAs(Dic.colVideoOneLevelClassification).toString -> row.getAs("mean_" + colName + "_accord_level_one").toString)
      .collectAsMap() //将key-value对类型的RDD转化成Map
      .asInstanceOf[mutable.HashMap[String, Double]]

    println(meanMap)

    val df_mean_filled = df_medias.na.fill(Map((colName, -1)))
      .withColumn(colName, fillUseMap(meanMap)(col(colName), col(Dic.colVideoOneLevelClassification)))

    printDf("df_mean_filled", df_mean_filled)

    df_mean_filled
  }


  def getArrayStrColLabel(dfMedias: DataFrame, colName: String) = {

    val df_label = dfMedias
      .select(
        explode(
          col(colName)).as(colName))
      .dropDuplicates()
      .withColumn(Dic.colRank, row_number().over(Window.orderBy(col(colName))) - 1)
      .select(
        concat_ws("\t", col(Dic.colRank), col(colName)).cast(StringType).as(Dic.colContent))

    printDf("输出  df_label", df_label)

    df_label
  }

  def getSingleStrColLabel(df_medias: DataFrame, colName: String) = {

    val df_label = df_medias
      .select(col(colName))
      .dropDuplicates()
      .filter(!col(colName).cast("String").contains("]"))
      .withColumn(Dic.colRank, row_number().over(Window.orderBy(col(colName))) - 1)
      .select(
        concat_ws("\t", col(Dic.colRank), col(colName)).as(Dic.colContent))

    printDf("输出  df_label", df_label)

    df_label
  }


  def fillUseMap(fillMap: mutable.HashMap[String, Double]) = udf((value: Double, levelOneType: String) =>

    if (value > 0)
      value
    else {
      println(value, levelOneType)
      fillMap(levelOneType)
    }
  )


}