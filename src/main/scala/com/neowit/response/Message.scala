package com.neowit.response

import com.neowit.response.Message.MessageId
import com.neowit.utils.JsonSupport
import spray.json._
/**
  * Author: Andrey Gavrikov
  * Date: 01/01/2017
  */
object Message {
    type MessageId = Int
    private var COUNTER = 0
    def getNextId(): MessageId = {
        COUNTER += 1
        COUNTER
    }
}
sealed trait Message extends JsonSupport{
    val id: MessageId = Message.getNextId()
    val msgType: MessageType
    val text: String
    val data: Map[String, Any]
    val details: List[MessageDetail]

    def toJson: JsValue = {
        val msgData = Map("id"-> id, "text" -> text, "type" -> msgType) ++ ResponseWriter.ensureNoNulls(data)
        msgData.toJson
    }
}
case class InfoMessage(text: String, data: Map[String, Any] = Map(), details: List[MessageDetail] = Nil) extends Message {
    override val msgType: MessageType = INFO
}
case class WarnMessage(text: String, data: Map[String, Any] = Map(), details: List[MessageDetail] = Nil) extends Message {
    override val msgType: MessageType = WARN
}
case class ErrorMessage(text: String, data: Map[String, Any] = Map(), details: List[MessageDetail] = Nil) extends Message {
    override val msgType: MessageType = ERROR
}
case class DebugMessage(text: String, data: Map[String, Any] = Map(), details: List[MessageDetail] = Nil) extends Message {
    override val msgType: MessageType = DEBUG
}
// text is section name, e.g. "ERROR"
/*
case class SectionMessage(text: String, data: Map[String, Any] = Map(), details: List[MessageDetail] = Nil) extends Message {
    override val msgType: MessageType = SECTION
}
*/
//case class SectionDetailText(message: SectionMessage, text: String)

case class KeyValueMessage(data: Map[String, Any] = Map()) extends Message {
    override val text: String = ""
    override val details: List[MessageDetail] = Nil
    override val msgType: MessageType = KEY_VALUE
}
case class JsonMessage(jsValue: JsValue) extends Message {
    override val text: String = ""
    override val details: List[MessageDetail] = Nil
    override val data: Map[String, Any] = Map.empty[String, Any]
    override val msgType: MessageType = JSON_VALUE

    override def toJson: JsValue = jsValue
}

sealed trait MessageDetail {
    val parentMessage: Message
}
case class MessageDetailMap(parentMessage: Message, data: Map[String, Any]) extends MessageDetail with JsonSupport {
    def toJson: JsValue = {
        val msgData = Map("messageId"-> parentMessage.id) ++ ResponseWriter.ensureNoNulls(data)
        msgData.toJson
    }
}
case class MessageDetailText(parentMessage: Message, text: String) extends MessageDetail

case class ArbitraryTypeMessage(msgType: MessageType, text: String, data: Map[String, Any] = Map(), details: List[MessageDetail] = Nil) extends Message

