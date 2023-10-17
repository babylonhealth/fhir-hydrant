package com.emed.hydrant

import scala.util.{ Success, Try }

import io.circe.{ Decoder, Encoder, Json }

/** Basically the fhir primitive types, with the addition of int64 and a 'ComplexType' case class wrapper */
sealed trait ParamType { def entryName: String }
enum PrimitiveParamType(val entryName: String) extends ParamType {
  case base64Binary extends PrimitiveParamType("base64Binary")
  case boolean      extends PrimitiveParamType("boolean")
  case canonical    extends PrimitiveParamType("canonical")
  case code         extends PrimitiveParamType("code")
  case date         extends PrimitiveParamType("date")
  case dateTime     extends PrimitiveParamType("dateTime")
  case decimal      extends PrimitiveParamType("decimal")
  case id           extends PrimitiveParamType("id")
  case instant      extends PrimitiveParamType("instant")
  case integer      extends PrimitiveParamType("integer")
  case int64        extends PrimitiveParamType("int64")
  case markdown     extends PrimitiveParamType("markdown")
  case oid          extends PrimitiveParamType("oid")
  case positiveInt  extends PrimitiveParamType("positiveInt")
  case string       extends PrimitiveParamType("string")
  case time         extends PrimitiveParamType("time")
  case unsignedInt  extends PrimitiveParamType("unsignedInt")
  case uri          extends PrimitiveParamType("uri")
  case url          extends PrimitiveParamType("url")
  case uuid         extends PrimitiveParamType("uuid")
  case xhtml        extends PrimitiveParamType("xhtml")
}
object PrimitiveParamType {
  lazy val lookup: Map[String, ParamType] = values.map(x => x.entryName -> x).toMap
}

case class ComplexType(entryName: String) extends ParamType

object ParamType {
  given Encoder[ParamType]             = Encoder.instance[ParamType](Json fromString _.entryName)
  given Decoder[ParamType]             = Decoder.instance[ParamType](_.as[String].map(fromString))
  def fromString(s: String): ParamType = PrimitiveParamType.lookup.getOrElse(s, ComplexType(s))
}
