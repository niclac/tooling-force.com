/*
 * Copyright (c) 2014 Andrey Gavrikov.
 * this file is part of tooling-force.com application
 * https://github.com/neowit/tooling-force.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.neowit.apex

import com.neowit.utils.{FileUtils, Logging, Config}
import com.sforce.soap.partner.PartnerConnection
import com.sforce.soap.metadata._
import com.sforce.soap.tooling._

import scala.concurrent._
import scala.Some
import java.io.File

/**
 * manages local data store related to specific project
 */
object Session {
    def apply(appConfig: Config) = new Session(appConfig)
}

/**
 * Session has following responsibilities
 * 1. Maintains/stores persistent connection and can resue connection
 * 2. Maintains cache of some important information about project metadata (Pages, Classes, etc)
 *
 * @param config - main application config
 */
class Session(config: Config) extends Logging {

    private val sessionProperties = config.lastSessionProps
    private var connectionPartner:Option[PartnerConnection] = None
    private var connectionMetadata:Option[MetadataConnection] = None
    private var connectionTooling:Option[com.sforce.soap.tooling.SoapConnection] = None
    private var connectionApex:Option[com.sforce.soap.apex.SoapConnection] = None

    //when user wants to work with files from one org and deploy them in another org we can not use stored session
    lazy val callingAnotherOrg:Boolean = config.getProperty("callingAnotherOrg").getOrElse("false").toBoolean

    def getConfig = config


    def storeSessionData() {
        config.storeSessionProps()
    }
    def getSavedConnectionData = {

        if (!callingAnotherOrg) {
            (sessionProperties.getPropertyOption("sessionId"), sessionProperties.getPropertyOption("serviceEndpoint"))
        } else {
            (None, None)
        }
    }
    def storeConnectionData(connectionConfig: com.sforce.ws.ConnectorConfig) {
        if (!callingAnotherOrg) {
            sessionProperties.setProperty("sessionId", connectionConfig.getSessionId)
            sessionProperties.setProperty("serviceEndpoint", connectionConfig.getServiceEndpoint)
        } else {
            sessionProperties.remove("sessionId")
        }
        storeSessionData()
    }
    def setData(key: String, data: Map[String, Any]) = {
        sessionProperties.setJsonData(key, data)
    }
    def getData(key: String): Map[String, Any] = {
        sessionProperties.getJsonData(key)
    }

    /**
     * return relative path inside project folder
     * this path is used as key in session
     * @param file - resource under project folder
     * @return - string, looks like: unpackaged/pages/Hello.page
     */
    def getRelativePath(file: File): String = {
        val projectPath = config.projectDir.getAbsolutePath + File.separator
        val res = file.getAbsolutePath.substring(projectPath.length)
        res
    }
    /**
     * return relative path inside project folder
     * this path is used as key in session
     * @param file - resource under project folder
     * @return - string, looks like: unpackaged/pages/Hello.page
     */
    def getKeyByFile(file: File): String = {
        val relativePath = getRelativePath(file)
        getKeyByRelativeFilePath(relativePath)
    }

    def getKeyByRelativeFilePath(filePath: String): String = {
        val relPath = if (filePath.startsWith("src" + File.separator))
            filePath.replaceFirst("src" + File.separator, "unpackaged" + File.separator)
        else
            filePath
        if (relPath.endsWith("-meta.xml"))
            relPath.substring(0, relPath.length - "-meta.xml".length)
        else
            relPath
    }

    /**
     * keys usually look like so: unpackaged/classes/Messages.cls
     * @param dirName - e.g. "classes"
     * @param fileName - e.g. "Messages.cls"
     * @return
     */
    def findFile(dirName: String, fileName: String): Option[String] = {
        if (sessionProperties.containsKey("unpackaged" + File.separator + dirName + File.separator + fileName)) {
            Some("src" + File.separator + dirName + File.separator + fileName)
        } else {
            //looks like this file is not in session yet, last attempt, check if local file exists
            val folder = new File(config.srcDir, dirName)
            val file = new File(folder, fileName)
            if (file.canRead)
                Some(getRelativePath(file))
            else
                None
        }

    }

    //Windows does not support cp -p (preserve last modified date) copy so have to assume that copy of all project files
    //on refresh takes no longer than this number of seconds
    private val SESSION_TO_FILE_TIME_DIFF_TOLERANCE_SEC = if (config.isUnix) 0 else 1000 * 3

    //if .cls and .cls-meta.xml file time differs by this number of seconds (or less) we consider time equal
    private val FILE_TO_META_TIME_DIFF_TOLERANCE_SEC = 1000

    /**
     * NOTE: this method does not check if provided file is a valid apex project file
     * @return true if file does not exist in session.properties or its hash does not match
     */
    def isModified(file: File): Boolean = {
        val prefix =  if (file.getName.endsWith("-meta.xml")) "meta" else ""
        val fileData = getData(getKeyByFile(file))
        val useMD5Hash = !fileData.getOrElse(MetadataType.MD5, "").asInstanceOf[String].isEmpty

        val hashFieldName = if (useMD5Hash) MetadataType.MD5 else MetadataType.CRC32
        val useHashCheck = fileData.contains(hashFieldName)

        val fileDiffBySessionData = useHashCheck match {
          case true =>
              val hashDifference = fileData.get(prefix + hashFieldName) match {
                  case Some(storedHash) =>
                      if (useMD5Hash) FileUtils.getMD5Hash(file) != storedHash
                      else FileUtils.getCRC32Hash(file)!= storedHash
                  case None => true //file is not listed in session, so must be new
              }
              hashDifference
          case false =>
              val fileTimeNewerThanSessionTimeData = fileData.get(prefix + MetadataType.LOCAL_MILLS) match {
                  case Some(x) => Math.abs(file.lastModified() - x.asInstanceOf[Long]) > SESSION_TO_FILE_TIME_DIFF_TOLERANCE_SEC
                  case None => true //file is not listed in session, so must be new
              }
              fileTimeNewerThanSessionTimeData

        }
        fileDiffBySessionData
    }

    private def getPartnerConnection: PartnerConnection = {
        val conn = connectionPartner match {
          case Some(connection) => connection
          case None =>
              //check if we have previously established session id
              getSavedConnectionData match {
                  case (Some(sessionId), Some(serviceEndpoint)) =>
                      //use cached data
                      Connection.getPartnerConnection(config, sessionId, serviceEndpoint)
                  case _ =>
                      //login explicitly
                      val _conn = Connection.createPartnerConnection(config)
                      storeConnectionData(_conn.getConfig)
                      _conn
              }

        }
        connectionPartner = Some(conn)

        conn
    }
    private def getMetadataConnection: MetadataConnection = {
        import com.sforce.soap.metadata._
        val conn = connectionMetadata match {
            case Some(connection) => connection
            case None => Connection.getMetadataConnection(config, getPartnerConnection)
        }
        val debugHeader = new DebuggingHeader_element()
        debugHeader.setDebugLevel(LogType.valueOf(config.logLevel))
        conn.__setDebuggingHeader(debugHeader)

        connectionMetadata = Some(conn)
        conn
    }

    private def getToolingConnection: SoapConnection = {
        import com.sforce.soap.tooling._
        val conn = connectionTooling match {
            case Some(connection) => connection
            case None => Connection.getToolingConnection(config, getPartnerConnection)
        }
        val debugHeader = new DebuggingHeader_element()
        debugHeader.setDebugLevel(LogType.valueOf(config.logLevel))
        conn.__setDebuggingHeader(debugHeader)

        connectionTooling = Some(conn)
        conn
    }

    private def getApexConnection: com.sforce.soap.apex.SoapConnection = {
        import com.sforce.soap.apex._
        val conn = connectionApex match {
            case Some(connection) => connection
            case None => Connection.getApexConnection(config, getPartnerConnection)
        }
        connectionApex = Some(conn)

        /*
        val infoAll = new LogInfo()
        infoAll.setCategory(LogCategory.All)
        infoAll.setLevel(LogCategoryLevel.Finest)

        val infoApex = new LogInfo()
        infoApex.setCategory(LogCategory.Apex_code)
        infoApex.setLevel(LogCategoryLevel.Finest)

        val infoProfiling = new LogInfo()
        infoProfiling.setCategory(LogCategory.Apex_profiling)
        infoProfiling.setLevel(LogCategoryLevel.Finest)

        val infoDB = new LogInfo()
        infoDB.setCategory(LogCategory.Db)
        infoDB.setLevel(LogCategoryLevel.Finest)

        conn.setDebuggingHeader(Array(infoAll, infoApex, infoProfiling, infoDB), LogType.Detail)
        //conn.setDebuggingHeader(Array(), LogType.Detail)
        */


        val debugHeader = new DebuggingHeader_element()
        //debugHeader.setCategories(Array(infoAll, infoApex, infoProfiling, infoDB))
        debugHeader.setDebugLevel(LogType.valueOf(config.logLevel))
        conn.__setDebuggingHeader(debugHeader)

        conn
    }

    def withRetry(codeBlock: => Any) = {
        try {
            codeBlock
        } catch {
            case ex:com.sforce.ws.SoapFaultException if "INVALID_SESSION_ID" == ex.getFaultCode.getLocalPart =>
                logger.debug("Session is invalid or has expired. Will run the process again with brand new connection. ")
                logger.trace(ex)
                reset()
                //run once again
                codeBlock
            case ex:Throwable =>
                throw ex
        }
    }
    def reset() {
        sessionProperties.remove("sessionId")
        sessionProperties.remove("serviceEndpoint")
        storeSessionData()
        connectionPartner = None
        connectionMetadata = None
        connectionTooling = None
    }

    def getServerTimestamp = {
        withRetry {
            getPartnerConnection.getServerTimestamp
        }.asInstanceOf[com.sforce.soap.partner.GetServerTimestampResult]
    }

    def retrieve(retrieveRequest: RetrieveRequest ):RetrieveResult = {
        val retrieveResult = withRetry {
            val conn = getMetadataConnection
            val asyncResult = wait(conn, conn.retrieve(retrieveRequest))
            val _retrieveResult = conn.checkRetrieveStatus(asyncResult.getId)
            _retrieveResult
        }.asInstanceOf[RetrieveResult]

        retrieveResult
    }

    def deploy(zipFile: Array[Byte], deployOptions: DeployOptions ):(DeployResult, String) = {
        var log = ""
        val deployResult = withRetry {
            val conn = getMetadataConnection
            val asyncResult = wait(conn, conn.deploy(zipFile, deployOptions))
            val _deployResult = conn.checkDeployStatus(asyncResult.getId, true)
            log = if (null != conn.getDebuggingInfo) conn.getDebuggingInfo.getDebugLog else ""
            _deployResult
        }.asInstanceOf[DeployResult]

        (deployResult, log)
    }

    def describeMetadata(apiVersion: Double ):DescribeMetadataResult = {
        val describeResult = withRetry {
            val conn = getMetadataConnection
            conn.describeMetadata(apiVersion)
        }.asInstanceOf[DescribeMetadataResult]
        describeResult
    }

    def listMetadata(queries: Array[ListMetadataQuery], apiVersion: Double ):Array[FileProperties] = {
        //sfdc allows only 3 queries per call, so have to micro batch calls
        def microBatch(queriesBatch: Array[ListMetadataQuery], conn: MetadataConnection,
                       propsSoFar: Array[FileProperties]):Array[FileProperties] = {
            queriesBatch match  {
                case Array() => //end of query list, return result
                    propsSoFar
                case Array(_, _*) =>
                    val _queries = queriesBatch.take(3)
                    logger.trace("About to process " + _queries.map(_.getType).mkString("; "))
                    val props = conn.listMetadata(_queries, apiVersion)
                    //sometimes SFDC returns props with fullname = "_Default" and type = null
                    //exclude those
                    val propsFiltered = props.filter(_.getType != null)
                    logger.trace("Props: " + propsFiltered.map(_.getType).mkString("; "))
                    microBatch(queriesBatch.drop(3), conn, propsFiltered ++ propsSoFar)

            }

        }
        val fileProperties = withRetry {
            val conn = getMetadataConnection
            //conn.listMetadata(queries, apiVersion)
            microBatch(queries, conn, Array[FileProperties]())
        }.asInstanceOf[Array[FileProperties]]
        fileProperties
    }


    def executeAnonymous(apexCode: String ):(com.sforce.soap.apex.ExecuteAnonymousResult, String) = {
        var log = ""
        val executeAnonymousResult = withRetry {
            val conn = getApexConnection
            val res = conn.executeAnonymous(apexCode)
            log = if (null != conn.getDebuggingInfo) conn.getDebuggingInfo.getDebugLog else ""
            res

        }.asInstanceOf[com.sforce.soap.apex.ExecuteAnonymousResult]
        (executeAnonymousResult, log)
    }

    //TODO - when API v30 is available consider switching to synchronous version of retrieve call
    private val ONE_SECOND = 1000
    private val MAX_NUM_POLL_REQUESTS = config.getProperty("maxPollRequests").getOrElse[String]("100").toInt
    private def wait(connection: MetadataConnection, asyncResult: AsyncResult): AsyncResult = {
        val waitTimeMilliSecs = config.getProperty("pollWaitMillis").getOrElse("" + (ONE_SECOND * 5)).toInt
        var attempts = 0
        var _asyncResult = asyncResult
        while (!_asyncResult.isDone) {
            blocking {
                Thread.sleep(waitTimeMilliSecs)
                logger.info("waiting result, attempt " + attempts)
            }
            attempts += 1
            if (!asyncResult.isDone && ((attempts +1) > MAX_NUM_POLL_REQUESTS)) {
                throw new Exception("Request timed out.  If this is a large set " +
                    "of metadata components, check that the time allowed " +
                    "by --maxPollRequests is sufficient and --pollWaitMillis is not too short.")
            }
            _asyncResult = connection.checkStatus(Array(_asyncResult.getId))(0)
            logger.info("Status is: " + _asyncResult.getState)
        }
        if (AsyncRequestState.Completed != _asyncResult.getState) {
            throw new Exception(_asyncResult.getStatusCode + " msg:" + _asyncResult.getMessage)
        }
        _asyncResult
    }


}
