package nl.knaw.dans.easy.stage.fileitem

import java.io.File
import java.net.URL
import java.sql.SQLException

import com.yourmediashelf.fedora.client.FedoraClientException
import nl.knaw.dans.easy.stage.lib.FOXML.{getDirFOXML, getFileFOXML}
import nl.knaw.dans.easy.stage.lib.Util._
import nl.knaw.dans.easy.stage.lib._
import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

object EasyStageFileItem {
  val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]) {
    val props = new PropertiesConfiguration(new File(System.getProperty("app.home"), "cfg/application.properties"))
    Fedora.setFedoraConnectionSettings(props.getString("fcrepo.url"), props.getString("fcrepo.user"), props.getString("fcrepo.password"))
    val conf = new FileItemConf(args)
    getSettingsRows(conf).map {
      _.foreach { settings =>
        run(settings)
          .map(_ => log.info(s"Staging SUCCESS of $settings"))
          .recover { case t: Throwable =>
            log.error(s"Staging FAIL of $settings", t)
            if (t.isInstanceOf[SQLException] || t.isInstanceOf[FedoraClientException]) return
          }
      }
    }.recover { case t: Throwable => log.error(s"Staging FAIL of $conf", t) }
  }

  private def getSettingsRows(conf: FileItemConf): Try[Seq[FileItemSettings]] =
    if (conf.datasetId.isDefined)
      Success(Seq(FileItemSettings(conf)))
    else if (conf.csvFile.isEmpty)
      Failure(new Exception("neither datasetId (option -i) nor CSV file (optional trail argument) specified"))
    else {
      val trailArgs = Seq(conf.sdoSetDir.apply().toString)
      CSV(conf.csvFile.apply(), conf.longOptionNames).map {
        case (csv, warning) =>
          warning.map(msg => log.warn(msg))
          val rows = csv.getRows
          if (rows.isEmpty) log.warn(s"Empty CSV file")
          rows.map(options => FileItemSettings(options ++ trailArgs))
      }
    }

  def run(implicit s: FileItemSettings): Try[Unit] = {
    log.debug(s"executing: $s")
    for {
      datasetId        <- getValidDatasetId(s)
      sdoSetDir        <- mkdirSafe(s.sdoSetDir)
      datasetSdoSetDir <- mkdirSafe(new File(sdoSetDir, datasetId.replace(":", "_")))
      (parentId, parentPath, newElements)  <- getPathElements()
      items            <- Try { getItemsToStage(newElements, datasetSdoSetDir, parentId) }
      _                = log.debug(s"Items to stage: $items")
      _                = items.init.foreach { case (sdo, path, parentRelation) => createFolderSdo(sdo, new File(new File(parentPath), path).toString, parentRelation) }
      _                = items.last match {case (sdo, path, parentRelation) => createFileSdo(sdo, new File(new File(parentPath), path).toString, parentRelation) }
    } yield ()
  }

  def getPathElements()(implicit s: FileItemSettings): Try[(String, String, Seq[String])] = Try {
    // TODO: refactor this to remove the need for get and toString
    EasyFilesAndFolders.getExistingParent(s.pathInDataset.get.toString, s.datasetId.get).get match {
      case Some(path) =>
        log.debug(s"Found parent path folder in repository: $path")
        val parentId = EasyFilesAndFolders.getPathId(new File(path), s.datasetId.get).get.get
        (parentId, path, s.pathInDataset.get.toString.replaceFirst(s"^$path", "").split("/").toSeq)
      case None =>
        log.debug("No parent path found in repository, using dataset as parent")
        (s.datasetId.get, "", s.pathInDataset.get.toString.split("/").toSeq)
    }

  }

  def getItemsToStage(pathElements: Seq[String], datasetSdoSet: File, existingFolderId: String): Seq[(File, String, (String, String))] = {
    getPaths(pathElements)
    .foldLeft(Seq[(File, String, (String, String))]())((items, path) => {
      items match {
        case s@Seq() => s :+ (new File(datasetSdoSet, toSdoName(path)), path, ("object" -> existingFolderId))
        case seq =>
          val parentFolderSdoName = seq.last match { case (sdo, _,  _) => sdo.getName}
          seq :+ (new File(datasetSdoSet, toSdoName(path)), path, ("objectSDO" -> parentFolderSdoName))
      }
    })
  }

  def getPaths(path: Seq[String]): Seq[String] =
    if(path.isEmpty) Seq()
    else path.tail.scanLeft(path(0))((acc, next) => s"$acc/$next")


  def createFileSdo(sdoDir: File, path: String, parent: (String,String))(implicit s: FileItemSettings): Try[Unit] = {
    log.debug(s"Creating file SDO: ${path}")
    sdoDir.mkdir()
    val location  = new URL(new URL(s.storageBaseUrl), s.pathInStorage.get.toString).toString
    for {
      mime <- Try{s.format.get}
      _ <- writeJsonCfg(sdoDir, JSON.createFileCfg(location, mime, parent, s.subordinate))
      _ <- writeFoxml(sdoDir, getFileFOXML(s.pathInDataset.get.getName, s.ownerId, mime))
      _ <- writeFileMetadata(sdoDir, EasyFileMetadata(s).toString())
    } yield ()
  }

  def createFolderSdo(sdoDir: File, path: String, parent: (String,String))(implicit s: FileItemSettings): Try[Unit] = {
    log.debug(s"Creating folder SDO: $path")
    sdoDir.mkdir()
    for {
      _ <- writeJsonCfg(sdoDir,JSON.createDirCfg(parent, s.subordinate))
      _ <- writeFoxml(sdoDir, getDirFOXML(path, s.ownerId))
      _ <- writeItemContainerMetadata(sdoDir,EasyItemContainerMd(path))
    } yield ()
  }

  private def getValidDatasetId(s: FileItemSettings): Try[String] =
    if (s.datasetId.isEmpty)
      Failure(new Exception(s"no datasetId provided"))
    else if (Fedora.findObjects(s"pid~${s.datasetId.get}").isEmpty)
      Failure(new Exception(s"${s.datasetId.get} does not exist in repository"))
    else
      Success(s.datasetId.get)

  def toSdoName(path: String): String =
    path.replaceAll("[/.]", "_").replaceAll("^_", "")
}