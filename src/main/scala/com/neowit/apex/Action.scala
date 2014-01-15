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

import com.neowit.utils.{ZipUtils, FileUtils, Logging, Config}
import java.io.{File, FileOutputStream}

class UnsupportedActionError(msg: String) extends Error(msg: String)

object ActionFactory {

    def getAction(session:Session, name: String): Option[Action] = {
        name match {
          case "refresh" => Some(new RefreshMetadata(session))
          case "listModified" => Some(new ListModified(session))
          case _ => throw new UnsupportedActionError(name + " is not supported")
        }


    }
}
trait Action extends Logging {
    def act
}
trait AsyncAction extends Action {
}

abstract class MetadataAction(session: Session) extends AsyncAction {
    val config:Config = session.getConfig

}

/**
 * 'refresh' action is 'retrieve' for all elements specified in package.xml
 *@param session - SFDC session
 */
case class RefreshMetadata(session: Session) extends MetadataAction(session: Session) {
    import com.sforce.soap.metadata.RetrieveRequest


    def act {
        val retrieveRequest = new RetrieveRequest()
        retrieveRequest.setApiVersion(config.apiVersion)
        setUpackaged(retrieveRequest)
        val retrieveResult = session.retrieve(retrieveRequest)
        updateFromRetrieve(retrieveResult)


    }
    def setUpackaged(retrieveRequest: RetrieveRequest) {
        val metaXml = new MetaXml(session.getConfig)
        val unpackagedManifest = metaXml.getPackageXml
        logger.debug("Manifest file: " + unpackagedManifest.getAbsolutePath)

        retrieveRequest.setUnpackaged(metaXml.getPackage)
    }
    /**
     * using ZIP file produced, for example, as a result of Retrieve operation
     * extract content and generate response file
     */
    def updateFromRetrieve(retrieveResult: com.sforce.soap.metadata.RetrieveResult) {

        //val outputPath = appConfig.srcDir.getParentFile.getAbsolutePath
        //extract in temp area first
        val resultsFile = FileUtils.createTempFile("retrieveResult", ".zip")
        val out = new FileOutputStream(resultsFile)
        try {
            out.write(retrieveResult.getZipFile)
        } finally {
            out.close()
        }
        val tempFolder = FileUtils.createTempDir(config)
        val propertyByFilePath = new collection.mutable.HashMap[String,  com.sforce.soap.metadata.FileProperties]()
        try {
            val localDateByFName = ZipUtils.extract(resultsFile, tempFolder)
            //update session with file properties
            for (fileProp <- retrieveResult.getFileProperties) {
                val key = MetadataType.getKey(fileProp)
                val lastModifiedLocally = localDateByFName(fileProp.getFileName)
                val valueMap = MetadataType.getValueMap(fileProp) ++ Map(Session.LOCAL_MILLS -> String.valueOf(lastModifiedLocally))
                session.setData(key, valueMap)

                propertyByFilePath.put(fileProp.getFileName, fileProp)
            }
        } finally {
            session.storeSessionData()
            resultsFile.delete()
        }
        config.responseWriter.println("RESULT=SUCCESS")
        config.responseWriter.println("result.folder=" + tempFolder.getAbsolutePath)
        config.responseWriter.println("file-count=" + propertyByFilePath.size)
    }
}

/**
 * list locally modified files using data from session.properties
 * @param session
 */
case class ListModified(session: Session) extends MetadataAction(session: Session) {
    def act {
        //check if package.xml is modified
        val packageXml = new MetaXml(config)
        val packageXmlFile = packageXml.getPackageXml
        //val packageXmlData = session.getData(session.getKeyByFile(packageXmlFile))

        //logger.debug("packageXmlData=" + packageXmlData)
        val allFiles = packageXmlFile ::  FileUtils.listFiles(config.srcDir)
        val modifiedFiles = allFiles.filter(session.isModified(_))
        config.responseWriter.println("RESULT=SUCCESS")
        config.responseWriter.println("file-count=" + modifiedFiles.size)
        config.responseWriter.println("# MODIFIED FILE LIST START")
        for(f <- modifiedFiles) {
            config.responseWriter.println(session.getRelativePath(f))
        }
        config.responseWriter.println("# MODIFIED FILE LIST END")

    }

}
